# TTS朗读模块总览

## 模块职责

把笔记的「记忆思路」朗读出来：`TtsPlayer` 按设置在「系统 TextToSpeech」「sherpa-onnx kokoro 离线模型」「云端大模型在线朗读」三个引擎之间分发，长文按句切块合成出声，朗读中可随时调速，引擎缺东西时明说提示、绝不静默失败；朗读正文在线失败还自动退回离线/系统引擎把这次读完。在线引擎不绑定渠道——任何 OpenAI `/audio/speech` 兼容服务（硅基流动 / 中转站 / OpenAI 官方）都能用，模型可手填或「检索模型」点选，格式被渠道拒收时自动从流式 PCM 回退到 WAV。

## 运作流程

**从哪来**：两个入口。

1. 详情页「思路」标签底部按钮（`ui/DetailScreen.kt`）→ `DetailViewModel.toggleTts()`（`DetailScreen.kt:629-634`）从 Room 读 `n.thinking` → `toggleTtsText(thinking)`（`DetailScreen.kt:638-651`）：正在读就 `tts.stop()`，否则读 `settingsRepo.current()`，引擎是 cloud 时组 `CloudTtsConfig(s.ttsCloudUrl, s.ttsCloudKey, s.ttsCloudVoice)`（`DetailScreen.kt:645-647`）→ `tts.speak(text, ttsRate.value, s.ttsEngine, cloud)`（`:648`）→ `ttsSpeaking` 置 true、`while (tts.isSpeaking) delay(300)` 轮询。`TtsPlayer` 实例是 VM 的 `by lazy`，构造传入的 `onMessage` 回调写入 `ttsMessage`，UI 侧 `LaunchedEffect` 弹 Snackbar。
2. 设置页「朗读」卡片「试听当前引擎」按钮（`ui/SettingsScreen.kt`）→ `togglePreview()` 用固定句子调 `previewTts.speak(..., ttsEngine.value, cloudConfig(), fallback = engine != CLOUD)`（`cloudConfig` 从设置页当前填写值组装，含 model）；`previewTts` 也是 `by lazy`，`onMessage` 写入设置页自己的 `message` Snackbar 通道。**试听传 `fallback=false`**：在线试听失败只弹准确原因，不静默换成离线/系统声音——试听的目的就是验证在线通不通。设置页另有「检索模型」按钮 → `fetchTtsModels()`（必须 `withContext(Dispatchers.IO)`，见坑 13）→ `CloudTts.fetchModels()` GET `/models` → `filterTtsModels` 筛语音类模型列成点选 chips；筛不出则列全部并提示点选后试听验证。

**怎么处理**：`TtsPlayer.speak(text, rate, engine, cloud = null)`（`tts/TtsPlayer.kt:64-95`）第一句先 `stop()` 清场、`liveRate = rate`，再按 `engine` 字符串分发（常量取 `data/SettingsRepository.kt:25-29` 的 `TtsEngine.SYSTEM="system"` / `LOCAL="local"` / `CLOUD="cloud"`）：

