# P-A3 小计划案：LLM 与界面组总览补账（6 份）

## 目标

更新 LLM 链路与界面侧 6 份过期总览，覆盖流式会话、截断抢救、三段式提示词与开关、宿主接线等 v1.2–v1.5 变化。

## 文档清单与需补记的代码事实

| 总览 | 需补记内容 | 事实来源 |
| --- | --- | --- |
| `LLM客户端与弹性层模块总览.md` | LlmSession 流式会话（stream=true 透传与 SSE 解析增强）、相关新增测试（LlmSessionStreamTest）；`DEFAULT_MAX_OUTPUT_TOKENS=16384`、400 硬帽降档已在 9/30 提交，核对总览是否漏记（常量与降档逻辑住在 `llm/LlmPolicy.kt`，:134/:100，事实来源须写 LlmPolicy.kt 而非 LlmSession.kt） | `llm/LlmSession.kt`、`llm/LlmPolicy.kt`、`app/src/test/.../LlmSessionStreamTest.kt` |
| `LLM输出解析模块总览.md` | `parsePartial` 部分内容抢救（截断时带部分内容回传，调用方 `parseOrSalvage` 已接）；新增 `LlmOutputParserPartialTest` | `mindmap/LlmOutputParser.kt`、`llm/Generator.kt`、新测试文件 |
| `提示词模块总览.md` | 三段式提示词组（draftThinking/refineThinking/mapFromThinking）、refineOutline 组、diffTrimRule 等；与「思路精修」开关的对应关系 | `llm/Prompts.kt` |
| `详情页与生成编排模块总览.md` | busyStage 接 `NoteProcessor.stageFlow`（本地 stage 优先）、maybeRunPipeline 与宿主 `maybeRun` 的分工、思路文本出现（拓展）标注（标注产自 `Prompts.kt:95` 三段式提示词要求，详情页只是原样渲染，**无**专门标注渲染逻辑，总览不许杜撰）等 WIP 变化 | `ui/DetailScreen.kt` |
| `导图画布模块总览.md` | 复核 MindMapPanel/index.html 是否被 WIP 改动；没改就写"复核无误" | `ui/components/MindMapPanel.kt`、`assets/mindmap/` |
| `导图格式引擎模块总览.md` | TreeNote.topic 改 var（就地编辑，commit 18eb5ef）等残留未记变化 | `mindmap/TreeText.kt` |

## 执行方式

起草子代理 1 个（并行）：只允许 Read 代码与 Write 上述 6 份；禁止碰 README 与 `app/src/`。特别注意：`生成流水线`、`设置页`、`数据层` 三份主会话 10/3 已更新，本组**只读不写**，涉及交叉引用时以它们现文为准，发现矛盾报告主会话裁决。

## 验收标准

1. 每份按 P0 §3「文档产出」权重评分 ≥90 且准确性问题零未修复；
2. `check_modules.py` 对应模块转 ok；
3. 与已更新的 3 份总览无事实矛盾（评分官抽查交叉引用点 ≥2 处）；
4. 流式/截断抢救等描述须与 LlmSession/LlmOutputParser 实际行为一致（评分官对照代码抽查）。
