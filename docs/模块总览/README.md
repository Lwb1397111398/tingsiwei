# 「听的思维」模块总览

> 录音/文字 → 转写 → LLM 生成「思维导图+思路」→ 拖拽编辑 → AI 修改闭环 的安卓 APP。

本目录规则：这里是**唯一索引**，每个模块另有一份同名 `*模块总览.md`，含 8 个固定字段（模块职责 / 运作流程 / 入口文件清单 / 对外接口与被谁调用 / 依赖的上游模块 / 关键数据结构 / 已知坑与约束 / 最后更新）。改代码要同步改对应那份，职责变了再改本索引那一行。

## 模块索引

| 模块 | 位置 | 职责 | 总览 |
| --- | --- | --- | --- |
| 入口与导航 | `app/src/main/java/com/tingsiwei/app/App.kt`、`MainActivity.kt`、`ui/AppNav.kt`、`ui/theme/Theme.kt`、`app/src/main/AndroidManifest.xml` | 单 Activity + Compose Navigation，5 个路由：home / record / textinput / detail/{noteId} / settings；Application 单例、主题、权限与前台服务注册 | [入口与导航模块总览.md](入口与导航模块总览.md) |
| 首页 | `ui/HomeScreen.kt` | 笔记列表、状态徽标、新建入口 | [首页模块总览.md](首页模块总览.md) |
| 录音页 | `ui/RecordScreen.kt` | 录音控制、实时字幕、进度与通知联动 | [录音页模块总览.md](录音页模块总览.md) |
| 文字录入页 | `ui/TextInputScreen.kt` | 粘贴文字/缩进树导图直接建笔记 | [文字录入页模块总览.md](文字录入页模块总览.md) |
| 详情页与生成编排 | `ui/DetailScreen.kt` | 三标签（导图/原文/思路）、版本快照、重试与续跑、朗读与导出——UI 与 `DetailViewModel` 同文件，跨模块业务编排的总入口 | [详情页与生成编排模块总览.md](详情页与生成编排模块总览.md) |
| 设置页 | `ui/SettingsScreen.kt` | LLM 接口/模型、离线模型下载、转写方式、语速、思路精修开关、上下文窗口/请求间隔/保守模式 | [设置页模块总览.md](设置页模块总览.md) |
| 数据层 | `data/db/`（Room：notes、versions 表，**version=1 未改动**）、`data/SettingsRepository.kt`（DataStore） | 笔记、版本快照、应用设置 | [数据层模块总览.md](数据层模块总览.md) |
| LLM 客户端与弹性层 | `llm/LlmClient.kt`、`llm/LlmSession.kt`、`llm/LlmPolicy.kt` | OpenAI 兼容：地址规范化（自动补 `/v1`）、`/models`、`/chat/completions`。**弹性层**：`Transport` 接缝（可注入假实现做 JVM 单测）、`LlmError` 分级（429/5xx/网络/超时/坏格式/空/截断）、`RateGate` 进程级节流闸（取号即放锁，退避不占位）、指数退避+抖动（认 `Retry-After`）、`Tokens` 预算（CJK 一字一 token，四级降级：压输出→缩 system→重算输出余量→截输入，绝不因"放不下"打断请求） | [LLM客户端与弹性层模块总览.md](LLM客户端与弹性层模块总览.md) |
| 提示词 | `llm/Prompts.kt` | 生成/修改/分段提炼/分层归并/最终汇总 + 截断降级规则 | [提示词模块总览.md](提示词模块总览.md) |
| 生成流水线 | `llm/Generator.kt`、`llm/LongTextPipeline.kt`、`llm/TextChunker.kt`、`llm/SegmentStore.kt` | 短内容默认一次生成（设置里开「思路精修」才走三段式：起草思路→自查优化→按思路出图）；长内容 = **语义分段**（句末边界+1 句重叠+可还原+块数上限）→ 逐段提炼 → 最多两层归并 → 一次最终生成。段成果按 `sha1(内容 + "\u0000" + 参数)` 指纹落盘（`SegmentStore.kt:78`，纯文件，不动 Room），失败段跳过、重跑只补失败段，内容一改旧断点整体作废；停手阈值：不可重试错误连续 2 段、提炼失败连续 3 段（`LongTextPipeline.kt:317-321`）。`revise()` 改前存版本快照；状态机 DRAFT / TRANSCRIBING / GENERATING / READY / ERROR（READY 时 `errorMsg` 用作"提示"而非错误；粘贴文字等入口可直达 READY，非线性流转） | [生成流水线模块总览.md](生成流水线模块总览.md) |
| 导图格式引擎 | `mindmap/TreeText.kt` | TAB 缩进树 ↔ mind-elixir JSON 互转；多顶层主题自动包虚拟根 | [导图格式引擎模块总览.md](导图格式引擎模块总览.md) |
| LLM 输出解析 | `mindmap/LlmOutputParser.kt` | 容错解析 `<导图>…</导图>` + `<思路>…</思路>`（容忍空格/代码块/缺失标签）。配测 `LlmOutputParserTest` 5 例，**与 `TreeTextTest` 同在一个文件** `app/src/test/java/com/tingsiwei/app/TreeTextTest.kt:102` | [LLM输出解析模块总览.md](LLM输出解析模块总览.md) |
| 导图画布 | `ui/components/MindMapPanel.kt` + `assets/mindmap/index.html`、`MindElixirFull.iife.js` | WebView 跑 mind-elixir v5（esbuild 自打包）。桥：`onMapChanged`(500ms 防抖) 回传数据；原生→JS：`appInit/appSetData/appSetDark/appSetEditable/appUndo/appRedo/appExportPng`；JS→原生：`onMapChanged/onLog/onPngReady` | [导图画布模块总览.md](导图画布模块总览.md) |
| 录音服务 | `record/Recorder.kt`、`record/RecordSession.kt`、`record/RecordService.kt` | MediaRecorder → m4a(AAC 16k 单声道 32kbps)，暂停/继续、时长统计；前台服务与常驻通知、通知栏「完成」回传 | [录音服务模块总览.md](录音服务模块总览.md) |
| 音频解码与切块 | `transcribe/AudioDecode.kt`、`transcribe/SpeechCut.kt`、`transcribe/PcmBuffer.kt` | MediaCodec 解码任意音频 → 16k 单声道 PCM。出块点由 `SpeechScanner.pickCut`（**有状态类**，`SpeechCut.kt:13,22-24`，非纯函数）在**静音中点**选择：目标 15s、硬上限 30s、静音判据 `silenceThresh=600`；只看已累积缓冲区，内存与音频时长无关 | [音频解码与切块模块总览.md](音频解码与切块模块总览.md) |
| 离线识别 | `transcribe/SherpaTranscriber.kt`、`transcribe/Transcriber.kt` | sherpa-onnx AAR(`app/libs/sherpa-onnx-1.13.8.aar`) + SenseVoice int8 中文模型；**`OfflineRecognizer` 逐块整段解码**（非流式，`SherpaTranscriber.kt:17`），块间拼接并回报进度 | [离线识别模块总览.md](离线识别模块总览.md) |
| 模型下载 | `transcribe/ModelManager.kt` | hf-mirror 下载 `model.int8.onnx`（`MODEL_BYTES = 237_115_547` ≈ 226 MiB）+ `tokens.txt`，断点续传（`.part`），共 5 次尝试、线性退避（1.5/3/4.5/6s） | [模型下载模块总览.md](模型下载模块总览.md) |
| 系统识别 | `transcribe/SystemSpeechRecognizer.kt` | 边录边转，会话中断自动重启续接 | [系统识别模块总览.md](系统识别模块总览.md) |
| 转写模式选择 | `data/SettingsRepository.kt`（`TranscribeMode` 定义在此，不在 `transcribe/`）+ 调用侧 | OFFLINE / SYSTEM 双模式，**默认 OFFLINE**（`SettingsRepository.kt:58`），设置页可切 | [转写模式选择模块总览.md](转写模式选择模块总览.md) |
| TTS 朗读 | `tts/TtsPlayer.kt` | 系统 TextToSpeech 朗读思路，语速滑杆 0.5~2.0x（区间钳制在 UI 侧） | [TTS朗读模块总览.md](TTS朗读模块总览.md) |
| 导出 | `export/Exporter.kt` | Markdown（导图+思路+原文）、FileProvider 分享 | [导出模块总览.md](导出模块总览.md) |
| 通用工具 | `util/Formatters.kt` | 时长/体积/时间等格式化，被各页面共用 | [通用工具模块总览.md](通用工具模块总览.md) |

