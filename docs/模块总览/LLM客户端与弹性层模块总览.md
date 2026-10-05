# LLM 客户端与弹性层模块总览

## 模块职责

把用户手填的接口地址变成一个「打得出去、打不出去也知道为什么」的 OpenAI 兼容调用层：地址规范化 → 组装流式请求 → 进程级节流 → 分类错误 → 指数退避重试 → token 预算降级 → 截断自动加码重问 → SSE/JSON 双格式解析，对外只交出「正文字符串」或「一句中文错误提示」（截断时错误还带回已经生成出来的部分内容，供上游抢救）。

> 术语：**SSE（Server-Sent Events）**是接口一边生成一边把文本一小块一小块推回来的传输方式，每小块一行 `data: ...`；**非流式**则是等全部生成完一次性返回一个大 JSON。本模块 v1.5.0 起默认走流式，但两种回包都能解析。

## 运作流程

**从哪来**：两个真实入口。①生成流水线 `Generator` 在建流水线时把 `client.chat(...)` 包成 `Complete` 闭包（`Generator.kt:42-44`），改写与三段式的 `ask` 也直调它（`Generator.kt:121-127`、`327`）；②设置页 `SettingsViewModel` 自己 `new` 了一个 `LlmClient(repo)`（`SettingsScreen.kt:392`），用 `listModels` 拉模型列表（`SettingsScreen.kt:462`）、用 `chat` 做「测试连接」（`SettingsScreen.kt:477`，system 传空串）。两者传进来的都是设置里原样存的 `llmUrl`，未经任何加工。

**怎么处理**：`LlmClient.chat`（`LlmClient.kt:35-53`）先整段切到 `Dispatchers.IO`（`LlmClient.kt:43`），再走 `endpoint()`：`LlmEndpoint.normalize` 规范化，结果为空串就直接抛 `LlmError.BadUrl()`（`LlmClient.kt:55-56`）。`session()` 每次调用都重读一遍设置并新建 `LlmSession`（`LlmClient.kt:58-74`），把 `llmContextWindow` 夹到 `MinContextWindow..MaxContextWindow`（2048..100 万）、`llmMinIntervalMs` 夹到 `0..MaxMinIntervalMs`（`LlmClient.kt:66-70`），所以设置改完下一句请求就生效，不依赖进程重启；但 `gate` 默认取 `GlobalGate.shared`（`LlmClient.kt:22`），闸门不因多处 new 客户端而失效（`LlmPolicy.kt:95-97`）。

进入 `LlmSession.chat`（`LlmSession.kt:46-80`）：`model.isBlank()` 先抛 `LlmException("请先在设置里选择模型")`（`LlmSession.kt:54`）→ `Tokens.fit(window, system, user, desiredOutTokens)` 做预算（`LlmSession.kt:55`）→ 手工拼 JSON：`model`/`max_tokens=fit.maxOut`/**`stream=true`**/`messages`，`fit.system` 空白就不发 system 消息（`LlmSession.kt:56-70`）。**请求体里没有 temperature 字段**——严格渠道只收默认采样参数，测试 `m6` 锁死了这一点（`LlmPolicyTest.kt:593-601`）→ `call("$baseUrl/chat/completions", ...)`（`LlmSession.kt:71`）。为什么必须流式：很多中转渠道套着 Cloudflare，非流式请求在模型憋长输出时超过约 100 秒无字节往返会被网关掐成 524/503（`LlmSession.kt:40-45` 的 KDoc 原文解释）。

`call()` 是弹性核心（`LlmSession.kt:138-161`）：`gate.acquire(intervalMs) { transport.post/get(...) }`（`LlmSession.kt:142-144`）→ `classify(resp)` 把状态码翻成分级错误，非错误则原样返回（`LlmSession.kt:145`、`163-168`）。`CancellationException` 原样上抛，绝不混进重试（`LlmSession.kt:146-147`）；`LlmError` 与「其它意外异常」都当成一次 outcome 参与判断，后者统一降级成 `LlmError.Network()`（`LlmSession.kt:148-153`）；不可重试或次数用完就抛（`LlmSession.kt:156`）；否则 `sleep(maxOf(err.retryAfterMs ?: 0, backoffMs(attempt, rng)))` 后 `attempt++`——**退避与 Retry-After 取大**，防止渠道回 0/1 秒时变成疯狂重打（`LlmSession.kt:157-158`）。