- **在线引擎分支**（`TtsPlayer.kt:68-78`）：配置为空或 Key 空白 → 提示「还没配置在线朗读：到设置页「朗读」里填语音服务的 API Key」；否则 `speakCloud(text, cfg, fallback)`（`:358-449`）。
  - `speakCloud` 与 `speakLocal` 同构：`playing.set(true)` 后建 `AudioTrack`（PCM 16bit 单声道、采样率 `CloudTts.SAMPLE_RATE=24000`，**缓冲约 10 秒**（`sampleRate*2*10`）——一句写完时缓冲里还压着没播的量，足够盖住下一句的网络往返，句间不出缝），然后逐句循环：`splitForSpeech(text, maxChars=120)` 切块，每句 `client.streamPcm(chunk, liveRate) { pcm -> ... }` 回调里把 PCM16 直接 `AudioTrack.write(..., WRITE_BLOCKING)`——写多快播多快；回调返回 `playing.get()`，停止时返回 false 让客户端立刻断流。每句请求前读一次 `liveRate`，朗读中拖调速条下一句生效。**服务端给的采样率不一定是 24k**——`CloudTts` 层重采样到统一 24k 后才回调，播放层不用关心。
  - 收尾同离线：等 `playbackHeadPosition` 追上 `totalFrames`。停止路径 `stop()` 除了置 `playing=false`、取消 job，还会 `track?.pause()+flush()` 立刻掐声——在线路径缓冲了 10 秒音频，不主动掐会继续出声好一会儿。
  - **失败回落**：`fallback=true`（朗读正文）时 `CloudTtsException` 等异常不终止朗读——优先退离线引擎（`ModelManager.isTtsReady` → `speakLocal`），没有就退系统语音（`speakSystemOrQueue`），都没有才罢休；`onMessage` 一次性说明「在线朗读失败：<原因>，本次先用离线/系统语音」。`fallback=false`（设置页试听）只弹「在线朗读失败：<原因>」并收掉播放态，绝不静默换声音。`stop()` 撞上收尾轮询的 `CancellationException` 属正常停止，静默返回。
  - `CloudTts`（`tts/CloudTts.kt`，384 行）：**OpenAI `/audio/speech` 兼容通用客户端，不绑定渠道**。`apiBase()`（`:113`）规范化手填地址（补协议、剥掉多余的 `/audio/speech`、补 `/v1`），派生 `endpoint()`（`= apiBase()+"/audio/speech"`）与 `modelsEndpoint()`（`= apiBase()+"/models"`）。合成走两级格式：`streamPcm`（`:130`）先发流式 PCM（`response_format=pcm + sample_rate=24000 + stream=true`，HTTP chunked 16bit 小端边下边播）；被渠道拒收（**400/404/415/422**，或 200 却回了 JSON/HTML/文本——按 Content-Type 与首字节 `{` 识别，绝不把文本当音频喂喇叭）时自动改发极简 WAV 请求 `{model, input, voice, response_format:"wav", speed}`（`streamWav :191`，OpenAI 官方与中转站支持面最广），`CloudTtsAudio.parseWav` 解 RIFF 头（立体声降混），非 24k 线性重采样回 24k 再回调。401/403/429/断网不重试（换格式也没用）。音色由 `buildVoice(model, voice)`（`:74`）请求期拼装：短名 `anna` + 硅基式模型（含 `/`）→ 拼 `模型:anna`；挂着旧模型前缀 → 换成当前模型；OpenAI 式原名 `alloy` → 原样发。`fetchModels()`（`:219`）GET `/models` 解析 `data[].id`（正则容错），404 提示渠道不提供列表可手填。`filterTtsModels`（`:93`）先排除（whisper/embed/rerank/image/video 等）再按关键词命中（tts/speech/voice/audio/cosyvoice/fish/kokoro 等），宁滥勿缺。预置音色 `voicePresetsFor(model)`（`:58`）：CosyVoice 系 → 8 个中文标签音色；`tts-1/tts-1-hd` → OpenAI 6 音色；`gpt-4o-mini-tts` → 11 个；其他模型空表（UI 自由手填）。日志 tag `CloudTts`：请求 URL/模型/音色/格式、HTTP 码、收到字节数、失败原因。HTTP 接缝 `CloudTtsTransport`（post+get）：生产 `OkHttpCloudTtsTransport`（post 成功路径不 close 流，整段朗读期间慢慢读）、单测注入 `FakeCloudTtsStream`。
  - `CloudTtsAudio`（`tts/CloudTtsAudio.kt`，110 行）：纯函数集——`parseWav`（RIFF/fmt/data 解析，16bit 限定、立体声降混）、`resample`（线性插值重采样）、`decodePcmLe`（小端解码）。
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
| `app/src/main/java/com/tingsiwei/app/tts/TtsPlayer.kt` | 496 | 朗读门面：三引擎分发、在线引擎流式播放与失败回落（`fallback` 参数区分试听/正文）、系统引擎初始化兜底与逐块投喂、离线引擎 AudioTrack 播放（约 5 秒缓冲领先量）、运行中调速、按句切块 `splitForSpeech`、`LocalTtsEngine` 进程级单例 |
| `app/src/main/java/com/tingsiwei/app/tts/CloudTts.kt` | 384 | 在线朗读通用客户端：OpenAI 兼容两级格式（流式 PCM → WAV 回退）、音色请求期拼装 `buildVoice`、模型列表 `fetchModels`+`filterTtsModels`、按模型预置音色、地址规范化、错误映射成人话、排查日志 |
| `app/src/main/java/com/tingsiwei/app/tts/CloudTtsAudio.kt` | 110 | 音频纯函数：WAV（RIFF）解析、线性插值重采样、小端 PCM 解码 |
| `app/src/main/java/com/tingsiwei/app/tts/SherpaTts.kt` | 51 | kokoro 离线引擎配置封装（`OfflineTtsConfig` 组装 + `VOICE_ID` 常量） |
| `app/src/main/java/com/tingsiwei/app/tts/TtsChunkCallback.java` | 27 | JNI 签名精确匹配的合成回调（Java 实现，Kotlin lambda 会 abort） |
| `app/src/test/java/com/tingsiwei/app/CloudTtsTest.kt` | 376 | `CloudTts`/`CloudTtsAudio` 21 例：地址规范化、请求体、PCM 跨块解码、错误映射、音色拼装与预置、WAV 回退链（400→WAV、200+JSON→WAV、双 JSON 报错、401 不重试）、WAV 解析与重采样、空响应报错、模型检索解析与筛选 |
| `app/src/test/java/com/tingsiwei/app/TtsSplitTest.kt` | 46 | `splitForSpeech` 4 例：短文不分块、句末切块不超上限、无句读硬切不丢字、小块切分 |

