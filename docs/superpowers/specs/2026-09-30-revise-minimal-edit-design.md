# 改写模块最小化编辑优化 — 设计规格

日期：2026-09-30 ｜ 状态：已评审通过 ｜ 关联：`Generator.revise`、`Prompts`、`TreeText`

## 1. 背景与问题

现状：AI 改写（详情页"建议"输入框 → `NoteProcessor.Kind.REVISE` → `Generator.revise`）是**单次全量重发**：把整棵导图文本、整段思路、最多 6000 字原文和用户要求一起发给模型，要求完整重新输出 `<导图>`+`<思路>`，输出预算 8192 token（截断时自动抬到 16384 并附体量规则再问一次，见 `LlmSession.chatWithDegrade`）。

用户实测反馈（2026-09-29/30）：

- 非 flash 模型经常报「修改失败：本次改动输出太长被截断（原导图未改动）」（`Generator.kt:135-143` 的有意保护：拒绝用半截导图覆盖完好旧图）；换成 flash 模型就正常。
- 改多了：没让它改的节点也被重写/润色。
- 改乱了：层级变乱、节点变啰嗦、导图与思路各说各话。

根因不是模型不行，是架构性的：整图重发意味着输出体量 = 整张导图 + 整段思路，推理型模型的思维链还要计入输出上限，很容易触顶；而一切"重新输出"的内容都可能漂移。

## 2. 目标 / 非目标

**目标**

1. 任意模型下常规改写基本不截断（输出体量从"整图"降到"几行指令"）。
2. 未被编辑指令点名的节点**逐字不变**（"改多了/改乱了"结构性消除）。
3. 手动编辑过的导图内容得到保留。
4. 不弱化现有保护：改前版本快照、回退、截断自动降级、错误文案中文规范、"修改失败"时旧图不动。

**非目标**：生成流水线（三段式 / 长文分段归并）、UI 界面、数据库 schema、导图渲染一概不动。

## 3. 设计

### 3.1 新改写流程

1. 存版本快照（现有逻辑不变，`Generator.revise` 开头）。
2. 组 diff 提示：当前导图用 `MapDiffText.renderNumbered` 渲染成**带路径编号**的行（根下第一枝=`1`，其子=`1.1`…，编号深度优先、只进提示词不落库），连同当前思路、用户建议发给模型（`Prompts.reviseDiffSystem/reviseDiffUser`）。不含 6000 字原文——diff 模式不需要，省输入预算。
3. 模型二选一输出，**由程序按输出格式自动判定**（`ReviseOutput.classify`，严格识别、不走「整段当导图」的宽松兜底，避免指令行被误读成树）：
   - 含 `<修改>` 块 → diff 模式（默认期望）；
   - 含 `<导图>`/`<思路>` 标签或 markdown 分节结构（判定方式同 `LlmOutputParser` 的显式标签匹配）→ 全量替换模式——模型自己判断这是整体重构，直接重画；
   - 两者都没有 → 判无效，走失败链。
4. 应用：
   - diff：`MapEdit.parse` → `MapEditApplier.apply`（**所有路径先对原树解析，再统一应用**，删操作不影响后续编号）→ 新树 + 应用/忽略报告；
   - 全量：输出内容直接作为新导图/思路替换（沿用现行「导图为空」校验与截断降级）。
5. 失败链（每步都保留旧图不动）：
   输出判无效或指令全部被忽略 → 附 `Prompts.diffReminder()` 重问一次 → 仍不可用 → 自动降级：按现行全量格式（`Prompts.reviseUser`，含 6000 字原文参考）重发一次，提示词加硬规则 `Prompts.majorKeepRule()`「只改建议涉及的部分，其余节点与思路逐字保留」（截断自动抬预算 `chatWithDegrade` 一并沿用）→ 再失败才报「修改失败：…」。
6. 截断抢救：`<修改>` 是行原子格式，`LlmError.Truncated.partialContent` 里的**完整行**照常被解析应用（`MapEdit.parse` 天然跳过未闭合尾行），通知条注明"部分指令因输出截断未送达"。
7. 成功写库。`ignored>0` 或有抢救发生时，用现有提示条样式（`note.errorMsg` 非"修改失败"前缀，同 `generate()` 的 notice 机制）告知「应用 N 处修改；M 处位置没对被忽略」。

### 3.2 指令 DSL（v1 只有三种单行操作）

```
<修改>
改 1.2 = 新的节点文字
删 3.1
加 2 = 新加的子节点
</修改>
<思路>改后的整段思路（可选；不输出则保留原思路）</思路>
```

