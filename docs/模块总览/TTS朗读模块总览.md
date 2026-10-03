# TTS朗读模块总览

## 模块职责

用系统 `TextToSpeech` 把笔记的「记忆思路」整篇朗读出来，提供 speak / stop / shutdown 三个动作和一个可外部轮询的说话状态，帮用户靠听觉复习。

## 运作流程

**从哪来**：详情页「思路」标签底部那颗按钮（`DetailScreen.kt:368-372`）→ `DetailViewModel.toggleTts()`（`DetailScreen.kt:650-664`）→ 从库里读 `n.thinking`（`DetailScreen.kt:652-653`）→ `tts.speak(thinking, ttsRate.value)`（`DetailScreen.kt:658`）。`TtsPlayer` 实例是宿主 VM 的 `by lazy`，首次点击才构造（`DetailScreen.kt:459`）。

**怎么处理**：构造时向系统申请引擎并注册初始化回调（`TtsPlayer.kt:17-27`）——回调里把 `ready` 置位、设语言 `Locale.CHINA`、冲掉初始化期间攒下的 `pendingText`、再广播一次状态（`TtsPlayer.kt:19-25`）。`speak(text, rate)` 分两条路：引擎还没就绪就把文本和语速暂存进 `pendingText`/`rate` 直接返回（`TtsPlayer.kt:29-36`）；已就绪就进 `speakInternal`（`TtsPlayer.kt:40-46`）——`setSpeechRate(rate)` → `stop()` → `speak(text, QUEUE_FLUSH, null, "tingsiwei-thought")` → 通知状态变化。再次 `speak` 因为是 `QUEUE_FLUSH`（`TtsPlayer.kt:44`），总是打断前一句、只保留最新一条。

**产出什么**：扬声器出声 + `isSpeaking` 的可观测变化（`TtsPlayer.kt:15`，直接代理 `tts?.isSpeaking`）。本模块不写 Room、不写 DataStore、不落文件。

**影响谁**：只影响思路页的按钮文案与停止/播放切换——而且是宿主自己 300ms 轮询 `tts.isSpeaking` 得出的（`DetailScreen.kt:660-662`），因为本模块预留的状态回调 `onStateChange`（`TtsPlayer.kt:13`）在全工程没有任何赋值点。生命周期终点是宿主 VM 的 `onCleared()` 调 `shutdown()`（`DetailScreen.kt:675`；`TtsPlayer.kt:53-61`）。

## 入口文件清单

- `app/src/main/java/com/tingsiwei/app/tts/TtsPlayer.kt` — 62 行 — 本模块唯一文件，只有一个 `class TtsPlayer(context: Context)`（`TtsPlayer.kt:8-62`）：初始化与 `pendingText` 冲刷（`TtsPlayer.kt:17-27`）、`speak`（`TtsPlayer.kt:29-36`）、`rate` 字段（`TtsPlayer.kt:38`）、`speakInternal`（`TtsPlayer.kt:40-46`）、`stop`（`TtsPlayer.kt:48-51`）、`shutdown`（`TtsPlayer.kt:53-61`）。模块不足百行，接口与实现全在一处。

## 对外接口 / 被谁调用

- `TtsPlayer(context)`（`TtsPlayer.kt:8`）：唯一的构造点 `private val tts by lazy { TtsPlayer(App.get()) }`（`DetailScreen.kt:459`）。全工程 grep 无第二个引用。
- `speak(text: String, rate: Float)`（`TtsPlayer.kt:29`）← `DetailScreen.kt:658`。
- `stop()`（`TtsPlayer.kt:48`）← `DetailScreen.kt:655`。
- `shutdown()`（`TtsPlayer.kt:53`）← `DetailScreen.kt:675`（`onCleared`）。
- `val isSpeaking: Boolean`（`TtsPlayer.kt:15`）← `DetailScreen.kt:654`、`660`。
- `var onStateChange: (() -> Unit)?`（`TtsPlayer.kt:13`）：**当前无人订阅**，本模块内部在 `TtsPlayer.kt:25`、`45`、`50` 三处 invoke。

## 依赖的上游模块

- Android 框架：`android.speech.tts.TextToSpeech`、`java.util.Locale`（`TtsPlayer.kt:4-5`），依赖设备上安装的 TTS 引擎与中文语音数据。
- `Context`：由宿主传 `App.get()`（`DetailScreen.kt:459`；`App.kt:11-14`）——本模块拿的是 Application 级上下文，所以 `shutdown()` 之后没有第二个持有者会再构造它。
- 数据/设置模块**不被本模块直接依赖**：文本与语速都是调用方读好后传参进来的（`DetailScreen.kt:652-658`，语速取自 `AppSettings.ttsRate`，`SettingsRepository.kt:30`、`60`）。
- 反向被依赖：`ui/DetailScreen.kt`（`DetailScreen.kt:73` 引入）。除此之外零依赖者。

## 关键数据结构

无独立数据结构，复用上游：待读文本 = `NoteEntity.thinking`（`Entities.kt:38-39`，由调用方读出后传入，`DetailScreen.kt:653`）；语速 = `AppSettings.ttsRate`（`SettingsRepository.kt:30`）。