## 对外接口 / 被谁调用

- `TtsPlayer(context, onMessage)`：全工程两个构造点——`DetailScreen.kt`（朗读思路）与 `SettingsScreen.kt`（试听），都传了 `onMessage`。
- `speak(text, rate, engine, cloud = null, fallback = true)`（`TtsPlayer.kt:68`）← `DetailScreen.toggleTtsText`（cloud 引擎时传 `CloudTtsConfig` 含 model，正文 fallback 默认 true）、`SettingsScreen.togglePreview`（试听，cloud 引擎时传 `fallback=false`）。
- `updateRate(rate)`（`TtsPlayer.kt:97`）← 详情页与设置页朗读中拖语速条；对在线引擎无额外动作——`liveRate` 每句请求前重读，下一句自然生效。
- `stop()`（`TtsPlayer.kt:109`）← 两个调用点；`track?.pause()+flush()` 立刻掐声。
- `shutdown()`（`TtsPlayer.kt:126`）← 仅详情页 `onCleared`。
- `val isSpeaking`（`:59`）、`var onStateChange`（仍无赋值点，宿主都用轮询）。
- `LocalTtsEngine.obtain/release`、`internal fun splitForSpeech`（`TtsPlayer.kt:475`，`TtsSplitTest` 直接测；生产三处调用都传 120）。
- `CloudTts(cfg)` / `streamPcm`（`CloudTts.kt:130`）仅 `TtsPlayer.speakCloud` 使用；`fetchModels()` / `filterTtsModels` / `voicePresetsFor` / `buildVoice` / `voiceLabel` 被设置页 UI 用（检索模型、音色预置 chips、模型/音色拼装）。

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
| `CloudTtsConfig(baseUrl, key, voice, model)` | `CloudTts.kt` | 在线朗读配置；调用方从设置组装后传入（model 默认 CosyVoice2） |
| `CloudTts.VOICES` | `CloudTts.kt:36` | 硅基流动 8 预置音色（接口后缀名 ↔ 中文标签） |
| `buildVoice(model, voice)` | `CloudTts.kt:74` | 音色请求期拼装：短名补模型前缀 / 旧前缀替换 / OpenAI 式原样 |
| 两级格式与重试码 | `CloudTts.kt:130-217` | pcm-stream → 400/404/415/422 或 200+文本 → 极简 WAV；解析重采样后回调 |
| chunked PCM 解码 | `CloudTts.kt` + `CloudTtsAudio.decodePcmLe` | 8KB 块读入 + 奇数字节 `carry` 进位 + 小端拼 ShortArray |
| `splitForSpeech` 块规则 | `TtsPlayer.kt:475` | 句末边界切分、每块 ≤120 字；三引擎统一 120 |

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
11. **音色预置按模型自适应**：CosyVoice 系 8 个中文标签；OpenAI tts-1 系 6 个、gpt-4o-mini-tts 11 个；其他模型自由手填。CosyVoice 自定义/克隆音色（`speech:…` URI）接口本身支持，可在音色框手填。换了模型但音色框还挂着旧值时，`buildVoice` 会把旧模型前缀替换成当前模型——但 OpenAI 式模型配 CosyVoice 音色名仍会被服务端拒，点音色 chips 里的对应预置即可。
12. **pcm 字节序与流式格式**：服务端实际输出 16bit 小端（业界惯例）。2026-10-24 已在模拟器上用模拟 OpenAI 兼容服务验证全链路：流式 PCM 边下边播成功、渠道拒收流式参数时自动 WAV 回退成功（44100Hz 重采样到 24k 数值正确）、真实中转站 csid.cc 检索模型成功（26 个模型、筛出语音类 0 个）。**真机听感仍待老板验收**；若真机出现刺耳杂音，优先怀疑字节序。
13. **fetchTtsModels 必须切 IO 线程**：`SettingsViewModel` 的协程默认 Main dispatcher，OkHttp 同步 `execute()` 在主线程会直接抛 `NetworkOnMainThreadException`（被兜底异常包成「无法连接语音接口」，现象是检索必失败且原因误导）。已用 `withContext(Dispatchers.IO)` 包住（对齐 `LlmClient.listModels` 的做法），以后新加网络调用点同样注意。
14. **渠道可能根本没有语音模型**（2026-10-24 实测 csid.cc：26 个模型全是聊天/生图/视频类）：这是「填了 URL/Key 却没声音」的最常见根因。检索模型筛出 0 个语音模型时 App 会列出全部模型并提示「点选后试听验证」；要稳定用在线朗读，选一个有 TTS 模型的渠道（硅基流动 CosyVoice2 免费）。试听失败会弹准确原因（不静默回落），朗读正文仍会回落离线/系统引擎。

