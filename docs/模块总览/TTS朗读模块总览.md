# TTS朗读模块总览

## 模块职责

把笔记的「记忆思路」朗读出来：`TtsPlayer` 按设置在「系统 TextToSpeech」与「sherpa-onnx kokoro 离线模型」两个引擎之间分发，长文按句切块合成出声，朗读中可随时调速，引擎缺东西时明说提示、绝不静默失败。

## 运作流程

**从哪来**：两个入口。

1. 详情页「思路」标签底部按钮（`ui/DetailScreen.kt:380`）→ `DetailViewModel.toggleTts()`（`DetailScreen.kt:627-633`）从 Room 读 `n.thinking`（`DetailScreen.kt:629-630`）→ `toggleTtsText(thinking)`（`DetailScreen.kt:636-649`）：正在读就 `tts.stop()`（`:639`），否则读 `settingsRepo.current().ttsEngine`（`:642`）→ `tts.speak(text, ttsRate.value, engine)`（`:643`）→ 把 `ttsSpeaking` 置 true、`while (tts.isSpeaking) delay(300)` 轮询（`DetailScreen.kt:644-646`）。`TtsPlayer` 实例是 VM 的 `by lazy`（`DetailScreen.kt:472`），构造时传入的 `onMessage` 回调就在这一行里写入 `ttsMessage`（字段声明在 `:476`），UI 侧 `LaunchedEffect` 弹 Snackbar（`DetailScreen.kt:99-107`）。
2. 设置页「朗读」卡片「试听当前引擎」按钮（`ui/SettingsScreen.kt:359-361`）→ `togglePreview()`（`SettingsScreen.kt:572-584`）用固定句子「你好，这是听的思维的朗读试听。一二三四五，上山打老虎。」调 `previewTts.speak(..., ttsRate.value, ttsEngine.value)`（`SettingsScreen.kt:579`）；`previewTts` 也是 `by lazy`（`SettingsScreen.kt:570`），`onMessage` 写入设置页自己的 `message` Snackbar 通道。

**怎么处理**：`TtsPlayer.speak(text, rate, engine)`（`tts/TtsPlayer.kt:64-83`）第一句先 `stop()` 清场、`liveRate = rate`（`TtsPlayer.kt:65-66`），再按 `engine` 字符串分发（常量取 `data/SettingsRepository.kt:24-28` 的 `TtsEngine.SYSTEM="system"` / `TtsEngine.LOCAL="local"`）：

- **离线引擎分支**（`TtsPlayer.kt:68-76`）：先 `ModelManager.isTtsReady(appCtx)` 判 kokoro 全部 373 个文件是否就绪（`transcribe/ModelManager.kt:539-542`）。就绪 → `speakLocal(text)`；未就绪 → 退回系统引擎并在成功时提示「离线朗读模型还没下载，这次先用系统语音；可在设置里下载（约 215MB）」（`TtsPlayer.kt:72`），系统引擎也没有则提示「本机没有可用的朗读引擎…」（`:74`）。
  - `speakLocal`（`TtsPlayer.kt:250-327`）先 `playing.set(true)`（`:251`，覆盖模型加载那几秒），`LocalTtsEngine.obtain(appCtx)` 拿进程级单例（`:257`；`LocalTtsEngine` 在 `TtsPlayer.kt:333-348`，double-checked 加锁，模型加载一次常驻复用），然后建 `AudioTrack`：PCM 16bit 单声道、采样率取自引擎、缓冲 `maxOf(minBuf * 4, 16384)` 字节（`TtsPlayer.kt:263-282`）。
  - 合成走逐句循环：`splitForSpeech(text, maxChars = 120)` 先切句（`:304`），每句调 `engine.tts.generateWithCallback(chunk, SherpaTts.VOICE_ID, liveRate, TtsChunkCallback(chunkHandler))`（`:306`）。回调里把 float 采样逐个乘 32767f、钳到 [-32768, 32767] 转 PCM16，`AudioTrack.write(..., WRITE_BLOCKING)` 阻塞写入——写多快播多快；回调返回 `playing.get()`，停止时返回 false 让 native 合成停下（`TtsPlayer.kt:289-300`）。每句合成前读一次 `liveRate`，朗读中拖调速条、当前句放完下一句立即按新语速来（`:301-303` 注释）。合成完等缓冲区播完（`playbackHeadPosition` 轮询，`:309`）。
  - `SherpaTts`（`tts/SherpaTts.kt:14-41`）是 kokoro-int8-multi-lang-v1_1 的配置封装：`model.int8.onnx` + `voices.bin` + `tokens.txt` + `espeak-ng-data` 目录 + 三个 lexicon（zh / us-en / gb-en）+ jieba 词典 `dict/`（`SherpaTts.kt:22-28`），`ruleFsts = phone-zh.fst / date-zh.fst / number-zh.fst`（`:34-35`），`maxNumSentences = 1`、`silenceScale = 0.5f`（压掉 kokoro 在句读处拖的长停顿，`:36-38`），线程 2、provider cpu。默认音色 `VOICE_ID = 3`——v1.1 里 sid 0-2 是英文女声、3 起才是中文女声 zf_001（`SherpaTts.kt:43-45`）。
  - 回调必须用 Java 类 `TtsChunkCallback`（`tts/TtsChunkCallback.java:10-27`）：sherpa-onnx 的 JNI 用硬编码签名 `invoke([F)Ljava/lang/Integer;` 反查回调方法，Kotlin 2.x 编译的 lambda（indy）没有这个桥接方法，合成首个分片会直接 abort（KDoc `TtsChunkCallback.java:5-9`；`TtsPlayer.kt:287` 注释同）。