## 关键设计决策

1. **导图数据格式 = TAB 缩进树**：LLM 输出、用户粘贴、AI 修改回传，全部同一格式（用户示例格式），树引擎负责与 mind-elixir JSON 互转。
2. **mind-elixir 用源码自打包**：npm 发行包不含插件实现（v5 拖拽是独立源码文件），用 esbuild 把 `src/index.ts + plugin/nodeDraggable + operationHistory + contextMenu` 打成 IIFE 放入 assets；产物只有一个 `MindElixirFull.iife.js`，**CSS 经 `--minify --target=chrome80` 后内联进 `index.html`（`index.html:6`），assets 下没有独立 `.css` 文件**。注意：v5.15.1 的 `nodeDraggable` 自身实现近乎空壳（源码 `nodeDraggable.ts:330-334`），`index.html:131-132` 又重复 install 了 `operationHistory`。再生成方法见下。
3. **长录音（1.1.0 重做）**：解码按静音点出块（15~30s）→ 逐块识别拼接并回报真实进度；生成阶段按 token 预算语义分段（句末边界 + 1 句重叠）→ 逐段提炼 → 最多两层归并 → 一次生成，任何一次调用都不超窗口；429/5xx 走进程级节流 + 指数退避，段成果落盘可续跑。
4. **拖动防回环**：MindMapPanel 记录 `localJson`，自己拖动产生的数据不回推画布，只有外部变化（AI 重生成/恢复版本）才 setData。
5. **saveMap 只在 READY 状态写库**，避免与生成流程互相覆盖。
6. **构建路径必须纯英文**（AGP 拒绝中文路径），项目在 `E:\engine\tingsiwei`。
7. **UI 与 ViewModel 同文件**是本工程的既有写法（`HomeScreen.kt:252`、`RecordScreen.kt:190`、`SettingsScreen.kt:294`、`DetailScreen.kt:432+` 都是），不是失误；其中只有 `DetailScreen.kt` 承担跨模块业务编排，改动它要连带看流水线与导图两块的总览。