成功回包进 `parseContent`（`LlmSession.kt:174-179`）：先探测**只要响应里任一行以 `data:` 开头就按 SSE 流解析**（`LlmSession.kt:177-178`），否则按整段 JSON 解析。`parseStreamBody`（`LlmSession.kt:185-213`）逐行取 `data:` 载荷、把 `delta.content` 拼回完整回答、跳过推理模型的思维链 `delta.reasoning_content`、认 `[DONE]` 结束标记；流被中途掐断（既无 `finish_reason` 也无 `[DONE]`）按可重试的 `Network` 走退避（`LlmSession.kt:210-211`）。`parseJsonBody`（`LlmSession.kt:215-237`）兜住「渠道不认 stream 参数仍回整段 JSON」的情况。解析层任何意外都归成 `BadFormat`（`LlmSession.kt:73-79`），不把英文异常甩到界面上。

`chatWithDegrade` 是截断与渠道硬帽的自动降级链（`LlmSession.kt:87-124`），分四步：

1. 正常问一次；抛出 `Reject` 且 `httpCode == 400`、`desiredOutTokens > 8192` → **渠道对 max_tokens 有硬帽**（v1.5.0 输出上限抬到 16384 后新场景），退回 `REJECT_FALLBACK_OUTPUT_TOKENS = 8192` 保守档、system 尾部拼上体量规则再问一次（`LlmSession.kt:99-108`）；401/403/404 与输出参数无关，维持快速失败。
2. 抛出 `Truncated` → **抬高输出预算到 2 倍（至少 4096）**、system 尾部拼体量规则再问一次（`LlmSession.kt:112-113`）。
3. 第二次仍截断 → 把两次中**较长的部分内容**随异常带回（`LlmError.Truncated.partialContent`，`LlmSession.kt:114-115`），由调用方决定抢救还是报错。
4. 第二次被 400 拒 → 退回原预算 + 体量规则最后一试（`LlmSession.kt:116-123`），仍截断同样带回较长的部分。

**产出什么**：`chat` 产出模型正文 `String`；`listModels` 产出 `List<String>` 模型 id（`LlmSession.kt:126-134`、`239-252`）。失败产出 `LlmError`/`LlmException`，其 `message` 本身就是中文提示，调用方直接展示（`LlmClient.kt:33` 注释即此约定）。`Truncated` 异常额外带回 `partialContent`（`LlmPolicy.kt:63`）。