本模块自有的全部状态就 4 个字段（`TtsPlayer.kt:10-13`、`38`）：
- `tts: TextToSpeech?`——引擎句柄，`shutdown` 后置 null（`TtsPlayer.kt:59`）。
- `ready: Boolean`——初始化回调结果（`TtsPlayer.kt:11`、`19`）。
- `pendingText: String?`——初始化未完成时的**单槽**暂存（`TtsPlayer.kt:12`、`31-32`、`22-23`）。
- `rate: Float`——私有语速字段，默认 `1.0f`（`TtsPlayer.kt:38`），实际只在未就绪分支被赋值（`TtsPlayer.kt:32`）。
外加一个函数槽 `onStateChange`（`TtsPlayer.kt:13`）。utteranceId 是常量字符串 `"tingsiwei-thought"`（`TtsPlayer.kt:44`）。

## 已知坑与约束

1. **传入的 `rate` 在引擎就绪后被忽略**：`speak(text, rate)` 只在「未就绪」分支写 `this.rate = rate`（`TtsPlayer.kt:29-36`），已就绪分支直接 `speakInternal(text)`，而 `speakInternal` 用的是字段 `rate`（`TtsPlayer.kt:42`），字段默认 `1.0f`（`TtsPlayer.kt:38`）。后果：引擎已就绪（多数情况）时第一次朗读用的还是 1.0x，用户调的倍速基本不生效。
2. **索引 README 的说法需要修正**：README 第 23 行「TTS 朗读 | `tts/TtsPlayer.kt` | 系统 TextToSpeech 朗读思路，语速 0.5~2.0x」——`0.5~2.0` 这个区间在本模块里**不存在任何钳制代码**（`TtsPlayer.kt:29-46` 全文没有 `coerceIn`），它是 UI 侧 Slider 的 `valueRange`（`DetailScreen.kt:378`、`SettingsScreen.kt:272`）。也就是说直接调 `TtsPlayer.speak(text, 5f)` 不会被挡。README 也完全没提到本模块只有一个消费者（`DetailScreen.kt:459`）。
3. **语言设置不校验结果**：`tts?.language = Locale.CHINA`（`TtsPlayer.kt:21`）没接返回值，设备缺中文语音数据时 `setLanguage` 会失败但代码继续走，表现为「能用但念不出中文/念成英文」，界面无任何提示。
4. **没有音频焦点，也没有和录音回放协调**：`speak` 的 params 传 null（`TtsPlayer.kt:44`），全模块无 `AudioManager`/`OnAudioFocusChangeListener`；详情页的 `MediaPlayer` 播放链路（`DetailScreen.kt:595-646`）与朗读链路彼此完全不知情，可以同时出声。
5. **没有完成回调，只能轮询**：`speak` 传的是 `null` Bundle（`TtsPlayer.kt:44`），没有 `UtteranceProgressListener`，所以「说完」这件事只能由外部 `while (tts.isSpeaking) delay(300)` 猜（`DetailScreen.kt:660`）。而 `isSpeaking` 在引擎刚提交文本的瞬间可能仍为 false（`TtsPlayer.kt:15`），因此详情页有概率刚点下就把按钮翻回「朗读思路」。
6. **`onStateChange` 是预留但闲置的单槽回调**：`var onStateChange: (() -> Unit)?`（`TtsPlayer.kt:13`），三处 invoke（`TtsPlayer.kt:25`、`45`、`50`），全工程无赋值点。宿主改用了轮询（`DetailScreen.kt:656`、`659`、`661-662` 手工维护 `ttsSpeaking`）→ 两条通知机制并存但只接了一条。
7. **未就绪期间的重复点击会互相覆盖**：`pendingText` 只有一个槽（`TtsPlayer.kt:12`、`31`），连点两次只播最后一次；`rate` 同样只留最后一次（`TtsPlayer.kt:32`）。
8. **`shutdown()` 后实例进入永久哑火**：`shutdown` 把 `tts = null; ready = false`（`TtsPlayer.kt:59-60`），而初始化只在 `init` 跑一次（`TtsPlayer.kt:17-27`）；此后 `speak` 会走进未就绪分支把文本塞进 `pendingText` 再没有回调来冲（`TtsPlayer.kt:29-35`），静默无输出。目前不成问题，因为唯一的 `shutdown()` 调用点在 `onCleared()`（`DetailScreen.kt:675`）——VM 也随之销毁。
9. **整篇一次投递、无分句无队列**：`thinking` 全文当一条 utterance 交给引擎（`TtsPlayer.kt:44`），长文本的实际截断行为取决于设备引擎，本模块不做分句；`QUEUE_FLUSH` 也意味着没有「排队继续读」的能力。
10. **停止不是幂等地改状态**：`stop()` 只调 `tts?.stop()` 并广播（`TtsPlayer.kt:48-51`），不会通知宿主；宿主的 `ttsSpeaking` 靠自己那条分支置 false（`DetailScreen.kt:655-656`）。
11. **与设置页/详情页共享同一份语速偏好**：写入方有两处（`DetailScreen.kt:668`、`SettingsScreen.kt:398`，都落到 `SettingsRepository.kt:93-95`），但 `TtsPlayer` 从不回读，只吃参数——所以任何一侧改了语速，正在播的都不受影响（结合坑 1，等于两侧都可能不生效）。
12. **权限面**：朗读不需要运行时权限，Manifest 里也没有 TTS 相关声明或 `<queries>`（`AndroidManifest.xml:4-8` 只有 INTERNET/RECORD_AUDIO/FOREGROUND_SERVICE(_MICROPHONE)/POST_NOTIFICATIONS）；`targetSdk 35`（`app/build.gradle.kts:16`）下这是否会影响引擎可见性未实测（见疑点）。

## 最后更新

2026-09-20 · 补写模块总览（首次成文）
