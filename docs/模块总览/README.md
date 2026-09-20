# 「听的思维」模块总览

> 录音/文字 → 转写 → LLM 生成「思维导图+思路」→ 拖拽编辑 → AI 修改闭环 的安卓 APP。

## 模块索引

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 入口/导航 | `app/src/main/java/com/tingsiwei/app/MainActivity.kt`、`ui/AppNav.kt` | 单 Activity + Compose Navigation，5 个路由：home / record / textinput / detail/{noteId} / settings |
| 数据层 | `data/db/`（Room：notes、versions 表）、`data/SettingsRepository.kt`（DataStore） | 笔记、版本快照、应用设置（LLM 地址/KEY/模型、转写方式、AI 拓展开关、语速） |
| LLM 客户端 | `llm/LlmClient.kt` | OpenAI 兼容：地址规范化（自动补 `/v1`）、`/models` 拉模型列表、`/chat/completions` 对话；OkHttp + kotlinx-serialization |
| 提示词 | `llm/Prompts.kt` | 生成/修改/分块提炼/合并 四类 system+user 提示词 |
| 生成流水线 | `llm/Generator.kt` | `generate()`（>5000 字自动分块 map-reduce）、`revise()`（改前存版本快照）、`restoreVersion()`；状态机：DRAFT→TRANSCRIBING→GENERATING→READY/ERROR |
| 导图格式引擎 | `mindmap/TreeText.kt` | TAB 缩进树 ↔ mind-elixir JSON 互转；多顶层主题自动包虚拟根；单测 `TreeTextTest` |
| LLM 输出解析 | `mindmap/LlmOutputParser.kt` | 容错解析 `<导图>…</导图>` + `<思路>…</思路>`（容忍空格/代码块/缺失标签）；单测 |
| 导图画布 | `app/src/main/assets/mindmap/` + `ui/components/MindMapPanel.kt` | WebView 跑 mind-elixir v5（esbuild 自打包，含 nodeDraggable/operationHistory/contextMenu 插件）；桥：`onMapChanged`(500ms 防抖) 回传数据、`appInit/appSetData/appSetDark/appExportPng` 等 |
| 录音 | `record/Recorder.kt` | MediaRecorder → m4a(AAC 16k 单声道 32kbps)，暂停/继续，时长统计 |
| 音频解码 | `transcribe/AudioDecode.kt` | MediaCodec 解码任意音频 → 16k 单声道 PCM，按 15 秒分块吐出（避免长录音整体载入内存） |
| 离线识别 | `transcribe/SherpaTranscriber.kt`、`transcribe/Transcriber.kt` | sherpa-onnx AAR(`app/libs/`) + SenseVoice int8 中文模型；流式逐块识别拼接 |
| 模型下载 | `transcribe/ModelManager.kt` | hf-mirror 下载 model.int8.onnx(~226MB)+tokens.txt，断点续传（.part 文件），5 次重试 |
| 系统识别 | `transcribe/SystemSpeechRecognizer.kt` | 边录边转，会话中断自动重启续接 |
| 文字转写（备用） | 设置里切换 `TranscribeMode` | OFFLINE / SYSTEM 双模式 |
| TTS 朗读 | `tts/TtsPlayer.kt` | 系统 TextToSpeech 朗读思路，语速 0.5~2.0x |
| 导出 | `export/Exporter.kt` | Markdown（导图+思路+原文）、FileProvider 分享 |

## 关键设计决策

1. **导图数据格式 = TAB 缩进树**：LLM 输出、用户粘贴、AI 修改回传，全部同一格式（用户示例格式），树引擎负责与 mind-elixir JSON 互转。
2. **mind-elixir 用源码自打包**：npm 发行包不含拖拽插件（v5 拖拽是独立源码文件），用 esbuild 把 `src/index.ts + plugin/nodeDraggable + operationHistory + contextMenu` 打成 IIFE 放入 assets；产物：`MindElixirFull.iife.js/.css`。再生成方法见下。
3. **长录音**：解码按块（15s）→ 逐块识别拼接；生成阶段 >5000 字先分段提炼再合并，避免超上下文。
4. **拖动防回环**：MindMapPanel 记录 `localJson`，自己拖动产生的数据不回推画布，只有外部变化（AI 重生成/恢复版本）才 setData。
5. **saveMap 只在 READY 状态写库**，避免与生成流程互相覆盖。
6. **构建路径必须纯英文**（AGP 拒绝中文路径），项目在 `E:\engine\tingsiwei`。

## 构建说明

- Gradle 8.9：`E:\engine\gradle-8.9\bin\gradle.bat`（`org.gradle.java.home` 指向 Android Studio JBR 21）
- 仓库：阿里云镜像（settings.gradle.kts）；SDK：`local.properties` → `E:/engine/androidstudioSDK`
- 命令：`GRADLE_USER_HOME=E:/engine/.gradle gradle.bat -p E:\engine\tingsiwei assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试：`testDebugUnitTest`（树解析/LLM 输出解析共 11 例）
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
