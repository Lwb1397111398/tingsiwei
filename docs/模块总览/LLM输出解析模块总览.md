# LLM 输出解析模块总览

## 模块职责

把模型返回的一段自由文本切成「导图缩进树文本 + 思路正文」两部分（`Parsed(mapText, thinking)`），并对不规范输出做容错（`LlmOutputParser.kt:21`）：标签两侧带空白、被 markdown 代码块包裹、标签名写成 `< 导图 >`、思路标签缺失等都能吃下。**还负责截断抢救**：输出被长度上限砍断、`</导图>` 没闭合时，按标签实际闭合情况切开、丢掉被拦腰砍断的尾行，抢救出大半个导图而不是整段作废（`LlmOutputParser.kt:51-89`，v1.5.0 配套新增）。

## 运作流程

**从哪来**：一次 chat 的原始字符串，五个调用点各拿各的：

- **直路/三段式出图**：`Generator.parseOrSalvage`（`Generator.kt:308-320`）——正常走 `parse`（`:319`）；抛 `LlmError.Truncated` 时先用 `parsePartial` 抢救 `e.partialContent`（`:316`），抢救成功再 `render` 包回标准标签、二次走同一条 `parse`（`:317`）——解析器自己是格式转换方，也是抢救后的复检方。
- **长路总生成**：`LongTextPipeline.finalGenerate`（`LongTextPipeline.kt:364`、`367`）解析正式与重问两次返回；`callOrSalvage` 里同样的「截断→`parsePartial`→`render`」抢救链（`LongTextPipeline.kt:389-393`），且抢救出的导图能用 `TreeText.parse` 验证有结构才算成功（`:390-391`），否则把截断异常原样上抛（`:391`）。
- **改写判型**：`ReviseOutput.classify`（`MapEdit.kt:59-73`，对象名 `ReviseOutput`、结果装进 `Decision` `:52`）对模型回包三态判定——见 `<修改>` 块走 diff（只把思路文本 `Parsed` 存进 Decision，`:65`）；见 `<导图>`/markdown 分节即判整体重构，转交 `parse` 全量解析（`:71`）；都没有算无效（`:73`）。`MapEdit.kt:48` 注释点名：diff 判型要赶在 `parse` 的宽松兜底之前，否则裸指令行会被兜底误读成一棵导图。
- **改写全量兜底**：`ReviseFlow` 拿到全量回包后 `parse`（`ReviseFlow.kt:102`），结果喂 `acceptFull`（`:82`）。

**怎么处理**（`parse`，`LlmOutputParser.kt:21-45`，三层递进）：

1. **标准标签**：正则 `<\s*导图\s*>([\s\S]*?)<\s*/\s*导图\s*>` 找 `<导图>…</导图>`（容忍标签内空白，`:24`）；命中就取内容 `stripFences` 剥围栏（`:29`），思路优先找 `<思路>…</思路>`（`:30`），找不到就把导图标签之后的剩余全文当思路（`:31`）——**思路内容本身不被围栏剥离**，只有导图正文剥，因为思路是给人读的叙述文本。
2. **markdown 分节兜底**：没有标签时找「# 思维导图 / **导图**」这类分节标题形式（`:36-41`），导图段截止到下一个「# 思路」标题或文末；标题变体、`*`/`#` 混用都覆盖。
3. **最后兜底**：整体当导图、思路为空（`:44`）——这就是坑里「宽松兜底必须放在 diff 判型之后」的出处。

**截断抢救**（`parsePartial`，`LlmOutputParser.kt:51-89`，与 `parse` 分开成另一条入口——标签不闭合时 `parse` 会一路退到兜底把 `<导图>` 碎片当节点内容）：

