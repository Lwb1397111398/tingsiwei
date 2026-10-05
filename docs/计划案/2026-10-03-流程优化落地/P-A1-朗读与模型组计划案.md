# P-A1 小计划案：朗读与模型组总览补账（3 份）

## 目标

把 3 份过期总览对照 v1.5.0 代码（commit `024692a`）补记到位，清偿 9/27–9/30 WIP 期间欠下的文档账。

## 文档清单与需补记的代码事实

| 总览 | 过期原因（待起草代理核实补充） | 事实来源 |
| --- | --- | --- |
| `TTS朗读模块总览.md` | 新增 kokoro 离线引擎（SherpaTts.kt、TtsChunkCallback.java），TtsPlayer 改为系统/本地双引擎 | `tts/SherpaTts.kt`、`tts/TtsChunkCallback.java`、`tts/TtsPlayer.kt`、`ui/SettingsScreen.kt` 朗读段 |
| `模型下载模块总览.md` | ModelManager 新增 TTS 模型（kokoro）下载与旧 vits-melo 清理（`cleanupLegacyTts`，App.kt:23 代码注释声明腾约 190MB——melo 目录从未入库，总览须写明"注释声明"并核对真常量 `TTS_TOTAL_BYTES`（ModelManager.kt:413，kokoro 侧体积），不许把注释数字当实测） | `transcribe/ModelManager.kt`、`App.kt`、`ui/SettingsScreen.kt` |
| `通用工具模块总览.md` | 体检工具按 mtime 报过期；逐项复核 Formatters.kt 与总览内容，代码没改就如实写"复核无误"，改了就补记 | `util/Formatters.kt` |

## 执行方式

起草子代理 1 个（并行于 P-A2/A3/B）：只允许 Read 代码与 Write 上述 3 份总览，禁止碰 `app/src/`、README 索引（README 行如需改动，报告主会话，由主会话转交 P-A2 组（README 独占方）执行或待其完成后处理，避免两方先后写同一文件）。运作流程必须按真实数据流写、落到实体（文件/类/函数/数值），无「某种/相关/一些/适当/等等」占位词；行号只写本次核对过的。

## 验收标准

1. 每份按 P0 §3「文档产出」权重评分 ≥90 且准确性问题零未修复（独立评分官，复用 P0 评审会话）；
2. `check_modules.py` 对应模块转 ok；
3. 涉及引擎选择/模型体积等数字与代码常量一致（评分官抽查 ≥3 处）；
4. README 索引行与总览职责句不冲突（如冲突，报主会话改 README）。