## 构建说明

- Gradle 8.9：`E:\engine\gradle-8.9\bin\gradle.bat`（`org.gradle.java.home` 指向 Android Studio JBR 21）
- 仓库：阿里云镜像（settings.gradle.kts）；SDK：`local.properties` → `E:/engine/androidstudioSDK`
- 命令：`GRADLE_USER_HOME=E:/engine/.gradle gradle.bat -p E:\engine\tingsiwei assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（对外发的包在 `E:\AI Agent\work area\听的思维\听的思维-安装包-v1.1.0.apk`）
- 单元测试：`testDebugUnitTest`（**共 98 例，6 个测试文件 / 7 个测试类**：`LlmPolicyTest` 27、`TextChunkerTest` 18、`LongTextPipelineTest` 17、`TreeTextTest` 9、`LlmOutputParserTest` 5（**与 `TreeTextTest` 同文件**，`TreeTextTest.kt:10` 与 `:102`，按文件名去找会误判成缺失）、`SpeechCutTest` 13、`PcmBufferTest` 9；全部纯 JVM，不依赖设备，不加新测试依赖，用 `runBlocking` + 手写 fake + 可注入时钟/等待）。**没有单测的部分**：`Generator` 与 `revise`/版本快照、`ModelManager` 下载续传、`SherpaTranscriber`、`SystemSpeechRecognizer`、`AudioDecode`、全部 UI 页面
- 导图前端资源再生成：`esbuild entry.ts --bundle --format=iife --global-name=MECore --loader:.svg=text "--define:import.meta.env.MODE=\"full\""`（源码 `E:\engine\me-core\mind-elixir-core-5.15.1`）
- `minSdk = 26`（Android 8.0）

## 已知缺陷与待办（补写模块总览时核出，均已在对应总览的「已知坑与约束」里落档）

1. **语速设置基本不生效**：`TtsPlayer.speak(text, rate)` 只在引擎**未就绪**分支把 `rate` 存进字段（`TtsPlayer.kt:29-36`），已就绪时直接 `speakInternal(text)` 用旧字段值（默认 `1.0f`，`:38`），且没有对外 setter → `DetailScreen.kt:658` 传的 `ttsRate.value` 被丢弃。
2. **导图 PNG 导出链路休眠**：`MindMapPanel.exportPng()`（`:62`）、`Exporter.savePng()`（`:66`）、`Exporter.shareText()`（`:84`）、`onPngCb`（`:25`）全项目零调用点，JS 侧 `onPngReady` 未接回原生。
3. **模型就绪阈值过松**：判据是文件长度 `> 200_000_000`（`SherpaTranscriber.kt:54`、`SettingsScreen.kt:431`），而真实体积 `237_115_547`（`ModelManager.kt:23`）→ 中断留下的 200~237MB 截断文件会被判"已就绪"，续跑逻辑（`ModelManager.kt:44,55`）也会当它下完了。
4. **注释与实现矛盾**：`Transport` 的 KDoc 说意外异常归"不可重试"，实现把它包成可重试的 `Network`（`LlmPolicy.kt:11` vs `LlmSession.kt:112-113`）。
5. **撤销/重做是否落库存疑**：回传点是 `operation` 事件（`index.html:137` → `scheduleSave()`），而 `appUndo/appRedo`（`index.html:174-175`）只调 `mind.undo()/redo()` 加刷按钮，不显式 `scheduleSave()`。真机若出现"撤销后退出重进又变回来"，就在 `appUndo/appRedo` 后补一次 `scheduleSave()`。
6. 小项：`Recorder.kt:14` 注释标"暂停/继续（Android 7.0+）"，`minSdk` 已是 26，该门槛恒满足，注释属冗余；`res/xml/file_paths.xml:4` 的 `exports` 路径疑为历史残留（无对应清理逻辑）。
7. 以下三条**未实测、属静态推断**：SYSTEM 识别与 MediaRecorder 并发占麦能否真出字；`SystemSpeechRecognizer` 在 `Dispatchers.Default` 上创建（start 与 resume 路径线程不一致）是否丢回调；SettingsScreen 下载失败后 `.part` 残留是否会让下载按钮永久禁用（`SettingsScreen.kt:334-337,195,423`）。

## 坑（冒烟测试实测踩过）

- **安卓构建路径必须纯英文**（AGP 拒绝中文路径），项目在 `E:\engine\tingsiwei`
- **Manifest 必须注册 `android:name=".App"`**，否则 `App.get()` 空指针，ViewModel 创建即崩
- **MapBridge 的方法名不能和属性同名**（`fun onReady() { onReady() }` 会递归调自己，栈溢出），属性一律加 Cb 后缀
- **WebView 初始化别依赖 JS→原生的 ready 信号**（鸡生蛋死锁），用 `WebViewClient.onPageFinished`（主线程）驱动首次 `appInit`
- **WebView 上 `body{height:100%}` 百分比链可能失效**（html 正常 body 为 0，且 body overflow:hidden 会把 690px 的 #map 全裁掉 → 画布空白），必须 JS 用 `window.innerHeight` 像素钉死 html/body/#map 三层
- **mind-elixir 的 CSS 必须用 esbuild `--minify --target=chrome80` 打包**：源码含 CSS 嵌套语法，旧 WebView 不支持会整块丢弃样式（节点变透明背景+白字=隐形）；产物 CSS 直接内联进 index.html 更稳
- **模拟器（swiftshader 软件渲染）下 WebView 硬件合成会丢内容**，需 `setLayerType(LAYER_TYPE_SOFTWARE)`；真机保持硬件加速
- **mind-elixir v5 用自定义元素**（`<me-tpc>` 等），addChild 无选中节点时不会默认用根，需手动兜底传 `document.querySelector('#map me-root me-tpc')`
- `avdmanager.bat` 需用 `cmd //c` 调，且 `-k "system-images;..."` 的分号要转义；bash 直调 12.0/bin 下的 bat 更省事
- sherpa-onnx 仓库在 `k2-fsa/sherpa-onnx`（不是 k2fsa）；GitHub 直连易断，用 gh-proxy.com 镜像下载 release
- npm 发行包（mind-elixir v4/v5）没有插件实现，别用现成 IIFE，必须源码打包
- Compose 的 `Color.Transparent` 与 WebView `setBackgroundColor(Int)` 类型不同，需 `android.graphics.Color.TRANSPARENT`
- 模拟器镜像包名是 `system-images;android-37.0;google_apis_playstore;x86_64`（SDK 目录里的 `google_apis_playstore_ps16k` 目录是无效残留）