**影响谁**：`Generator` 把 message 写进 `notes.errorMsg`（`Generator.kt:83`、`147`、`159`、`169`）；`LongTextPipeline` 用 `retryable` 决定「这段跳过」还是「整篇停手」（`LongTextPipeline.kt:382-400` 的 `callOrSalvage`）；`Truncated.partialContent` 被上游 `LlmOutputParser.parsePartial` 抢救成半截导图；`Tokens.fit` 会**改写**用户输入（截断加尾标），这是导图内容与原文不完全一致的根因之一；`RateGate` 是全进程节奏的总闸，生成流水线、设置页测试连接、模型列表共用同一条队列。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/llm/LlmClient.kt` | 120 | 面向业务的门面 + 生产 `OkHttpTransport`：读设置 → 规范化地址 → 组装 `LlmSession`；OkHttp 三档超时与 IO 异常归类也在此 |
| `app/src/main/java/com/tingsiwei/app/llm/LlmSession.kt` | 253 | 纯 Kotlin 会话：预算 → 节流 → 发送 → 分类 → 退避重试 → SSE/JSON 双解析 → 截断/硬帽降级，零 Android 依赖 |
| `app/src/main/java/com/tingsiwei/app/llm/LlmPolicy.kt` | 244 | 弹性层全部零件：`Transport`/`Sleeper`/`ClockSource` 接缝、`LlmError` 分级、`RateGate`、`LlmEndpoint`、`LlmPolicy` 常量/退避/Retry-After、`Tokens` 预算 |
| `app/src/test/java/com/tingsiwei/app/LlmPolicyTest.kt` | 612 | 本模块 34 个 JVM 单测：429 退避、Retry-After、截断加码、400 硬帽降档、节流闸、预算降级、地址规范化、模型列表 GET、无 temperature |
| `app/src/test/java/com/tingsiwei/app/LlmSessionStreamTest.kt` | 115 | 流式解析 8 个单测：SSE 拼块、stream=true 断言、思维链跳过、length 截断带回部分内容、流中断按网络错误、整段 JSON 兼容、keep-alive 注释行 |

`LlmClient.kt:16` 定义了与 `LlmError` 并列的 `LlmException`，专门装「非接口层」的业务错误文案（空导图、没选模型等）。

## 对外接口 / 被谁调用

| 对外符号 | 位置 | 被谁用 |
| --- | --- | --- |
| `LlmClient.chat(baseUrl, apiKey, model, system, user, maxTokens?, degradeRule?)` | `LlmClient.kt:35-53` | `Generator` 的 `Complete` 闭包（`Generator.kt:43`）、改写 `Ask`（`Generator.kt:120-127`）、`Generator.ask`（`Generator.kt:327`）、设置页测试连接（`SettingsScreen.kt:477`） |
| `LlmClient.listModels(baseUrl, apiKey)` | `LlmClient.kt:27-29` | 设置页拉模型下拉（`SettingsScreen.kt:462`） |
| `LlmSession` | `LlmSession.kt:20` | 仅 `LlmClient.session()`（`LlmClient.kt:58-74`）与两个测试类直接构造 |
| `LlmError`（含 `retryable`/`httpCode`/`retryAfterMs`/`Truncated.partialContent`） | `LlmPolicy.kt:33-68` | `LongTextPipeline.callOrSalvage` 靠 `retryable` 分诊并靠 `partialContent` 抢救（`LongTextPipeline.kt:382-400`）；`Generator.friendly` 直接取 `message`（`Generator.kt:329-334`） |
| `Transport.post` / `Transport.get` | `LlmPolicy.kt:11` / `:13` | post 走对话，get 走模型列表（OpenAI 兼容渠道对 POST /models 通常回 404）；单测注入假实现 |
| `RateGate` / `GlobalGate.shared` | `LlmPolicy.kt:74-92` / `:95-97` | `LlmClient`/`LlmSession` 的默认 gate；单测用 `RateGate(time, time)` 替换（`LlmPolicyTest.kt:78`） |
| `LlmEndpoint.normalize` | `LlmPolicy.kt:103-110` | `LlmClient.endpoint`（`LlmClient.kt:55-56`）；纯函数被 JVM 单测直接覆盖（`LlmPolicyTest.kt:520-547`） |
| `LlmPolicy.*`（`retryAfterMs`/`backoffMs`/`windowFor`/`intervalFor`） | `LlmPolicy.kt:142-163` | `LlmSession.call` 的退避（`LlmSession.kt:158`）、`window`/`intervalMs`（`LlmSession.kt:37-38`）、`LongTextPipeline.effectiveWindow`（`LongTextPipeline.kt:68`）、`Generator.stagedOrDirect` 的窗口估算（`Generator.kt:238`） |
| `Tokens`（`estimate`/`fit`/`truncateToTokens`） | `LlmPolicy.kt:166-243` | `LlmSession.chat`（`LlmSession.kt:55`）、`LongTextPipeline`/`TextChunker`/`StagedFlow` 的全部体量判断（`LongTextPipeline.kt:97`、`114`、`314`、`341-357`；`StagedFlow.fits`） |

`SettingsRepository` 反过来引用 `LlmPolicy` 的默认值与迁移判据（`SettingsRepository.kt:38`、`40`、`79-80`、`94`、`97`、`108-109`——`DEFAULT_CONTEXT_WINDOW`/`DEFAULT_MIN_INTERVAL_MS`/`LEGACY_DEFAULT_CONTEXT_WINDOW`），是唯一的「llm 包被 data 包依赖」点；它自己的夹逼边界（`MinContextWindow` 等）是另一组自有常量（`158-161`）。

## 依赖的上游模块

- **`data/SettingsRepository.kt`**：唯一的配置来源，取 `llmContextWindow`/`llmMinIntervalMs`/`llmConservative`（`LlmClient.kt:59-72`）。边界常量由它定义：`MinContextWindow = 2048`、`MaxContextWindow = 1_000_000`、`MaxMinIntervalMs = 60_000L`（`SettingsRepository.kt:158-161`）。窗口默认值走 `LlmPolicy.DEFAULT_CONTEXT_WINDOW = 131_072`；老用户存的是旧默认 16384 的，首次读取被一次性迁到新默认（`windowMigrated` 标记，`SettingsRepository.kt:94-97`）。
- **`llm/Prompts.kt`**：`LlmClient.chat` 的 `degradeRule` 参数默认取 `Prompts.trimRule()`（`LlmClient.kt:42`）；改写链路换成 `Prompts.diffTrimRule()`（`Generator.kt:123`）。本模块不认识其它提示词。
- **OkHttp**：`OkHttpClient` 三档超时（连接 20s / 读 300s / 写 60s，`LlmClient.kt:80-84`），读超时给到 5 分钟；流式下读超时是「两次字节到达之间的间隔」而不是总时长，因此长回答不会被它掐。
- **kotlinx-serialization-json**：只用 `Json` 的 DOM（`JsonObject`/`JsonArray`/`JsonPrimitive`）手工取字段，不定义 `@Serializable` 数据类（`LlmSession.kt:4-13`、`35`）。
- **kotlinx-coroutines**：`Mutex`/`withLock`（`LlmPolicy.kt:3-4`）、`delay`（`LlmPolicy.kt:23-27`）、`withContext(Dispatchers.IO)`（`LlmClient.kt:4-5`）。
- **无 Android 依赖**：`LlmSession.kt` 与 `LlmPolicy.kt` 全文不 import 任何 `android.*`，这是整层能跑纯 JVM 单测的前提（`LlmSession.kt:16-19` 注释；`LlmPolicyTest`/`LlmSessionStreamTest` 零 Robolectric）。

## 关键数据结构

| 结构 | 位置 | 说明 |
| --- | --- | --- |
| `Transport.RawResp(code, body, headers)` | `LlmPolicy.kt:9` | 一次 HTTP 往来的最小三元组；`Transport` 有 `post`（对话）与 `get`（模型列表）两个方法（`LlmPolicy.kt:11`、`13`） |
| `Sleeper` / `ClockSource` | `LlmPolicy.kt:16-27` | 等待与时钟的接缝；生产实现 `CoroutineSleeper`、`SystemClock` |
| `LlmError` 家族 | `LlmPolicy.kt:33-68` | `RateLimited(code, retryAfterMs)`/`Server(code)` 可重试，`Reject(code)`（4xx 非限流，401/403/404 有专属文案，`42-49`）、`BadUrl`、`Network`、`Timeout`、`BadFormat`、`EmptyContent`、`Truncated(partialContent)`（`59-64`）。`retryable` 只放行 `RateLimited \| Server \| Network \| Timeout`（`66-67`） |
| `RateGate` | `LlmPolicy.kt:74-92` | 状态只有 `mutex` + `nextSlotMs`。`acquire` 在锁内「取号」：`slot = max(now, nextSlotMs)`，然后 `nextSlotMs = slot + minIntervalMs` 并**立即释放锁**，等待发生在锁外的 `sleeper.sleep`（`LlmPolicy.kt:81-90`）。并发请求拿到互不相同、严格相差一个间隔的时刻，退避中的请求不会把闸门锁死 |
| `GlobalGate` | `LlmPolicy.kt:95-97` | 进程级单例 `shared`，chat / listModels / 测试连接共用 |
| `LlmSession.Policy` | `LlmSession.kt:28-33` | `contextWindow`/`minIntervalMs`/`maxRetries`/`conservative` 四项；`window` 与 `intervalMs` 是它的派生属性（`LlmSession.kt:37-38`） |
| `Tokens.Fit(maxOut, system, user)` | `LlmPolicy.kt:213` | 预算结果：真正要发的 `max_tokens` 加可能被改写过的 system/user |
| `LlmPolicy` 常量表 | `LlmPolicy.kt:114-139` | `MAX_RETRIES=5`（即最坏 6 次发送）、`BASE_DELAY_MS=1000`、`MAX_DELAY_MS=60000`、`MIN_OUTPUT_TOKENS=512`、`MIN_INPUT_ROOM_TOKENS=256`、`DEFAULT_CONTEXT_WINDOW=131_072`、`LEGACY_DEFAULT_CONTEXT_WINDOW=16_384`（迁移标记用）、`DEFAULT_MAX_OUTPUT_TOKENS=16_384`、`REJECT_FALLBACK_OUTPUT_TOKENS=8_192`、`DEFAULT_MIN_INTERVAL_MS=1200`、私有 `JITTER=0.2` |

退避与抖动的确切算式：`base = min(1000 shl clamp(attempt,0,20), 60000)`，`factor = 1 - 0.2 + 0.4*rng()`，`delay = max(1, base*factor)`（`LlmPolicy.kt:152-156`）。`rng` 固定 0.5 时 factor 恰为 1.0，等待严格是 1s/2s/4s……——这正是单测能精确断言 `listOf(1000L, 2000L)` 的原因（`LlmPolicyTest.kt:103-109`）。

`Retry-After` 的解析规则：只认整数秒，先看响应头 `retry-after`，再用正则从响应体里找 `retry_after|retryAfter|reset|wait` 字段；≤0 或非数字视为未提供，交给指数退避；结果封顶 60s（`LlmPolicy.kt:142-149`）。

保守模式是两个纯函数：`windowFor` 窗口除以 2、`intervalFor` 间隔乘 2（`LlmPolicy.kt:159-163`），对应单测「间隔翻倍且窗口减半」（`LlmPolicyTest.kt:406-413`）。

`Tokens` 的估算与截断：CJK 一字一 token，其余整段字符按 4 字符一 token 向上取整（`LlmPolicy.kt:169-177`），CJK 判定覆盖六个码段（`LlmPolicy.kt:207-211`）。`truncateToTokens` 先扣掉尾标 `…（内容过长，已截断）` 自身占的 token，再尽量收在强边界（`。！？；!?;` 与换行，`LlmPolicy.kt:179-205`）；找不到边界就按 token 数硬切。

`Tokens.fit` 的四级降级顺序，注释就写在函数头上（`LlmPolicy.kt:217`）：① 输入装得下但输出余量不足 → 先压输出（`LlmPolicy.kt:226-228`）；② 连最小输入余量都不够 → 判定 system 本身过长，缩 system 到窗口的 1/4（`LlmPolicy.kt:229-232`）；③ 缩完 system 把腾出的余量还给输出（`LlmPolicy.kt:233-234`）；④ 最后才截输入（`LlmPolicy.kt:236-238`）。三条地板：`MinOut=512`、`MinRoom=256`、`MinWindow=1280`（`LlmPolicy.kt:241-243`，`MinWindow = MinRoom + MinOut*2`），保证「绝不因为放不下而打断一个本来能发的请求」。

## 已知坑与约束

**注释与实现的矛盾**

1. `Transport.post` 的 KDoc 说「只允许抛 `LlmError`，其余异常由 `LlmSession` 兜底归类为**不可重试**」（`LlmPolicy.kt:10-11`），但实现把漏出的意外异常包成 `LlmError.Network()`，而 Network 是**可重试**的（`LlmSession.kt:150-153`，该处注释自己写的是「按可重试的网络错误处理」）。两处注释互相矛盾，以实现为准：意外异常会吃掉一次重试额度。索引 README「已知缺陷」第 4 条记录的就是它。
2. **流被掐断也算网络错误**：SSE 收完但既没有 `finish_reason` 也没有 `[DONE]`，按可重试 `Network` 走退避（`LlmSession.kt:210-211`）——渠道半路断流会静默重打一次，语义上合理但排障时要知道「这次失败不是真的 5xx」。

**取消与文案**

3. 取消必须能穿过去：`CancellationException` 在 `call()` 里先单独 rethrow（`LlmSession.kt:146-147`），`Generator` 外层也再兜一次（`Generator.kt:82`、`152-153`）——否则用户离开页面时的取消会被当成网络错误继续退避重打。
4. 错误文案不带响应体：`LlmError` 的类注释明确「原始响应体一概不进错误信息（里面有回显的用户内容）」（`LlmPolicy.kt:31`）。KEY 只进 header，永不进请求体（`LlmSession.kt:170-172`）。对应断言在 `LlmPolicyTest.kt:231-235`（不泄漏 `SECRET-KEY-VALUE`）与 `LlmPolicyTest.kt:429-430`（文案必须中文、不含 `Exception`）。

**降级链的顺序与额度**

5. `Retry-After` 取 max 而不是覆盖：`maxOf(err.retryAfterMs ?: 0, backoffMs(...))`（`LlmSession.kt:158`），配套用例 `b3`（`LlmPolicyTest.kt:125-131`）。
6. 退避不占闸口：`f3` 用例专门断言「第 2 次发送应在退避结束时（1000）；若退避还占了闸口会变成 2000」（`LlmPolicyTest.kt:328-338`）。改 `RateGate` 时这条是最容易被破坏的不变量。
7. **一次最坏的请求最多打 1+1+1+1+1 次接口**：`chatWithDegrade` 里「400 硬帽降档」「截断加码」「退回原预算」三条路径各只重问一次，且互斥（抛 `Reject` 就不会再走 `Truncated` 分支）；重试额度（5 次）只在 `call()` 内部对同一请求生效。排障时把「为什么发了这么多请求」数清楚要按「call 内重试 × 降级重问」两层相乘。
8. **`finish_reason == "length"` 优先于内容判空**：流式与 JSON 两条解析路径都一样——有正文也算 `Truncated` 且**带回正文**（`LlmSession.kt:205-209`、`230-235`），空正文 + length 抛不带部分的 `Truncated`。

**模型列表**

9. `listModels` 失败有两种形态：**HTTP 层 4xx/5xx 会抛**（`Reject` 被翻译成人话「这个渠道可能不提供模型列表，直接手动输入模型名即可」，`LlmSession.kt:129-132`，用例 `m5` 锁定）；**响应形状不认识则静默返回空列表**（`parseModelIds` 全线 catch，`LlmSession.kt:239-252`），字段名兼容 `data`/`models` 与 `id`/`name`。所以设置页「一个模型都没有」要先看是弹了错还是只是空。
10. **模型列表必须用 GET**（`LlmPolicy.kt:12-13` 注释 + `OkHttpTransport.get`，`LlmClient.kt:98-107`；用例 `m4`，`LlmPolicyTest.kt:549-572`）——旧版走 POST 时部分渠道回 405/404。

**地址与窗口**

11. `LlmEndpoint.normalize` 是纯字符串规则：缺协议补 `https://`（`LlmPolicy.kt:106`）、剥掉误填的 `/chat/completions`（`107`）、不以 `/v1` 结尾就补 `/v1`（`108`）。它不校验主机名也不处理查询串：填 `a.com/v1beta` 会得到 `a.com/v1beta/v1`（单测 `m` 的 `https://a.com/x/v1` 是保持不变的例子，`LlmPolicyTest.kt:520-532`）。带 `?key=` 形式的地址同理会退化。
12. `LlmSession.chat` 每次新建、无内部缓存，`RateGate` 才是唯一跨请求状态（`LlmClient.kt:58-74`、`LlmPolicy.kt:78-79`）。`GlobalGate.nextSlotMs` 只增不减，长时间空闲后的第一个请求不会额外等待（`LlmPolicy.kt:84` 用 `now > nextSlotMs` 复位）。
13. 窗口/间隔的 `coerceIn` 在 `LlmClient` 与 `Tokens.fit` 各有一道地板（`LlmClient.kt:66-70`；`LlmPolicy.kt:243` 的 `MinWindow=1280`），加上设置里最低只能填 2048（`SettingsRepository.kt:127`），真机上走不到「窗口小于 1280」的路径；单测里出现的 1024 窗口是人为构造。

