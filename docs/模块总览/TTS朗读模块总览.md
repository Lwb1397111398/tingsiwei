# TTS朗读模块总览

## 模块职责

把笔记的「记忆思路」朗读出来：`TtsPlayer` 按设置在「系统 TextToSpeech」「sherpa-onnx kokoro 离线模型」「云端大模型朗读（默认硅基流动 CosyVoice2）」三个引擎之间分发，长文按句切块合成出声，朗读中可随时调速，引擎缺东西时明说提示、绝不静默失败；在线朗读失败还自动退回离线/系统引擎把这次读完。

## 运作流程

**从哪来**：两个入口。

1. 详情页「思路」标签底部按钮（`ui/DetailScreen.kt`）→ `DetailViewModel.toggleTts()`（`DetailScreen.kt:629-634`）从 Room 读 `n.thinking` → `toggleTtsText(thinking)`（`DetailScreen.kt:638-651`）：正在读就 `tts.stop()`，否则读 `settingsRepo.current()`，引擎是 cloud 时组 `CloudTtsConfig(s.ttsCloudUrl, s.ttsCloudKey, s.ttsCloudVoice)`（`DetailScreen.kt:645-647`）→ `tts.speak(text, ttsRate.value, s.ttsEngine, cloud)`（`:648`）→ `ttsSpeaking` 置 true、`while (tts.isSpeaking) delay(300)` 轮询。`TtsPlayer` 实例是 VM 的 `by lazy`，构造传入的 `onMessage` 回调写入 `ttsMessage`，UI 侧 `LaunchedEffect` 弹 Snackbar。
2. 设置页「朗读」卡片「试听当前引擎」按钮（`ui/SettingsScreen.kt:711`）→ `togglePreview()`（`SettingsScreen.kt:712-726`）用固定句子调 `previewTts.speak(..., ttsEngine.value, cloudConfig())`（`:723`，`cloudConfig` 在 `:732-736` 从设置页当前填写值组装）；`previewTts` 也是 `by lazy`，`onMessage` 写入设置页自己的 `message` Snackbar 通道。

**怎么处理**：`TtsPlayer.speak(text, rate, engine, cloud = null)`（`tts/TtsPlayer.kt:64-95`）第一句先 `stop()` 清场、`liveRate = rate`，再按 `engine` 字符串分发（常量取 `data/SettingsRepository.kt:25-29` 的 `TtsEngine.SYSTEM="system"` / `LOCAL="local"` / `CLOUD="cloud"`）：