## 最后更新

2026-09-20 · 补写 22 份模块总览（本目录此前只有这张索引）。同步修正索引中与代码不符的 9 处：`LlmCallError`→`LlmError`；四级降级顺序改为"压输出→缩 system→重算输出余量→截输入"；段指纹补上分隔符写法；`SpeechCut.pickCut` 标注为有状态类；离线识别"流式"改"整块解码"；模型体积改为 `237_115_547` 并说明 226/230 两个口径来源；`TranscribeMode` 由"备用"改为"默认 OFFLINE"且定义在 `data/`；assets 产物"js/.css"改为"js + 内联 CSS"；单测构成补明「6 个文件 / 7 个测试类」——`LlmOutputParserTest` 5 例其实存在，只是与 `TreeTextTest` 同文件（`TreeTextTest.kt:10` 与 `:102`，按文件名去找会误判成缺失，本轮排查中已被误判过一次，特此留证）。新增《已知缺陷与待办》一节（语速、PNG 休眠、200MB 阈值、注释矛盾、撤销落库等）。录音行补 `RecordSession.kt`/`RecordService.kt`，入口行补 `App.kt`/`Theme.kt`/Manifest，界面层 4 页与通用工具此前无人认领，现已入索引。

2026-09-20 · 四轮质检收尾（91 分 / SHIP）后的原始索引。