**单测覆盖点（`LlmPolicyTest` 34 例 + `LlmSessionStreamTest` 8 例）**

- 退避与限流：`a` 严格 1s/2s（`:103`）、`d2` 用尽次数后抛限流（`:239`）、`d3` 网络抖动可重试（`:252`）、`d4` 408/425/5xx 重试而 400/404 不重试（`:269`）、`h` 退避封顶 60s 与 `Retry-After` 解析边界（`:394`）。
- `Retry-After`：`b` 头优先（`:111`）、`b2` 响应体里的秒数也认（`:118`）、`b3` 为 0 不得绕过退避（`:125`）。
- 截断与硬帽：`c` 带体量规则只重问一次（`:133`）、`c2` 连续截断抛 `Truncated`（`:146`）、**`c3` 截断重试会抬高输出上限**（`:157`）、**`c4` 两次都截断时异常带回较长的部分内容**（`:168`）、**`c5` 渠道硬帽回 400 自动退回 8192 档再问一次**（`:188`）、**`c6` 401 与输出帽无关仍快速失败**（`:204`）。
- 错误形状与文案：`d` 空内容/401 不重试且不泄漏 KEY（`:215`）、`k` 畸形响应收敛成中文错误（`:415`）、`m`/`m2` 地址规范化与非法地址（`:520`、`:534`）、`m4` 模型列表 GET 与 id 解析（`:549`）、`m5` 列表 404 翻译成人话（`:574`）、**`m6` 请求体不得携带 temperature**（`:593`）。
- 节流闸：`f`/`f2`/`f3`（`:308`、`:318`、`:328`）。
- token 预算：`e` 估算规则（`:287`）、`e2` 截断不超上限且收在边界（`:297`）、`g`/`g2`/`g3`/`g4` 超大输入与 system 压缩（`:340`、`:361`、`:371`、`:383`）。
- 保守模式：`i`（`:406`）。
- 跨层：`z` 分段与预算联动（`:437`）、`zz` 429 注入下整条链路仍出图（`:446`）、`zz2` KEY 不对时经真实客户端两段内停手（`:486`）。
- 流式（`LlmSessionStreamTest`）：`a` 逐块拼回（`:48`）、`a2` 请求体必须 stream true（`:58`）、`b` 思维链跳过（`:65`）、`c` length 截断带回部分内容（`:72`）、`d` 流中断按网络错误（`:83`）、`e` 只有 DONE 没内容按空内容（`:94`）、`f` 渠道忽略 stream 回整段 JSON 也能解（`:105`）、`g` keep-alive 注释行不影响（`:111`）。

## 最后更新

2026-10-04 · 按 v1.5.0 当前代码全量刷新（旧版为 2026-09-20 首次成文）。本次纠正的过期说法：①「非流式 stream=false」→ 现默认 `stream=true`，新增 SSE 解析与 `LlmSessionStreamTest` 8 例；②「输出上限 2048 / 窗口默认 16384」→ 16384 / 131072（老用户一次性迁移，`LEGACY_DEFAULT_CONTEXT_WINDOW`）；③新增 400 硬帽降档（8192 档）与「截断抬预算 2 倍重问、带回 partialContent 抢救」整条降级链；④「温度 0.4 默认值、chat 带 temperature」→ 请求体已不含 temperature（用例 m6）；⑤`Transport` 新增 `get`（模型列表改 GET）；⑥`listModels` 的 404 现在抛带人话的 `LlmException` 而非静默空列表；⑦全部行号按当前源码重新核对。

2026-09-20 · 补写模块总览（首次成文）