- **在线引擎分支**（`TtsPlayer.kt:68-75`）：配置为空或 Key 空白 → 提示「还没配置在线朗读：到设置页「朗读」里填语音服务的 API Key」；否则 `speakCloud(text, cfg)`（`:351-433`）。
  - `speakCloud` 与 `speakLocal` 同构：`playing.set(true)` 后建 `AudioTrack`（PCM 16bit 单声道、采样率 `CloudTts.SAMPLE_RATE=24000`，**缓冲约 10 秒**（`sampleRate*2*10`，`:375`）——一句写完时缓冲里还压着没播的量，足够盖住下一句的网络往返，句间不出缝），然后逐句循环：`splitForSpeech(text, maxChars=120)` 切块（`:391`），每句 `client.streamPcm(chunk, liveRate) { pcm -> ... }`（`:393-399`）回调里把 PCM16 直接 `AudioTrack.write(..., WRITE_BLOCKING)`——写多快播多快；回调返回 `playing.get()`，停止时返回 false 让客户端立刻断流。每句请求前读一次 `liveRate`，朗读中拖调速条下一句生效。
  - 收尾同离线：等 `playbackHeadPosition` 追上 `totalFrames`（`:404`）。停止路径 `stop()`（`:105-120`）除了置 `playing=false`、取消 job，还会 `track?.pause()+flush()`（`:110-114`）立刻掐声——在线路径缓冲了 10 秒音频，不主动掐会继续出声好一会儿。
  - **失败回落**（`:417-428`）：`CloudTtsException` 等异常不终止朗读——优先退离线引擎（`ModelManager.isTtsReady` → `speakLocal`），没有就退系统语音（`speakSystemOrQueue`），都没有才罢休；`onMessage` 一次性说明「在线朗读失败：<原因>，本次先用离线/系统语音」。`stop()` 撞上收尾轮询的 `CancellationException` 属正常停止，静默返回（`:399-401` 离线分支同样处理）。
  - `CloudTts`（`tts/CloudTts.kt`，182 行）：默认硅基流动 `FunAudioLLM/CosyVoice2-0.5B`（`DEFAULT_MODEL :26`），OpenAI `/audio/speech` 兼容格式。`endpoint()`（`:54-61`）规范化用户手填地址（补协议、补 `/v1/audio/speech`，与 LLM 的 `LlmEndpoint` 同规则）；`streamPcm(text, speed, onPcm)`（`:67-125`）发 `response_format=pcm + sample_rate=24000 + stream=true` 的 POST，响应是 HTTP chunked 的 16bit 小端 PCM，按 8KB 块读入、奇数字节进位拼接成 ShortArray 回调；错误映射成人话（401/403 → 查 Key、429 → 限流、4xx → 尽量抽出服务端 `message`）。8 种预置音色 `VOICES`（`:32-39`，anna/bella/claire/diana/alex/benjamin/charles/david）+ `voiceId/voiceLabel` 互转（`:43-47`）。HTTP 接缝 `CloudTtsTransport`（`:135-148`）：生产 `OkHttpCloudTtsTransport`（`:140-167`，成功路径不 close 流，整段朗读期间慢慢读）、单测注入 `FakeCloudTtsStream`。
- **离线引擎分支**（`TtsPlayer.kt:76-84`）：先 `ModelManager.isTtsReady(appCtx)` 判 kokoro 全部 373 个文件是否就绪。就绪 → `speakLocal(text)`（`:263-345`）；未就绪 → 退回系统引擎并提示「离线朗读模型还没下载，这次先用系统语音；可在设置里下载（约 215MB）」。
  - `speakLocal`：`LocalTtsEngine.obtain(appCtx)` 拿进程级单例（`LocalTtsEngine` 在 `TtsPlayer.kt:435-453`，模型加载一次常驻复用），建 `AudioTrack`（采样率取自引擎、缓冲 `maxOf(minBuf*4, sampleRate*2*5)` 约 5 秒），逐句 `engine.tts.generateWithCallback(chunk, SherpaTts.VOICE_ID, liveRate, TtsChunkCallback(chunkHandler))`（`:319`）。回调把 float 采样转 PCM16 阻塞写入；停止时回调返回 false 让 native 合成停下。约 5 秒的缓冲让合成自然跑在播放前面（领先量 ≈1~2 句）：逐句循环本就背靠背合成、不等上一句播完，领先量盖住每句开头的启动耗时（jieba 分词 + 模型首趟推理），句间不出缝——等价于「边播当前句边预取后面几句」，与在线路径「靠大缓冲吸收句间空档」同一套路。
  - `SherpaTts`（`tts/SherpaTts.kt:14-45`）是 kokoro-int8-multi-lang-v1_1 配置封装：lexicon 三件套 + jieba 词典 + espeak-ng-data + ruleFsts（phone/date/number-zh.fst），`maxNumSentences=1`、`silenceScale=0.5f`（压掉 kokoro 句读处的长停顿），默认音色 `VOICE_ID=3`（中文女声 zf_001）。
  - 回调必须用 Java 类 `TtsChunkCallback`（`tts/TtsChunkCallback.java`）：sherpa-onnx 的 JNI 用硬编码签名反查回调方法，Kotlin 2.x lambda 没有桥接方法，合成首个分片会 abort。