- **系统引擎分支**（`TtsPlayer.kt:77-81`）：`speakSystemOrQueue` 失败才提示「本机没有可用的系统朗读引擎…」（`:79`）。
  - 同步预检 `hasSystemEngine()`：`packageManager.queryIntentServices(TTS_SERVICE intent)` 查本机装没装 TTS 引擎（`TtsPlayer.kt:123-128`）；Android 11+ 能查到靠 Manifest 的 `<queries>` 声明（`AndroidManifest.xml:10-16`，注释写明「允许发现系统 TTS 语音引擎」）。
  - `speakSystemOrQueue` 四态分发（`TtsPlayer.kt:131-160`）：引擎未创建 / 初始化中 → 文本暂存 `pendingSystemText` 后创建或等待；就绪 → 直接 `speakSystem`；上次初始化失败 → shutdown 旧实例重建再试（`:148-158`）。
  - `createSystem`（`TtsPlayer.kt:162-188`）挂 `TextToSpeech` 初始化回调：成功则设 `Locale.CHINA`、挂 `UtteranceProgressListener`、冲掉 `pendingSystemText`（`:164-176`）；部分机型（裸模拟器）没有任何引擎时回调永不回来，挂 6 秒超时当失败处理（`:177-187`，`delay(6000)` 在 `:179`）。
  - `speakSystem`（`TtsPlayer.kt:227-235`）把全文 `splitForSpeech(maxChars = 120)` 切块（`:229`）——系统引擎单条 utterance 有 4000 字上限（`TtsPlayer.kt:350-353` 注释）、切块也是调速重投的天然单元——然后 `setSpeechRate(rate)`、`playing.set(true)`，逐块排队。每次只投一条 `QUEUE_FLUSH`、utteranceId 为 `"tingsiwei-<idx>"`（`TtsPlayer.kt:237-246`），不积压；`onDone` 推进 `systemNext` 投下一块（`:205-212`），单块 `onError` 当读完继续（`:214-217`），`onStop` 区分两种来路：用户停止（`playing` 已 false，到此为止）/ 朗读中调速（`updateRate` 里 `setSpeechRate` + `stop`，`TtsPlayer.kt:86-96`；`onStop` 回调从当前块重投，`:219-224`）。

**产出什么**：扬声器出声 + `isSpeaking` 的可观测变化（`TtsPlayer.kt:57-59`，唯一真源是 `playing: AtomicBoolean`，系统引擎初始化期与离线模型加载期都算「朗读中」）。本模块不写 Room、不写 DataStore、不落音频文件（PCM 直喂 AudioTrack，无中间文件）。