- 先 `stripFences`（`:52`，围栏不挡抢救，用例 `LlmOutputParserPartialTest.kt:42-47`）。
- `<导图>` **闭合**：导图完整取整；思路闭合则整取，思路只有开标签就把后半截 `dropTruncatedTail` 丢尾行（`:60-68`）。
- `<导图>` **未闭合**：取开标签后的内容，若后面挂着残缺的 `<思路>` 一并切开（`:71-85`），导图与思路都 `dropTruncatedTail`。
- **连 `<导图>` 开标签都没有**：返回 null（`:86-87`）——导图救不回来，上层（`Generator.kt:316`、`LongTextPipeline.kt:390-391`）据此把截断异常继续上抛。
- `dropTruncatedTail`（`:92-98`）：被长度上限砍断的几乎总是最后一行/最后一句，退到最近的 `\n`/`。`/`；` 边界；什么都不剩返回空串。
- `render`（`:101-112`）：把抢救结果包回标准 `<导图>/<思路>` 标签，让上层走同一条 `parse` 复检——**一条解析路径，不做两套**。

**产出什么**：`Parsed(mapText, thinking)`。`mapText` 交给 `TreeText.parse` 判空/结构校验（空 → 触发 `formatReminder`/`mapOnlyReminder` 重问或报「AI 没有按格式返回导图」），最终 `TreeText.toMapData` 落库；`thinking` 直接写 `notes.thinking`。

**影响谁**：`Generator` 直路/三段式、`LongTextPipeline` 总生成、`ReviseOutput.classify`/`ReviseFlow` 改写链（见「生成流水线模块总览.md」）。它自己不被 UI 调用。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/mindmap/LlmOutputParser.kt` | 113 | `parse`（三层容错）、`parsePartial`（截断抢救）、`render`（抢救结果包回标准格式）、`stripFences`/`dropTruncatedTail` 两个内部件 |
| `app/src/test/java/com/tingsiwei/app/TreeTextTest.kt`（后半段） | :102-151 | `LlmOutputParserTest` 5 例：标准标签、代码块+空白变体、缺思路取剩余、无标签整体当导图、标签内围栏剥除——**与 `TreeTextTest` 同文件**，按文件名去找会误判成缺失 |
| `app/src/test/java/com/tingsiwei/app/LlmOutputParserPartialTest.kt` | 62 | 截断抢救 6 例：未闭合丢尾行、闭合但思路截断、完整不丢、围栏不挡、null 条件（空/仅空白/只有 `<思路>`）、`render→parse` 同路复检 |

## 对外接口 / 被谁调用

| 对外符号 | 位置 | 被谁调用 |
| --- | --- | --- |
| `Parsed(mapText, thinking)` | `LlmOutputParser.kt:15` | `Generator.kt:301`（`AskResult` 成员）、`MapEdit.kt:52`（`Decision.parsed`）、`LongTextPipeline.kt:334`（`Generated.parsed`）、`ReviseFlow.kt:82` |
| `parse(raw): Parsed` | `LlmOutputParser.kt:21` | `Generator.kt:317`、`319`；`LongTextPipeline.kt:364`、`367`；`MapEdit.kt:71`；`ReviseFlow.kt:102` |
| `parsePartial(raw): Parsed?` | `LlmOutputParser.kt:51` | `Generator.kt:316`（直路截断抢救）；`LongTextPipeline.kt:389`（总生成截断抢救） |
| `render(parsed): String` | `LlmOutputParser.kt:101` | `Generator.kt:317`、`LongTextPipeline.kt:393`（包回标签后复检） |
| `stripFences` / `dropTruncatedTail` | `LlmOutputParser.kt:18` / `92` | 仅本文件内部（`parse` 与 `parsePartial` 共用） |

## 依赖的上游模块

- **无工程内依赖**：只用 `Regex`/标准库。它产出的 `mapText` 格式约定与 `Prompts.kt:21-29` 的输出要求成对（标签独占一行、TAB 缩进、不套代码块），但**不 import** `Prompts`。
- 下游消费：`TreeText.parse`（`Generator.kt:211`、`295`、`LongTextPipeline.kt:365`、`371`、`390`——`mapText` 必须能解析出树才算有效）、`LlmError.Truncated.partialContent`（弹性层截断异常带回的部分内容，见「LLM客户端与弹性层模块总览.md」）。

## 关键数据结构

| 结构 | 位置 | 说明 |
| --- | --- | --- |
| `Parsed` | `LlmOutputParser.kt:15` | `data class`，两个纯字符串；`thinking` 可为空串（无标签兜底路径），`mapText` 可为空串（`MapEdit.kt:65` diff 模式故意留空导图位） |
| 标签正则 | `LlmOutputParser.kt:24-25`、`36-37` | 标签匹配容忍 `\s*` 空白；markdown 分节兜底容忍 `#+`/`**` 混用与「思维导图/导图/思路/记忆思路」四种标题词 |
| 抢救的三种形态 | `LlmOutputParser.kt:59-88` | ①`<导图>` 闭合（思路可残）②`<导图>` 未闭合（切残缺思路）③无 `<导图>`（null）。判定顺序不能乱：先找闭合、再找开标签、最后 null |