- `改 <path> = 文本`：替换该节点 topic，子树不动。
- `删 <path>`：删除该节点连同子树。
- `加 <path> = 文本`：在该节点 children 末尾追加一个叶子；同一 path 多条 `加` 按出现顺序追加。
- `=` 分隔取路径后的**第一个**等号，其后全部文字（含再出现的 `=`）都是节点文本；`删` 无分隔符。行前后空白与全角冒号等噪声容错跳过。
- 路径指向**改写前**的原树（先解析后应用）。
- 重画某棵子树 = 逐条 `删` 其子节点 + 逐条 `加` 新子节点，可组合出任意结构；v1 不做多行块操作，保持行原子可抢救。
- 空图防护：若应用后 forest 为空，整次判定失败走失败链（不写库）。

### 3.3 文件清单

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/tingsiwei/app/mindmap/MapDiffText.kt` | 新增 | 带编号序列化（`TreeNote` forest → `1.2` 编号行）；编号规则与解析互逆 |
| `app/src/main/java/com/tingsiwei/app/llm/MapEdit.kt` | 新增 | `ReviseOutput.classify`（diff/全量/无效三态判定）+ `<修改>` 文本 → 操作列表；非法行跳过计数；行截断安全 |
| `app/src/main/java/com/tingsiwei/app/llm/MapEditApplier.kt` | 新增 | 原 forest + 操作 → 新 forest + 报告（applied/ignored/原因）；纯函数 |
| `app/src/main/java/com/tingsiwei/app/llm/Prompts.kt` | 修改 | `reviseDiffSystem/reviseDiffUser/diffReminder/majorKeepRule/diffTrimRule` |
| `app/src/main/java/com/tingsiwei/app/llm/LlmClient.kt` | 修改 | `chat` 增加可选 `degradeRule` 参数（默认现行 `trimRule`），diff 调用传 `diffTrimRule` |
| `app/src/main/java/com/tingsiwei/app/llm/Generator.kt` | 修改 | `revise()` 按 3.1 重接线；`<重画>`/失败链分支 |
| `app/src/test/java/com/tingsiwei/app/MapEditTest.kt` | 新增 | 指令解析（合法/非法/混合/截断行抢救） |
| `app/src/test/java/com/tingsiwei/app/MapEditApplierTest.kt` | 新增 | 应用语义（删后加编号稳定、追加顺序、空图守卫） |
| `app/src/test/java/com/tingsiwei/app/ReviseFlowTest.kt` | 新增 | fake Transport 驱动失败链三模式 + 手动编辑逐字保持的端到端断言 |

### 3.4 兼容与数据

- `mapJson`（mind-elixir 结构）、`virtualRoot`、版本表全部不变；编号只存在于提示词文本。
- 旧数据（无编号时代生成的导图）无需迁移——编号在每次改写时现场渲染。
- 失败语义保持：`errorMsg` 以「修改失败」开头时 UI 的「重试」只清提示不重跑生成（`DetailScreen.kt:517`）。

## 4. 测试计划

1. 单测（JVM，无 Android 依赖，注入 fake）：
   - `MapDiffText` 编号 ↔ 还原互逆（含 virtualRoot、空格缩进退化）。
   - `MapEdit.parse`：`改/删/加` 合法行、路径含空格/全角冒号、乱码行、未闭合尾行。
   - `MapEditApplier`：改根、删最后分支、同路径多次加、路径不存在计数、空图守卫。
   - 失败链：diff→reminder→全量降级每一跳；`Truncated` 行抢救；classify 三态（含"指令行不被误判为导图"用例）。
2. 回归：现有 98 例 JVM 单测全绿；`assembleDebug` 通过。
3. 真机清单（追加到 `docs/迭代计划/用户实测清单-v1.md`）：非 flash 模型下「改第 2 枝措辞」不截断、未点名节点逐字一致、手动编辑保留、大改请求走重画、失败可回退版本。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 模型给出不存在的路径 | 逐条忽略并计数，通知条如实上报；连续全忽略走提醒重问 |
| 小指令表达不了的大重构 | 模型输出完整 `<导图>` 时程序直接按全量替换收下；失败链末端也有全量重发兜底，行为退回现状不更差 |
| 思维链吃预算连小输出也截断 | 行原子格式截断可逐行抢救 + 现有 `chatWithDegrade` 抬预算 |
| 提示词注入（用户建议文本诱导输出指令） | 建议文本按原样呈现于独立小节；应用前路径必须命中原树；写库走既有 `TreeText.toMapData`，无越权面 |

## 6. 验收标准

1. 用户现有非 flash 模型：对 30+ 节点导图提出措辞级修改，一次成功率 ≥ 现行 flash 水平，无「输出太长被截断」报错。
2. 未点名节点在结果 mapJson 中逐字不变（自动化断言覆盖）。
3. 手动编辑 + AI 改写交替使用时，不丢版本（快照/回退可用）。
