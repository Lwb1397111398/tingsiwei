# P-A2 小计划案：任务宿主与转写录音组（新建 1 + README 1 行 + 更新 5，共 7 处）

## 目标

给 v1.2–v1.5 最重要的新模块「笔记处理宿主」补建总览，并更新转写录音链路上 5 份过期总览。

## 动作清单

| # | 动作 | 说明 |
| --- | --- | --- |
| 1 | **新建** `笔记处理宿主模块总览.md` | 覆盖 `pipeline/NoteProcessor.kt`：App 级协程宿主，TRANSCRIBE/GENERATE/REVISE 三类任务、幂等启动（同笔记互斥）、`stages` 进度流、`maybeRun`（打开笔记自动续跑）与 `resumeStuck`（App 启动拉起全部半路任务）；关键设计：转写完成自动接生成（`runTranscribe` → `runGenerate`，`NoteProcessor.kt:106-137`）——这正是"提取后 LLM 处理慢"被用户每次必感的入口，须在「已知坑与约束」注明与生成流水线模块的联动 |
| 2 | **README 索引**新增该模块一行 | 插在「详情页与生成编排」之后；职责一句话 |
| 3 | 更新 `录音服务模块总览.md` | RecordSession 与宿主的接线（状态机产出方） |
| 4 | 更新 `首页模块总览.md` | HomeScreen 徽章接 `NoteProcessor.stages`、删笔记清断点等变化（`HomeScreen.kt:284` 清的是 SegmentStore 分段落盘断点文件，总览须写到该实体，不写笼统"清断点"） |
| 5 | 更新 `转写模式选择模块总览.md` | SettingsRepository 本轮新增 `llmStagedThinking` 键对"定义在此"的影响；行号重校 |
| 6 | 更新 `系统识别模块总览.md` | 复核 SystemSpeechRecognizer 是否被 WIP 改动；没改就如实写"复核无误" |
| 7 | 更新 `入口与导航模块总览.md` | App.kt 新增 `NoteProcessor.resumeStuck()` 与 `cleanupLegacyTts` 后台清理、Manifest/gradle 变化 |

## 执行方式

起草子代理 1 个（并行）：只允许 Read 代码与 Write 上述 1 份新建 + 5 份更新；README 第 2 项动作也由本代理执行（P-A1/A3 被明确禁止碰 README，避免并行写冲突，故 README 独占给本组）。特别纪律：`生成流水线`、`设置页`、`数据层` 三份总览是主会话 10/3 已更新的，本组**只读不写**，交叉引用以它们现文为准，发现矛盾报告主会话裁决。

## 验收标准

1. 全部产出按 P0 §3「文档产出」权重评分 ≥90 且准确性问题零未修复；
2. `check_modules.py` 对应模块转 ok；
3. 新总览的 8 字段齐全，运作流程从"任务从哪来"到"写库"每步落到实体；
4. README 新行与总览职责句、代码目录三方一致（评分官实查）。