## 已知坑与约束

1. **`parse` 的最后兜底太宽松，diff 判型必须在前**：任何带 TAB 的裸文本都会被 `parse` 当成一棵导图（`:43-44`）。`ReviseOutput.classify` 靠 `MapEdit.kt:48` 注释写明的顺序避开：先看 `<修改>` 再看 `<导图>`/分节，都没有才 Invalid——调换顺序会把无效回包误判成全量重构。
2. **围栏只剥导图不剥思路**：`parse` 里 `stripFences` 只作用于 `mapText`（`:29`），思路标签缺失时的"剩余全文当思路"（`:31`）**不剥围栏**——模型如果给思路也套了代码块，``` 会留在思路正文里。目前测试与用例都锁的是"导图内围栏被剥"（`TreeTextTest.kt:143-151`），思路侧无围栏断言。
3. **`parsePartial` 是显式第二条入口，不是 `parse` 的自动降级**：上层必须自己捕获 `LlmError.Truncated` 并主动调它（`Generator.kt:313-318`、`LongTextPipeline.kt:388-393`）——忘记捕获 = 截断直接失败。两条路径共用 `stripFences`/`dropTruncatedTail`，改这两个内部件会同时影响正常与抢救行为。
4. **`render` 包回标准标签是为了「一条解析路径」**（`LlmOutputParser.kt:100` 注释）：抢救结果不直接信任，`render → parse` 复检 + `TreeText.parse` 结构校验双保险（`LongTextPipeline.kt:390`）。跳过 `render` 直接拿抢救结果用，格式漂移就没人兜底了。
5. **缺 `<思路>` 时把"剩余全文"当思路**是有意设计（`:31`）：模型经常导图写完思路忘了标签；副作用是——如果导图标签后还粘着别的解释文字，会一并进思路。提示词侧靠 `formatReminder`（`Prompts.kt:40`）压格式，解析侧不猜。
6. **`TreeTextTest` 与 `LlmOutputParserTest` 同文件**（`TreeTextTest.kt:10` 与 `:102`）：README 与本文档都特意标注过，因为按"一个测试类一个文件"去找会误判 `LlmOutputParserTest` 不存在——2026-09-20 那轮排查真的被误判过一次。
7. **思路截断丢尾行的边界**：`dropTruncatedTail` 只退到 `\n`/`。`/`；`（`:96`），半句里的逗号不算边界——宁丢一句不丢半句，但模型用英文标点收尾的长句会被整句丢。

## 最后更新

2026-10-04 · 按 v1.5.0 当前代码全量重写（旧版停在 2026-09-20）。新增建档：**截断抢救链**（`parsePartial`/`render`/`dropTruncatedTail`，配合弹性层 `LlmError.Truncated.partialContent`）与 `LlmOutputParserPartialTest` 6 例；调用点从旧版 3 处更新为 5 处（新增 `Generator.parseOrSalvage` 与 `LongTextPipeline.callOrSalvage`）；坑 1/3/4 即本轮新增约束。`LlmOutputParserTest` 5 例位置核对未变（`TreeTextTest.kt:102-151`）。