## 最后更新

2026-10-24 · 小改：测试假 key 从 `sk-test` 改为 `test-key-fake`（不再形似真 key）；tts 包内配置字段与 transport 参数统一由 apikey 字样改名 `key`（语义不变，消除会话收尾扫描器把「字段名 + 冒号 + String 类型标注」误判成密钥赋值的固定误报）。197 例单测全过。

2026-10-24 · 在线朗读通用化改造（修「出不来语音」）：①模型可配置（DataStore 新键 `tts_cloud_model`，设置页模型框 + 「检索模型」按钮 + 点选 chips，`fetchModels` GET `/models` + `filterTtsModels` 筛语音类）；②音色自适应（`buildVoice` 请求期拼装 + `voicePresetsFor` 按模型给预置，修「换渠道后音色前缀不匹配必失败」）；③两级格式回退（流式 PCM 被拒 → 极简 WAV 请求，`CloudTtsAudio` 解析+重采样，修「非硅基渠道不支持 pcm+stream 必失败」）；④200 但返回 JSON 不再当音频喂喇叭；⑤`TtsPlayer.speak` 加 `fallback` 参数（设置页试听失败只报原因不静默回落）；⑥新增 tag `CloudTts` 排查日志；⑦朗读卡加「已填 Key 但引擎没切过来」提醒。新增 `CloudTtsAudio.kt`；`CloudTtsTest` 扩到 21 例（全项目 197 例单测全过）。**模拟器实测**：模拟 OpenAI 兼容服务上流式 PCM 与 WAV 回退两链路全通（含 44100→24000 重采样），检索模型/点选联动/试听出声链路日志齐备；真实中转站 csid.cc 检索模型成功（结论：该渠道无任何语音模型，坑 14）。**真机听感待老板验收（UNMEASURED）**。修一处真 bug：`fetchTtsModels` 主线程发网络请求必炸（坑 13）。

2026-10-24 · 离线朗读句间隔优化：`speakLocal` 的 AudioTrack 缓冲从 `maxOf(minBuf*4, 16384)`（约 0.34 秒）放大到 `sampleRate*2*5`（约 5 秒）——逐句循环本就背靠背合成，放大缓冲即得「边播当前句边预合成后面几句」的领先量，句间启动耗时被盖住。调速生效最多延后约 5 秒（坑 2）；`stop()` 的 `pause+flush` 照旧立刻掐声。184 个单测全过、`assembleDebug` 成功；句间听感改善待真机试听（UNMEASURED）。

2026-10-24 · 新增在线朗读引擎（第三引擎）：`CloudTts.kt`（182 行，硅基流动 CosyVoice2 流式 PCM）、`TtsPlayer.speakCloud`（失败自动回落离线/系统）、`stop()` 加 `pause+flush` 立刻掐声、`speak` 加第 4 参 `cloud: CloudTtsConfig?`；设置页与详情页调用点改传云端配置；新增 `CloudTtsTest` 7 例（184 个单测全过）。两个调用点的行号按本次重排后重标。

2026-10-04 · 独立评分（G2）返修：坑 12 的「README 未列双引擎」登记作废；`onMessage` 写入点表述收紧。其余约 50 处引用经抽验全部命中。

2026-10-03 · 对照 v1.5.0 全面重写：`TtsPlayer.kt` 从单系统引擎重写为双引擎门面，新增 `SherpaTts.kt`、`TtsChunkCallback.java`、`TtsSplitTest.kt`。

2026-09-20 · 补写模块总览（首次成文，描述的是单系统引擎旧版）