- **系统引擎分支**（`TtsPlayer.kt:85-89`）：`speakSystemOrQueue` 失败才提示「本机没有可用的系统朗读引擎…」。
  - 同步预检 `hasSystemEngine()`（`:136-140`，`packageManager.queryIntentServices(TTS_SERVICE)`，Android 11+ 靠 Manifest `<queries>`）；四态分发（`:144-173`）：未创建/初始化中 → 暂存 `pendingSystemText`，就绪 → 直接读，上次失败 → 重建再试。
  - `createSystem`（`:175-201`）：成功设 `Locale.CHINA`、挂 `UtteranceProgressListener`、冲掉暂存；裸模拟器无引擎时回调永不回来，6 秒超时当失败。
  - `speakSystem`（`:240-248`）把全文 `splitForSpeech(maxChars=120)` 切块，逐块 `QUEUE_FLUSH` 投喂（utteranceId `"tingsiwei-<idx>"`），`onDone` 推进下一块、单块 `onError` 当读完、`onStop` 区分用户停止与调速重投。

**产出什么**：扬声器出声 + `isSpeaking` 的可观测变化（`TtsPlayer.kt:59`，唯一真源是 `playing: AtomicBoolean`）。本模块不写 Room、不写 DataStore、不落音频文件（PCM 直喂 AudioTrack）。在线朗读的配置（地址/Key/音色）由调用方从设置读好传入，本模块不读设置。

