# LLM 输出解析模块总览

## 模块职责

把模型返回的一段自由文本切成「导图缩进树文本 + 思路正文」两部分，并对标签写法、代码块包裹、标签缺失等不规范输出做容错（`LlmOutputParser.kt:21`）。

## 运作流程

**从哪来**：一次 chat 的原始字符串。三个调用点各拿各的：

- 短文直出：`Generator.kt:180`（`directGenerate` 里 `LlmOutputParser.parse(ask(...))`）
- AI 按建议改图：`Generator.kt:116`
- 长文流水线的最终生成：`LongTextPipeline.kt:273`（分段提炼 / 归并阶段**不经过本模块**，那两步要求模型裸输出大纲，见 `Prompts.kt:44-62`）

**怎么处理**：按优先级三层兜底，全部在同一函数里（`LlmOutputParser.kt:21-45`）——

1. 标签层（`LlmOutputParser.kt:24-32`）：`Regex("<\\s*导图\\s*>([\\s\\S]*?)<\\s*/\\s*导图\\s*>")` 与思路版各自 `find`，非贪婪、只取第一处；导图块内容过 `stripFences` 后若非空白即采纳。思路取 `<思路>` 块；**没有思路块时**退化为「导图块结束位置之后的全部剩余文本」（`LlmOutputParser.kt:30-31`）。
2. markdown 分节标题层（`LlmOutputParser.kt:36-41`）：`headingMap` 要求「`#`+ 或 `**`」前缀 + `思维导图|导图`，右界到下一个「`#`/`**` + `思路|记忆思路`」或文末；思路同理（`headingThink`）。
3. 终极兜底（`LlmOutputParser.kt:44`）：整段文本 `stripFences` 后当导图，思路为空串。

产出：`Parsed(mapText, thinking)`（`LlmOutputParser.kt:15`），`mapText` 即 `TreeText` 约定的 TAB 缩进树文本。

**影响谁**：`Generator` 与 `LongTextPipeline` 拿 `mapText` 去 `TreeText.parse` 判空并转 JSON（`Generator.kt:120,122`），`thinking` 落 `notes.thinking`（`Generator.kt:74,126`）→ 供思路页展示、TTS 朗读与「改图」回灌（`Generator.kt:112`）。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/mindmap/LlmOutputParser.kt` | 46 | 唯一实现：`Parsed` 数据类 + `stripFences` + 三层兜底的 `parse` |
| `app/src/test/java/com/tingsiwei/app/TreeTextTest.kt` | 151 | 配测 `LlmOutputParserTest`（5 例）就写在该文件 **第 102-151 行**，与 `TreeTextTest` 共存一个文件 |

## 对外接口 / 被谁调用

- `object LlmOutputParser`（`LlmOutputParser.kt:13`）
  - `data class Parsed(mapText: String, thinking: String)`（`LlmOutputParser.kt:15`）
  - `fun parse(raw: String): Parsed`（`LlmOutputParser.kt:21`）——**不抛异常、不返回 null**（仅 `Regex` 求值，无 `require`/`throw`）
  - `private fun stripFences(s: String): String`（`LlmOutputParser.kt:18`）
- 调用方：`Generator.kt:116,118,180,182`、`LongTextPipeline.kt:253,273,276`。导入处 `Generator.kt:8`、`LongTextPipeline.kt:3`。
- 解析失败时上游怎么处理（本模块自己不报错，失败判定交给格式层）：
  1. `TreeText.parse(parsed.mapText).isEmpty()` → 追加 `Prompts.formatReminder()` 重问一次（`Generator.kt:117-119`、`Generator.kt:181-183`、`LongTextPipeline.kt:274-279`）。
  2. 仍为空 → 抛 `LlmException`（`Generator.kt:121`、`Generator.kt:184-186`、`LongTextPipeline.kt:280-282`）。
  3. `Generator` 捕获后写 `NoteStatus.ERROR` + 友好文案；改图路径带「修改失败」前缀以保住原导图（`Generator.kt:133-143`），界面「重试」据此只清提示不重跑整篇（`DetailScreen.kt:499-503`）。
- 提示词侧对偶：`Prompts.formatReminder()` 专门纠正标签（`Prompts.kt:39-41`），`trimRule()` 要求「标签必须闭合」（`Prompts.kt:36-37`）。

## 依赖的上游模块

- 无：只用 Kotlin stdlib 的 `Regex` / `String` API（`LlmOutputParser.kt:19,24-25,36-37`），不 import 任何 Android / kotlinx 符号，因此可跑纯 JVM 单测。
- 概念上依赖格式约定：`mapText` 的正确性由 `TreeText` 判定（`TreeText.kt:28`），二者构成「解析 → 校验」的隐式契约。

## 关键数据结构

- `Parsed(mapText, thinking)`：`mapText` 是**已剥代码围栏并 trim 过**的缩进树文本，`thinking` 是 trim 过的自然段文本（`LlmOutputParser.kt:15,29-31`）。
- 正则捕获组：两个标签正则都是单捕获组 `([\s\S]*?)`（`LlmOutputParser.kt:24-25`），跨行匹配靠 `[\s\S]` 而非 `RegexOption.DotMatch`。
- 标签容错的精确边界：`<` 与标签名之间 `\s*`、`/` 与标签名之间 `\s*`、标签名与 `>` 之间 `\s*`（`LlmOutputParser.kt:24-25`）——所以 `< 导图 >`、`<导图 >`、`< /导图 >` 都能认。
- `stripFences`：`Regex("```+[a-zA-Z0-9_-]*\\n?")` 去掉 ``` 开头及其后语言名与一个换行，再兜一次 `replace("```","")`，最后 `trim()`（`LlmOutputParser.kt:18-19`）。因此 ```` ```xml ````、```` ```` ````、成对/落单围栏都能清掉。