**影响谁**：详情页思路页的按钮文案与图标（`ttsSpeaking` 状态，`DetailScreen.kt:357`、`:381-383`）与 `ttsMessage` Snackbar（`DetailScreen.kt:99-107`）；设置页试听按钮文案（`previewSpeaking`，`SettingsScreen.kt:359-361`、`:572-584`）与 `message` Snackbar；语速持久化在调用侧完成（`DetailScreen.kt:655`、`SettingsScreen.kt:505` → `SettingsRepository.setTtsRate`，`SettingsRepository.kt:145-147`）；引擎选择持久化同样在调用侧（`SettingsScreen.kt:508-511` → `SettingsRepository.setTtsEngine`，`:149-151`）。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/tts/TtsPlayer.kt` | 375 | 朗读门面：双引擎分发、系统引擎初始化兜底与逐块投喂、离线引擎 AudioTrack 播放、运行中调速、按句切块 `splitForSpeech`、`LocalTtsEngine` 进程级单例 |
| `app/src/main/java/com/tingsiwei/app/tts/SherpaTts.kt` | 51 | kokoro 离线引擎配置封装（`OfflineTtsConfig` 组装 + `VOICE_ID` 常量） |
| `app/src/main/java/com/tingsiwei/app/tts/TtsChunkCallback.java` | 27 | JNI 签名精确匹配的合成回调（Java 实现，Kotlin lambda 会 abort） |
| `app/src/test/java/com/tingsiwei/app/TtsSplitTest.kt` | 46 | `splitForSpeech` 4 例：短文不分块、句末切块不超上限、无句读硬切不丢字、小块切分 |

## 对外接口 / 被谁调用

- `TtsPlayer(context, onMessage: ((String) -> Unit)?)`（`TtsPlayer.kt:27-30`）：全工程两个构造点——`DetailScreen.kt:472`（朗读思路）与 `SettingsScreen.kt:570`（试听），都传了 `onMessage`。
- `speak(text: String, rate: Float, engine: String)`（`TtsPlayer.kt:64`）← `DetailScreen.kt:643`、`SettingsScreen.kt:579`。
- `updateRate(rate: Float)`（`TtsPlayer.kt:86-96`）← `DetailScreen.kt:654`、`SettingsScreen.kt:504`（朗读中拖语速条即时生效）。
- `stop()`（`TtsPlayer.kt:98-107`）← `DetailScreen.kt:639`、`SettingsScreen.kt:574`。
- `shutdown()`（`TtsPlayer.kt:109-118`）← 仅 `DetailScreen.kt:662`（`onCleared`）。设置页的 `previewTts` 没人调 shutdown（见坑 6）。
- `val isSpeaking`（`TtsPlayer.kt:59`）← `DetailScreen.kt:638`、`:645`；`SettingsScreen.kt:581`。
- `var onStateChange: (() -> Unit)?`（`TtsPlayer.kt:61`）：模块内 9 处 invoke（`:106`、`:175`、`:185`、`:198`、`:233`、`:241`、`:252`、`:311`、`:325`），全工程仍无赋值点——两个宿主都改用轮询。
- `LocalTtsEngine.obtain(context)`（`TtsPlayer.kt:337-340`）仅 `TtsPlayer.kt:257` 使用；`release()`（`:342-347`）生产代码零调用 → 引擎常驻到进程结束。
- `internal fun splitForSpeech(text, maxChars = 3000)`（`TtsPlayer.kt:354-375`）：顶层函数，`TtsSplitTest` 直接测；生产两处调用都传 120。
- `SherpaTts(modelDir, numThreads = 2)` 仅被 `LocalTtsEngine.obtain` 构造（`TtsPlayer.kt:339`）。

## 依赖的上游模块

- **`transcribe/ModelManager`**：`isTtsReady`（`ModelManager.kt:539-542`）与 `ttsDir`（`:531`，目录 `filesDir/tts/kokoro-v1.1-zh`）——离线引擎能不能用、模型放哪，都由模型下载模块定。
- **`data/SettingsRepository` 的 `TtsEngine` 常量**（`SettingsRepository.kt:24-28`）：engine 是调用方读好传入的字符串，本模块不读设置（语速同理）。
- **sherpa-onnx AAR**：`app/libs/sherpa-onnx-1.13.8.aar`（`build.gradle.kts:65` 以 fileTree 引入），`com.k2fsa.sherpa.onnx.OfflineTts` 系列类（`SherpaTts.kt:3-6`）。
- **Android 框架**：`TextToSpeech` + `UtteranceProgressListener`（`TtsPlayer.kt:7-8`）、`AudioTrack/AudioAttributes/AudioFormat`（`:4-6`）、`Manifest <queries>` TTS_SERVICE（`AndroidManifest.xml:10-16`）。
- **kotlinx.coroutines**：自建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（`TtsPlayer.kt:33`），合成与投喂都在这上面。
- 反向被依赖：`ui/DetailScreen.kt:73`、`ui/SettingsScreen.kt:59` 两处 import，除此之外无消费者。

## 关键数据结构

| 结构 | 位置 | 说明 |
| --- | --- | --- |
| `playing: AtomicBoolean` | `TtsPlayer.kt:57` | 「正在朗读」唯一真源，覆盖系统引擎初始化与离线模型加载期；`isSpeaking` 直接代理它 |
| `systemChunks` / `systemNext` | `TtsPlayer.kt:42-46` | 系统引擎的切块队列与投喂游标（均 `@Volatile`，回调在子线程推进） |
| `pendingSystemText: Pair<String, Float>?` | `TtsPlayer.kt:39` | 初始化期间的单槽暂存（文本+语速），回调冲掉（`:174`） |
| `liveRate: Float` | `TtsPlayer.kt:53-54` | `@Volatile` 跨线程语速；`speak`/`updateRate` 写，离线引擎每句合成前读 |
| `LocalTtsEngine.instance` | `TtsPlayer.kt:335` | 进程级 kokoro 单例（模型加载要几秒，加载一次常驻） |
| utteranceId 格式 | `TtsPlayer.kt:245` | `"tingsiwei-<idx>"`，`onDone` 用 `removePrefix("tingsiwei-").toIntOrNull()` 解析回块号（`:208`） |
| `splitForSpeech` 块规则 | `TtsPlayer.kt:354-375` | 句末字符集 `。！？；!?;。\n` 边界切分、每块 ≤ maxChars、窗口内无句读就硬切；系统/离线都传 120 |
| `SherpaTts.VOICE_ID = 3` | `SherpaTts.kt:45` | 默认音色：中文女声 zf_001（0-2 是英文女声） |

## 已知坑与约束

1. **旧「语速不生效」坑已在 v1.5.0 修复**：旧版只在未就绪分支存 rate、就绪后丢弃。现在 `speak()` 开头就 `liveRate = rate`（`TtsPlayer.kt:66`），系统引擎 `speakSystem` 里 `setSpeechRate`（`:231`），离线引擎每句读 `liveRate`（`:306`）；朗读中还能 `updateRate`（系统引擎停当前句重投 `:92-93`，离线下一句生效）。索引 README「已知缺陷」第 1 条（`TtsPlayer.kt:29-36` 语速被丢弃）描述的旧代码已不存在。
2. **`isSpeaking` 轮询有竞态（静态推断，未实测）**：`DetailScreen.kt:644` 先把 `ttsSpeaking` 置 true，随后 `while (tts.isSpeaking) delay(300)`（`:645`）。系统引擎走「创建中」路径时，`playing` 要等初始化回调里的 `speakSystem`（`TtsPlayer.kt:232`）才置 true；若引擎初始化慢于首轮检查，循环立刻退出、按钮翻回「朗读思路」，之后声音才开始出。设置页 `togglePreview`（`SettingsScreen.kt:580-582`）同构。离线路径 `playing` 在进入合成前就置 true（`TtsPlayer.kt:251`），无此问题。
3. **无音频焦点**：全工程 grep 无 `AudioManager`/`AudioFocusRequest`；朗读（AudioTrack `USAGE_MEDIA`，`TtsPlayer.kt:269`）与详情页 `MediaPlayer` 回放（`DetailScreen.kt:465-469`）、录音链路互不知情，可同时出声。
4. **`onStateChange` 仍是预留未接线的回调**（9 处 invoke、0 处赋值），两条状态通知机制并存但只接了轮询那条。
5. **系统引擎 6 秒初始化超时是兜底不是调优值**（`TtsPlayer.kt:177-187`）：注释写明裸模拟器无引擎时 `TextToSpeech` 回调可能永不回来；6 秒内真机上慢引擎会被误杀为失败（未实测）。
6. **设置页 `previewTts` 无人 shutdown**：`SettingsViewModel` 没有 `onCleared` 覆写，`previewTts.shutdown()` 零调用点。退出设置页时 `viewModelScope` 取消 `togglePreview` 协程，但 `TtsPlayer` 内部自建 scope 里的合成 job 与 `systemTts` 不受影响——试听中直接退出，声音是否放完、引擎是否滞留，属静态推断（本地引擎常驻是设计使然，`TtsPlayer.kt:331` 注释），未实测。
7. **单块合成失败不报错**：系统引擎 `onError` 直接转 `onDone` 继续后面的块（`TtsPlayer.kt:214-217`），用户听不出少读了一句；离线引擎异常倒是会 `onMessage` 报「离线朗读失败：…」（`:312`）。
8. **系统引擎逐块 `QUEUE_FLUSH` 意味着没有排队能力**（`:245`）；调速重投依赖引擎回调 `onStop`（`:219-224`），若引擎不回调则当前块之后不再推进（依赖引擎行为，未实测）。
9. **离线首句等待**：kokoro 模型第一次加载要几秒（`TtsPlayer.kt:331` 注释），加载期按钮已显示「停止」（`playing` 已 true）但还没出声；之后常驻，第二次起秒出。
10. **`TtsChunkCallback` 的签名约束**：回调必须是 Java 类（`TtsChunkCallback.java:5-9`），以后改 `Handler` 签名时 Kotlin 侧 lambda 仍不可直接替代，改完要真机验证首个分片不 abort。
11. **系统引擎语言设置仍不校验结果**：`systemTts?.language = Locale.CHINA`（`TtsPlayer.kt:168`）不接返回值，设备缺中文语音数据时无提示（继承自旧版）。
12. **索引 README 对照（已回收）**：旧登记称 README 只写系统单引擎、未列 `SherpaTts.kt`/`TtsChunkCallback.java`/`TtsSplitTest.kt`——2026-10-04 README 第 31 行已改为「双引擎朗读（系统 TextToSpeech + sherpa-onnx kokoro，未下载自动回落系统）」并列出 3 个文件，登记作废。README 行归 P-A2 组维护，此处仅对照。

## 最后更新

2026-10-04 · 独立评分（G2）返修：坑 12 的「README 未列双引擎」登记作废（README 第 31 行已改为双引擎三文件，见 README 10-04 条目）；`onMessage` 写入点表述收紧——回调在 `DetailScreen.kt:472` 的 `by lazy` 构造里，`:476` 是 `ttsMessage` 字段声明行。其余约 50 处引用（双引擎全链、调速链、`onStateChange` 计数、README 对照）经抽验全部命中。

2026-10-03 · 对照 v1.5.0（HEAD 024692a）全面重写：`TtsPlayer.kt` 从 62 行单系统引擎重写为 375 行双引擎门面（新增 kokoro 离线链路 `speakLocal`、`updateRate` 运行中调速、系统引擎 6 秒初始化兜底与失败重建、`splitForSpeech` 按句切块排队、`LocalTtsEngine` 常驻单例），新增 `SherpaTts.kt`（51 行）、`TtsChunkCallback.java`（27 行，JNI 签名适配）、`TtsSplitTest.kt`（4 例）；旧「语速不生效」「引擎可见性无 `<queries>`」两坑已随 v1.5.0 修复；README 索引行冲突 1 处已登记待 P-A2 处理。全文行号为本次逐一 Read 核对值。

2026-09-20 · 补写模块总览（首次成文，描述的是单系统引擎旧版）
