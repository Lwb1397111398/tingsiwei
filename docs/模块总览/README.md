# 「听的思维」模块总览

> 录音/文字 → 转写 → LLM 生成「思维导图+思路」→ 拖拽编辑 → AI 修改闭环 的安卓 APP。

## 模块索引

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 入口/导航 | `app/src/main/java/com/tingsiwei/app/MainActivity.kt`、`ui/AppNav.kt` | 单 Activity + Compose Navigation，5 个路由：home / record / textinput / detail/{noteId} / settings |
| 数据层 | `data/db/`（Room：notes、versions 表，**version=1 未改动**）、`data/SettingsRepository.kt`（DataStore） | 笔记、版本快照、应用设置（LLM 地址/KEY/模型、转写方式、AI 拓展开关、语速、**上下文窗口/请求间隔/保守模式**） |
| LLM 客户端 | `llm/LlmClient.kt`、`llm/LlmSession.kt`、`llm/LlmPolicy.kt` | OpenAI 兼容：地址规范化（自动补 `/v1`）、`/models` 拉模型、`/chat/completions` 对话。**弹性层**：`Transport` 接缝（可注入假实现做 JVM 单测）、`LlmCallError` 分级（429/5xx/网络/超时/坏格式/空/截断）、`RateGate` 进程级节流闸（取号即放锁，退避不占位）、指数退避+抖动（认 `Retry-After`）、`Tokens` 预算（CJK 一字一 token，压输出→截输入→缩 system 四级降级，绝不因"放不下"打断请求） |
| 提示词 | `llm/Prompts.kt` | 生成/修改/分段提炼/分层归并/最终汇总 + 截断降级规则 |
| 生成流水线 | `llm/Generator.kt`、`llm/LongTextPipeline.kt`、`llm/TextChunker.kt`、`llm/SegmentStore.kt` | 短内容一次生成；长内容 = **语义分段**（句末边界+1 句重叠+可还原+块数上限）→ 逐段提炼 → 最多两层归并 → 一次最终生成。段成果按 `sha1(内容)+参数` 指纹落盘（纯文件，不动 Room），失败段跳过、重跑只补失败段，内容一改旧断点整体作废。`revise()` 改前存版本快照；状态机：DRAFT→TRANSCRIBING→GENERATING→READY/ERROR（READY 时 `errorMsg` 用作"提示"而非错误） |
| 导图格式引擎 | `mindmap/TreeText.kt` | TAB 缩进树 ↔ mind-elixir JSON 互转；多顶层主题自动包虚拟根；单测 `TreeTextTest` |
| LLM 输出解析 | `mindmap/LlmOutputParser.kt` | 容错解析 `<导图>…</导图>` + `<思路>…</思路>`（容忍空格/代码块/缺失标签）；单测 |
| 导图画布 | `app/src/main/assets/mindmap/` + `ui/components/MindMapPanel.kt` | WebView 跑 mind-elixir v5（esbuild 自打包，含 nodeDraggable/operationHistory/contextMenu 插件）；桥：`onMapChanged`(500ms 防抖) 回传数据、`appInit/appSetData/appSetDark/appExportPng` 等 |
| 录音 | `record/Recorder.kt` | MediaRecorder → m4a(AAC 16k 单声道 32kbps)，暂停/继续，时长统计 |
| 音频解码 | `transcribe/AudioDecode.kt`、`transcribe/SpeechCut.kt` | MediaCodec 解码任意音频 → 16k 单声道 PCM。出块点由 `SpeechCut.pickCut`（纯函数）在**静音中点**选择：目标 15s、硬上限 30s、静音判据 220ms；只看已累积的缓冲区，内存与音频时长无关 |
| 离线识别 | `transcribe/SherpaTranscriber.kt`、`transcribe/Transcriber.kt` | sherpa-onnx AAR(`app/libs/`) + SenseVoice int8 中文模型；流式逐块识别拼接 |
| 模型下载 | `transcribe/ModelManager.kt` | hf-mirror 下载 model.int8.onnx(~226MB)+tokens.txt，断点续传（.part 文件），5 次重试 |
| 系统识别 | `transcribe/SystemSpeechRecognizer.kt` | 边录边转，会话中断自动重启续接 |
| 文字转写（备用） | 设置里切换 `TranscribeMode` | OFFLINE / SYSTEM 双模式 |
| TTS 朗读 | `tts/TtsPlayer.kt` | 系统 TextToSpeech 朗读思路，语速 0.5~2.0x |
| 导出 | `export/Exporter.kt` | Markdown（导图+思路+原文）、FileProvider 分享 |

## 关键设计决策

1. **导图数据格式 = TAB 缩进树**：LLM 输出、用户粘贴、AI 修改回传，全部同一格式（用户示例格式），树引擎负责与 mind-elixir JSON 互转。
2. **mind-elixir 用源码自打包**：npm 发行包不含拖拽插件（v5 拖拽是独立源码文件），用 esbuild 把 `src/index.ts + plugin/nodeDraggable + operationHistory + contextMenu` 打成 IIFE 放入 assets；产物：`MindElixirFull.iife.js/.css`。再生成方法见下。
3. **长录音（1.1.0 重做）**：解码按静音点出块（15~30s）→ 逐块识别拼接并回报真实进度；生成阶段按 token 预算语义分段（句末边界 + 1 句重叠）→ 逐段提炼 → 最多两层归并 → 一次生成，任何一次调用都不超窗口；429/5xx 走进程级节流 + 指数退避，段成果落盘可续跑。
4. **拖动防回环**：MindMapPanel 记录 `localJson`，自己拖动产生的数据不回推画布，只有外部变化（AI 重生成/恢复版本）才 setData。
5. **saveMap 只在 READY 状态写库**，避免与生成流程互相覆盖。
6. **构建路径必须纯英文**（AGP 拒绝中文路径），项目在 `E:\engine\tingsiwei`。

## 构建说明

- Gradle 8.9：`E:\engine\gradle-8.9\bin\gradle.bat`（`org.gradle.java.home` 指向 Android Studio JBR 21）
- 仓库：阿里云镜像（settings.gradle.kts）；SDK：`local.properties` → `E:/engine/androidstudioSDK`
- 命令：`GRADLE_USER_HOME=E:/engine/.gradle gradle.bat -p E:\engine\tingsiwei assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试：`testDebugUnitTest`（**共 98 例**：`TreeTextTest` 9、`LlmOutputParserTest` 5、`LlmPolicyTest` 27、`TextChunkerTest` 18、`LongTextPipelineTest` 17、`SpeechCutTest` 13、`PcmBufferTest` 9；全部纯 JVM，不依赖设备，不加新测试依赖，用 `runBlocking` + 手写 fake + 可注入时钟/等待）
- 导图前端资源再生成：`esbuild entry.ts --bundle --format=iife --global-name=MECore --loader:.svg=text "--define:import.meta.env.MODE=\"full\""`（源码 `E:\engine\me-core\mind-elixir-core-5.15.1`）

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