## 已知坑与约束

1. **`thinking` 不走 `stripFences`**（`LlmOutputParser.kt:30-31` 与 `:40` 都只 `trim()`，而 `mapText` 走了 `stripFences`，`:29`）：模型把思路整段用 ```` ``` ```` 包住时，围栏符号会原样落进 `notes.thinking`，思路页与 TTS 都会读到反引号。
2. **标签未闭合 = 标签文本泄漏成节点**：`mapMatch == null`（缺 `</导图>`）时直接跳到兜底层，终极兜底把整段当导图（`LlmOutputParser.kt:44`），于是 `<导图>`、`<思路>` 这些字符串本身会被 `TreeText.parse` 当成顶层节点，且极易连带触发虚拟根（`TreeText.kt:85`）。同理，标签写成 `<导图/>` / `<导图 />` 也不匹配（闭标签模式只允许 `>` 前有空白，`LlmOutputParser.kt:24`）。
3. **多组标签只取第一组**：`find` 非全局，模型「先给样例再给正式答案」时取到的是样例（`LlmOutputParser.kt:27,30`）。
4. **顺序颠倒没问题，位置兜底有前提**：两个标签各自独立 `find`，所以 `<思路>` 在前也能取到（`LlmOutputParser.kt:24-31`）；但缺 `</思路>` 时退化为「导图块之后」的文本（`:31`），此时写在导图**之前**的思路正文会整段丢失（返回空串）。
5. **`<导图></导图>` 命中但内容为空白时不采纳**（`LlmOutputParser.kt:32` 的 `isNotBlank()` 判断），会继续走兜底，最终产出「mapText = 含空标签的整段原文、thinking = ""」——即此路径下**已匹配到的思路也会被丢掉**。
6. **markdown 兜底要求显式标记**：`headingMap`/`headingThink` 都必须有 `#` 或 `**`（`LlmOutputParser.kt:36-37`），模型只写「导图：」「思路：」纯文本分节时不识别，会整体当导图。
7. **终极兜底让「解析失败」几乎不可能被察觉**：只要 `raw` 非空，`parse` 基本都能给出非空 `mapText`，而 `TreeText.parse` 又会把任何非空行变成节点（`TreeText.kt:41-43`），所以 `Generator.kt:57` 那条「导图是空的」只在模型返回纯空白时才触发；模型输出客套话时，废话会变成导图顶层节点而不是报错。
8. 全角尖括号 `＜导图＞`、别名标签 `<思维导图>` 均不识别（`LlmOutputParser.kt:24` 只匹配字面 `导图`）；但 `LlmOutputParser.kt:11` 的注释写着「标签名可变体」——**实际只有空白位置可变体，标签名不可**，`思维导图` 这个别名只在 markdown 兜底路径生效（`:36`）。
9. 测试覆盖（5 例，`TreeTextTest.kt:102-151`）：标准标签格式（:104）、代码块包裹+空白变体（:120）、缺思路标签取剩余（:128）、无标签整体当导图（:136）、导图块内嵌围栏（:144）。**未覆盖**：markdown 标题兜底（坑 6）、标签未闭合（坑 2）、多组标签（坑 3）、`<导图></导图>` 空块（坑 5）、thinking 带围栏（坑 1）。
10. 与索引 README 的对照：README 说「容错解析 `<导图>…</导图>` + `<思路>…</思路>`（容忍空格/代码块/缺失标签）」——三条都与代码一致（`:24-25`、`:18-19`、`:31,36-44`）。这 5 例测试与 `TreeTextTest` 同处一个文件（`app/src/test/java/com/tingsiwei/app/TreeTextTest.kt:102`），按模块名去找 `LlmOutputParserTest.kt` 会找不到文件——索引 README 的模块索引行与「构建说明」两处现已写明这一点（2026-09-20 补总览时定稿）。
11. 本模块不涉及 WebView / mind-elixir，画布相关的坑见《导图画布模块总览》《导图格式引擎模块总览》。

## 最后更新

2026-09-20 · 补写模块总览（首次成文）