**影响谁**：详情页按钮文案与图标（`ttsSpeaking`）与 `ttsMessage` Snackbar；设置页试听按钮文案与 Snackbar；语速/引擎/在线配置的持久化都在调用侧完成（`SettingsRepository.setTtsRate/setTtsEngine/setTtsCloud`）。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/tts/TtsPlayer.kt` | 480 | 朗读门面：三引擎分发、在线引擎流式播放与失败回落、系统引擎初始化兜底与逐块投喂、离线引擎 AudioTrack 播放（约 5 秒缓冲领先量）、运行中调速、按句切块 `splitForSpeech`、`LocalTtsEngine` 进程级单例 |
| `app/src/main/java/com/tingsiwei/app/tts/CloudTts.kt` | 182 | 在线朗读客户端：硅基流动 CosyVoice2 请求组装、地址规范化、chunked PCM 跨块解码、错误映射成人话、8 预置音色、HTTP 接缝与 OkHttp 实现 |
| `app/src/main/java/com/tingsiwei/app/tts/SherpaTts.kt` | 51 | kokoro 离线引擎配置封装（`OfflineTtsConfig` 组装 + `VOICE_ID` 常量） |
| `app/src/main/java/com/tingsiwei/app/tts/TtsChunkCallback.java` | 27 | JNI 签名精确匹配的合成回调（Java 实现，Kotlin lambda 会 abort） |
| `app/src/test/java/com/tingsiwei/app/CloudTtsTest.kt` | 136 | `CloudTts` 7 例：地址规范化、请求体字段与语速钳制、PCM 小端跨块解码（奇字节进位）、回调 false 即停、无 Key 报错、401/429/400 错误映射、音色清单互查 |
| `app/src/test/java/com/tingsiwei/app/TtsSplitTest.kt` | 46 | `splitForSpeech` 4 例：短文不分块、句末切块不超上限、无句读硬切不丢字、小块切分 |

## 对外接口 / 被谁调用

- `TtsPlayer(context, onMessage)`（`TtsPlayer.kt:30-32`）：全工程两个构造点——`DetailScreen.kt`（朗读思路）与 `SettingsScreen.kt`（试听），都传了 `onMessage`。
- `speak(text, rate, engine, cloud = null)`（`TtsPlayer.kt:64`）← `DetailScreen.kt:648`（cloud 引擎时传 `CloudTtsConfig`）、`SettingsScreen.kt:719-724`（试听，`cloudConfig()` 组装）。
- `updateRate(rate)`（`TtsPlayer.kt:93`）← 详情页与设置页朗读中拖语速条；对在线引擎无额外动作——`liveRate` 每句请求前重读，下一句自然生效。
- `stop()`（`TtsPlayer.kt:105`）← 两个调用点；新增 `track?.pause()+flush()` 立刻掐声。
- `shutdown()`（`TtsPlayer.kt:122`）← 仅详情页 `onCleared`。
- `val isSpeaking`（`:59`）、`var onStateChange`（`:61`，仍无赋值点，宿主都用轮询）。
- `LocalTtsEngine.obtain/release`（`:435-453`）、`internal fun splitForSpeech`（`:456-476`，`TtsSplitTest` 直接测；生产三处调用都传 120）。
- `CloudTts(cfg)` / `streamPcm`（`CloudTts.kt:20`、`:67`）仅 `TtsPlayer.speakCloud` 使用；`CloudTts.VOICES/voiceId/voiceLabel` 另被设置页 UI 用（音色下拉）。

## 依赖的上游模块

- **`data/SettingsRepository`**：`TtsEngine` 三常量（`SettingsRepository.kt:25-29`）；`TtsCloudDefaults`（`:31-35`，BASE_URL/MODEL/VOICE 默认值住数据层）；engine/语速/云端配置由调用方读好传入。
- **`transcribe/ModelManager`**：`isTtsReady` 与 `ttsDir`——离线引擎能不能用、模型放哪；也是在线失败回落的第一顺位。
- **sherpa-onnx AAR**（`app/libs/`）：离线引擎；**OkHttp**（与 LLM 层同依赖）：在线引擎 HTTP；**kotlinx.serialization**：请求 JSON。
- **Android 框架**：`TextToSpeech` 系（系统引擎）、`AudioTrack` 系（离线+在线）、Manifest `<queries>`。
- 反向被依赖：`ui/DetailScreen.kt`、`ui/SettingsScreen.kt` 两处 import（后者还用 `CloudTts.voiceLabel/VOICES`）。

## 关键数据结构

| 结构 | 位置 | 说明 |
| --- | --- | --- |
| `playing: AtomicBoolean` | `TtsPlayer.kt:57` | 「正在朗读」唯一真源；`isSpeaking` 直接代理它 |
| `liveRate: Float` | `TtsPlayer.kt:53` | `@Volatile` 跨线程语速；三引擎都每句重读，调速下一句生效 |
| `track: AudioTrack?` | `TtsPlayer.kt:50` | 当前播放轨道，`stop()` 用来 `pause+flush` 立刻掐声（在线路径缓冲大，必须掐） |
| `systemChunks` / `systemNext` / `pendingSystemText` | `TtsPlayer.kt:42-48` | 系统引擎切块队列、游标与初始化期暂存 |
| `LocalTtsEngine.instance` | `TtsPlayer.kt:437` | 进程级 kokoro 单例 |
| `CloudTtsConfig(baseUrl, apiKey, voice, model)` | `CloudTts.kt:127-133` | 在线朗读配置；调用方从设置组装后传入 |
| `CloudTts.VOICES` | `CloudTts.kt:32-39` | 硅基流动 8 预置音色（接口后缀名 ↔ 中文标签）；存储值为 `模型名:音色名` 全串 |
| chunked PCM 解码 | `CloudTts.kt:95-120` | 8KB 块读入 + 奇数字节 `carry` 进位 + 小端拼 ShortArray |
| `splitForSpeech` 块规则 | `TtsPlayer.kt:456-476` | 句末边界切分、每块 ≤120 字；三引擎统一 120 |

## 已知坑与约束

1. **在线朗读必须有网、必须填 Key**：没填 Key 在发起前就提示；请求失败（断网/Key 错/限流）不空手——自动退回离线/系统引擎读完本次并弹原因（`TtsPlayer.kt:417-428`）。回落选择发生在失败当下，若离线模型没下载则直接落系统语音。
2. **句间连续性靠播放缓冲的领先量，不是并发预取**：两个流式引擎都是逐句串行合成/请求（不并发开第二个任务——离线引擎是 native 单例不能并行，在线路径 10 秒缓冲足够），靠 AudioTrack 大缓冲吸收句间空档。离线路径约 5 秒缓冲（2026-10-24 起）：合成背靠背跑进缓冲，领先量 ≈1~2 句盖住每句启动耗时。边界：①设备算力不足、合成慢于实时播放时领先量攒不起来，句间/句中仍会断，后备手段依次是 `SherpaTts` 构造参数 `numThreads` 2→4、实验调 `maxNumSentences`、改用在线引擎；②全文第一句的冷启动（首趟推理几百毫秒到 1 秒）没有领先量可盖，消不掉；③调速生效最多延后约 5 秒（旧语速已压进缓冲的量，此前是当前句放完即生效）。在线路径网络极慢（>10 秒拿不到下一句首包）时句间会有短暂停顿；语速 2x 时缓冲等效时长减半。
3. **「停」的即时性**：`stop()` 里 `pause+flush` 立刻掐声（`:110-114`）；被停协程可能还挂在 socket read 上（readTimeout 30 秒），最多滞留一个读超时后自行退出，期间不再出声、无副作用。
4. **在线朗读走手机流量**：计费按输入文本 UTF-8 字节数；CosyVoice2 在硅基流动模型页当前标价免费（以官网实时价格为准）。一篇 5000 字笔记约 25 次请求（每次一句 ≤120 字）。
5. **`isSpeaking` 轮询竞态（继承）**：系统引擎走「创建中」路径时 `playing` 要等初始化回调才置 true，首轮检查可能提前退出循环；离线与在线路径 `playing` 在进入合成前就置 true，无此问题。
6. **无音频焦点（继承）**：朗读与详情页录音回放、录音链路互不知情，可同时出声。
7. **`onStateChange` 仍是预留未接线的回调**（多处 invoke、0 处赋值）。
8. **系统引擎 6 秒初始化超时是兜底不是调优值（继承）**；逐块 `QUEUE_FLUSH` 没有排队能力、调速重投依赖引擎回调（继承）。
9. **单块合成失败不报错（系统引擎，继承）**；离线与在线引擎异常都会 `onMessage` 报错（在线还会回落）。
10. **`TtsChunkCallback` 的签名约束（继承）**：回调必须是 Java 类；以后改 `Handler` 签名要真机验证首个分片不 abort。
11. **音色只暴露硅基流动 8 预置**：自定义音色/克隆音色（`speech:…` URI）接口本身支持，但 UI 未开放；`voiceLabel` 对不认识的存储值原样显示，给以后留了口子。
12. **pcm 字节序文档未写明**：服务端实际输出 16bit 小端（业界惯例，单测按小端解码通过、真机待最终确认——若真机出现刺耳杂音，优先怀疑字节序）。

## 最后更新

2026-10-24 · 离线朗读句间隔优化：`speakLocal` 的 AudioTrack 缓冲从 `maxOf(minBuf*4, 16384)`（约 0.34 秒）放大到 `sampleRate*2*5`（约 5 秒）——逐句循环本就背靠背合成，放大缓冲即得「边播当前句边预合成后面几句」的领先量，句间启动耗时被盖住。调速生效最多延后约 5 秒（坑 2）；`stop()` 的 `pause+flush` 照旧立刻掐声。184 个单测全过、`assembleDebug` 成功；句间听感改善待真机试听（UNMEASURED）。

2026-10-24 · 新增在线朗读引擎（第三引擎）：`CloudTts.kt`（182 行，硅基流动 CosyVoice2 流式 PCM）、`TtsPlayer.speakCloud`（失败自动回落离线/系统）、`stop()` 加 `pause+flush` 立刻掐声、`speak` 加第 4 参 `cloud: CloudTtsConfig?`；设置页与详情页调用点改传云端配置；新增 `CloudTtsTest` 7 例（184 个单测全过）。两个调用点的行号按本次重排后重标。

2026-10-04 · 独立评分（G2）返修：坑 12 的「README 未列双引擎」登记作废；`onMessage` 写入点表述收紧。其余约 50 处引用经抽验全部命中。

2026-10-03 · 对照 v1.5.0 全面重写：`TtsPlayer.kt` 从单系统引擎重写为双引擎门面，新增 `SherpaTts.kt`、`TtsChunkCallback.java`、`TtsSplitTest.kt`。

2026-09-20 · 补写模块总览（首次成文，描述的是单系统引擎旧版）
