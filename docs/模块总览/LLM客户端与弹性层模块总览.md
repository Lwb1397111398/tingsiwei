# LLM 客户端与弹性层模块总览

## 模块职责

把用户手填的接口地址变成一个「打得出去、打不出去也知道为什么」的 OpenAI 兼容调用层：地址规范化 → 组装请求 → 进程级节流 → 分类错误 → 指数退避重试 → token 预算降级 → 输出解析，对外只交出「正文字符串」或「一句中文错误提示」。

## 运作流程

**从哪来**：两个真实入口。①生成流水线 `Generator` 在建流水线时把 `client.chat(...)` 包成 `Complete` 闭包（`Generator.kt:42-44`），`ask()` 也直调它（`Generator.kt:195`）；②设置页 `SettingsViewModel` 自己 `new` 了一个 `LlmClient(repo)`（`SettingsScreen.kt:297`），用 `listModels` 拉模型列表（`SettingsScreen.kt:361`）、用 `chat` 做「测试连接」（`SettingsScreen.kt:376`，system 传空串）。两者传进来的都是设置里原样存的 `llmUrl`，未经任何加工。

**怎么处理**：`LlmClient.chat`（`LlmClient.kt:35-54`）先整段切到 `Dispatchers.IO`（`LlmClient.kt:43`），再走 `endpoint()`：`LlmEndpoint.normalize` 规范化，结果为空串就直接抛 `LlmError.BadUrl()`（`LlmClient.kt:56-57`）。`session()` 每次调用都重读一遍设置并新建 `LlmSession`（`LlmClient.kt:59-75`），把 `llmContextWindow` 夹到 `MinContextWindow..MaxContextWindow`、`llmMinIntervalMs` 夹到 `0..MaxMinIntervalMs`（`LlmClient.kt:67-71`），所以设置改完下一句请求就生效，不依赖进程重启；但 `gate` 默认取 `GlobalGate.shared`（`LlmClient.kt:22`），闸门不因多处 new 客户端而失效（`LlmPolicy.kt:86-89`）。

进入 `LlmSession.chat`（`LlmSession.kt:40-76`）：`model.isBlank()` 先抛 `LlmException("请先在设置里选择模型")`（`LlmSession.kt:49`）→ `Tokens.fit(window, system, user, desiredOutTokens)` 做预算（`LlmSession.kt:50`）→ 手工拼 JSON：`model`/`temperature`/`max_tokens=fit.maxOut`/`stream=false`，`fit.system` 空白就不发 system 消息（`LlmSession.kt:51-66`）→ `call("$baseUrl/chat/completions", ...)`（`LlmSession.kt:67`）。

`call()` 是弹性核心（`LlmSession.kt:101-122`）：`gate.acquire(intervalMs) { transport.post(...) }`（`LlmSession.kt:105`）→ `classify(resp)` 把状态码翻成分级错误，非错误则原样返回（`LlmSession.kt:106`、`124-129`）。`CancellationException` 原样上抛，绝不混进重试（`LlmSession.kt:107-108`）；`LlmError` 与「其它意外异常」都当成一次 outcome 参与判断，后者统一降级成 `LlmError.Network()`（`LlmSession.kt:109-114`）；不可重试或次数用完就抛（`LlmSession.kt:117`）；否则 `sleep(maxOf(err.retryAfterMs ?: 0, backoffMs(attempt, rng)))` 后 `attempt++`——**退避与 Retry-After 取大**，防止渠道回 0/1 秒时变成疯狂重打（`LlmSession.kt:118-120`）。

成功后进 `parseContent`（`LlmSession.kt:135-156`）：解析层任何意外都归成 `BadFormat`，不把英文异常甩到界面上（`LlmSession.kt:68-75`）。外层 `chatWithDegrade`（`LlmSession.kt:79-92`）捕获 `Truncated`，把「体量要求」规则拼到 system 尾部再问**一次**，仍截断才把错误交给用户。

**产出什么**：`chat` 产出模型正文 `String`；`listModels` 产出 `List<String>` 模型 id（`LlmSession.kt:94-97`、`158-171`）。失败产出 `LlmError`/`LlmException`，其 `message` 本身就是中文提示，调用方直接展示（`LlmClient.kt:32-33` 注释即此约定）。

**影响谁**：`Generator` 把 message 写进 `notes.errorMsg`（`Generator.kt:83`、`139`）；`LongTextPipeline` 用 `retryable` 决定「这段跳过」还是「整篇停手」（`LongTextPipeline.kt:187-189`、`200-205`）；`Tokens.fit` 会**改写**用户输入（截断加尾标），这是导图内容与原文不完全一致的根因之一；`RateGate` 是全进程节奏的总闸，生成流水线、设置页测试连接、模型列表共用同一条队列。

## 入口文件清单

| 相对路径 | 行数 | 一句话职责 |
| --- | --- | --- |
| `app/src/main/java/com/tingsiwei/app/llm/LlmClient.kt` | 107 | 面向业务的门面：读设置 → 规范化地址 → 组装 `LlmSession`，并给出生产 `OkHttpTransport` |
| `app/src/main/java/com/tingsiwei/app/llm/LlmSession.kt` | 172 | 纯 Kotlin 会话：预算 → 节流 → 发送 → 分类 → 退避重试 → 解析，零 Android 依赖 |
| `app/src/main/java/com/tingsiwei/app/llm/LlmPolicy.kt` | 219 | 弹性层的全部零件：`Transport`/`Sleeper`/`ClockSource` 接缝、`LlmError` 分级、`RateGate`、`LlmEndpoint`、`LlmPolicy` 常量与退避、`Tokens` 预算 |
| `app/src/test/java/com/tingsiwei/app/LlmPolicyTest.kt` | 490 | 本模块 27 个 JVM 单测：429 退避、Retry-After、截断降级、节流闸、预算降级、错误文案不泄漏 KEY |

`LlmClient.kt:16` 另定义了与 `LlmError` 并列的 `LlmException`，专门装「非接口层」的业务错误文案（空导图、没选模型等）。

## 对外接口 / 被谁调用

| 对外符号 | 位置 | 被谁用 |
| --- | --- | --- |
| `LlmClient.chat(...)` | `LlmClient.kt:35-54` | `Generator` 的 `Complete` 闭包（`Generator.kt:43`）、`Generator.ask`（`Generator.kt:195`）、设置页测试连接（`SettingsScreen.kt:376`） |
| `LlmClient.listModels(...)` | `LlmClient.kt:27-29` | 设置页拉模型下拉（`SettingsScreen.kt:361`） |
| `LlmSession` | `LlmSession.kt:20` | 仅 `LlmClient.session()`（`LlmClient.kt:61`）与 `LlmPolicyTest`（`LlmPolicyTest.kt:73-79`）直接构造 |
| `LlmError`（含 `retryable`/`httpCode`/`retryAfterMs`） | `LlmPolicy.kt:31-60` | `LongTextPipeline.askOutline` 靠 `retryable` 分诊（`LongTextPipeline.kt:187-189`）；`Generator.friendly` 直接取 `message`（`Generator.kt:197-202`） |
| `Transport` / `Sleeper` / `ClockSource` | `LlmPolicy.kt:8-19` | 单测注入假实现（`LlmPolicyTest.kt:40-60`、`201`、`388-401`、`425-430`） |
| `RateGate` / `GlobalGate.shared` | `LlmPolicy.kt:66-89` | `LlmClient`/`LlmSession` 的默认 gate；单测用 `RateGate(time, time)` 替换（`LlmPolicyTest.kt:75`） |
| `LlmEndpoint.normalize` | `LlmPolicy.kt:92-103` | `LlmClient.endpoint`（`LlmClient.kt:56`）；因是纯函数才能被 JVM 单测直接覆盖（`LlmPolicyTest.kt:452-464`） |
| `LlmPolicy.*`（退避、`windowFor`、`intervalFor`） | `LlmPolicy.kt:105-139` | `LlmSession` 的 `window`/`intervalMs`（`LlmSession.kt:37-38`）、`LongTextPipeline.effectiveWindow`（`LongTextPipeline.kt:63`） |
| `Tokens`（`estimate`/`fit`/`truncateToTokens`） | `LlmPolicy.kt:141-219` | `LlmSession.chat`（`LlmSession.kt:50`）、`LongTextPipeline`/`TextChunker` 的全部体量判断（`LongTextPipeline.kt:89`、`TextChunker.kt:47`） |

`SettingsRepository` 反过来引用 `LlmPolicy` 的默认值（`SettingsRepository.kt:11`、`32-36`、`61-63`），是唯一的「llm 包被 data 包依赖」点。

## 依赖的上游模块

- **`data/SettingsRepository.kt`**：唯一的配置来源，取 `llmContextWindow`/`llmMinIntervalMs`/`llmConservative`（`LlmClient.kt:60`、`66-73`）。边界常量由它定义：`MinContextWindow = 2048`、`MaxContextWindow = 1_000_000`、`MaxMinIntervalMs = 60_000L`（`SettingsRepository.kt:97-101`）。
- **`llm/Prompts.kt`**：`LlmClient.chat` 固定把 `Prompts.trimRule()` 当降级规则传下去（`LlmClient.kt:52`），本模块不认识其它提示词。
- **OkHttp**：`OkHttpClient` 三档超时（连接 20s / 读 300s / 写 60s，`LlmClient.kt:81-85`），读超时给到 5 分钟是为非流式长输出留的（`stream=false`，`LlmSession.kt:55`）。
- **kotlinx-serialization-json**：只用 `Json` 的 DOM（`JsonObject`/`JsonArray`/`JsonPrimitive`）手工取字段，不定义 `@Serializable` 数据类（`LlmSession.kt:4-13`、`35`）。
- **kotlinx-coroutines**：`Mutex`/`withLock`（`LlmPolicy.kt:3-4`）、`delay`（`LlmPolicy.kt:21-25`）、`withContext(Dispatchers.IO)`（`LlmClient.kt:4-5`）。
- **无 Android 依赖**：`LlmSession.kt` 与 `LlmPolicy.kt` 全文不 import 任何 `android.*`，这是整层能跑纯 JVM 单测的前提（`LlmSession.kt:16-19` 注释；`LlmPolicyTest.kt` 零 Robolectric）。

## 关键数据结构

| 结构 | 位置 | 说明 |
| --- | --- | --- |
| `Transport.RawResp(code, body, headers)` | `LlmPolicy.kt:9` | 一次 HTTP 往来的最小三元组；`Transport.post` 是唯一方法（`LlmPolicy.kt:11`），头一节说明其抛异常契约 |
| `Sleeper` / `ClockSource` | `LlmPolicy.kt:14-15` | 等待与时钟的接缝；生产实现 `CoroutineSleeper`（`LlmPolicy.kt:21-25`）、`SystemClock`（`LlmPolicy.kt:17-19`） |
| `LlmError` 家族 | `LlmPolicy.kt:31-60` | `RateLimited(code, retryAfterMs)`/`Server(code)` 可重试，`Reject(code)`（4xx 非限流，401/403/404 有专属文案）、`BadUrl`、`Network`、`Timeout`、`BadFormat`、`EmptyContent`、`Truncated`。`retryable` 是个派生 getter，只放行 `RateLimited \| Server \| Network \| Timeout`（`LlmPolicy.kt:58-59`） |
| `RateGate` | `LlmPolicy.kt:66-84` | 状态只有 `mutex` + `nextSlotMs`。`acquire` 在锁内「取号」：`slot = max(now, nextSlotMs)`，然后 `nextSlotMs = slot + minIntervalMs` 并**立即释放锁**，等待（`slot - now`）发生在锁外的 `sleeper.sleep`（`LlmPolicy.kt:73-83`）。因此并发请求拿到互不相同、严格相差一个间隔的时刻，而退避中的请求不会把闸门锁死 |
| `GlobalGate` | `LlmPolicy.kt:87-89` | 进程级单例 `shared`，chat / listModels / 测试连接共用 |
| `LlmSession.Policy` | `LlmSession.kt:28-33` | `contextWindow`/`minIntervalMs`/`maxRetries`/`conservative` 四项；`window` 与 `intervalMs` 是它的派生属性（`LlmSession.kt:37-38`） |
| `Tokens.Fit(maxOut, system, user)` | `LlmPolicy.kt:188` | 预算结果：真正要发的 `max_tokens` 加可能被改写过的 system/user |
| `LlmPolicy` 常量表 | `LlmPolicy.kt:106-114` | `MAX_RETRIES=5`（即最坏 6 次发送）、`BASE_DELAY_MS=1000`、`MAX_DELAY_MS=60000`、`MIN_OUTPUT_TOKENS=512`、`MIN_INPUT_ROOM_TOKENS=256`、`DEFAULT_CONTEXT_WINDOW=16384`、`DEFAULT_MIN_INTERVAL_MS=1200`、`DEFAULT_MAX_OUTPUT_TOKENS=2048`、私有 `JITTER=0.2` |

退避与抖动的确切算式：`base = min(1000 shl clamp(attempt,0,20), 60000)`，`factor = 1 - 0.2 + 0.4*rng()`，`delay = max(1, base*factor)`（`LlmPolicy.kt:126-131`）。`rng` 固定 0.5 时 factor 恰为 1.0，等待严格是 1s/2s/4s……——这正是单测能精确断言 `listOf(1000L, 2000L)` 的原因（`LlmPolicyTest.kt:104`、`185`）。

`Retry-After` 的解析规则：只认整数秒，先看响应头，再用正则从响应体里找 `retry_after|retryAfter|reset|wait` 字段；≤0 或非数字视为未提供，交给指数退避；结果封顶 60s（`LlmPolicy.kt:116-124`）。

保守模式是两个纯函数：`windowFor` 窗口除以 2、`intervalFor` 间隔乘 2（`LlmPolicy.kt:133-138`），对应单测「间隔翻倍且窗口减半」（`LlmPolicyTest.kt:342-349`）。

`Tokens` 的估算与截断：CJK 一字一 token，其余**整段**字符按 4 字符一 token 向上取整（`LlmPolicy.kt:143-152`），CJK 判定覆盖六个码段（`LlmPolicy.kt:182-186`）。`truncateToTokens` 先扣掉尾标 `…（内容过长，已截断）` 自身占的 token，再尽量收在强边界（`。！？；!?;` 与换行，`LlmPolicy.kt:180`）；找不到边界就按 token 数硬切（`LlmPolicy.kt:157-178`）。

`Tokens.fit` 的四级降级顺序，注释就写在函数头上（`LlmPolicy.kt:190-193`）：① 输入装得下但输出余量不足 → 先压输出（`LlmPolicy.kt:201-203`）；② 连最小输入余量都不够 → 判定 system 本身过长，缩 system 到窗口的 1/4（`LlmPolicy.kt:204-207`）；③ 缩完 system 把腾出的余量还给输出（`LlmPolicy.kt:208-209`）；④ 最后才截输入（`LlmPolicy.kt:211-213`）。三条地板：`MinOut=512`、`MinRoom=256`、`MinWindow=1280`（`LlmPolicy.kt:216-218`），保证「绝不因为放不下而打断一个本来能发的请求」。

## 已知坑与约束

**与索引 README 不一致的地方**

1. **错误类名**：索引 README「LLM 客户端」行写的是 `LlmCallError` 分级，代码里的类叫 `LlmError`（`LlmPolicy.kt:31`），同文件另有语义不同的 `LlmException`（`LlmClient.kt:16`）。全工程搜不到 `LlmCallError` 这个标识符。
2. **四级降级顺序**：README 写「压输出→截输入→缩 system」，代码实际顺序是 **压输出 → 缩 system → 重算输出余量 → 截输入**（`LlmPolicy.kt:192` 注释与 `LlmPolicy.kt:201-213` 实现）。顺序写反会误导排查——截输入是最后一步，不是第二步。
3. README 其余说法（自动补 `/v1`、`/models`、`Transport` 接缝、取号即放锁、退避不占位、认 `Retry-After`、CJK 一字一 token）与代码一致。

**注释里写明的历史坑**

4. `Transport.post` 的 KDoc 说「只允许抛 `LlmError`，其余异常由 `LlmSession` 兜底归类为**不可重试**」（`LlmPolicy.kt:10-11`），但实现把漏出的意外异常包成 `LlmError.Network()`，而 Network 是**可重试**的（`LlmSession.kt:111-114`，该处注释自己写的是「按可重试的网络错误处理」）。两处注释互相矛盾，以实现为准：意外异常会吃掉一次重试额度。
5. 取消必须能穿过去：`CancellationException` 在 `call()` 里先单独 rethrow（`LlmSession.kt:107-108`），`Generator` 外层也再兜一次（`Generator.kt:82`、`134`）——否则用户切页面时的取消会被当成网络错误继续退避重打。
6. 错误文案不带响应体：`LlmError` 的类注释明确「原始响应体一概不进错误信息（里面有回显的用户内容）」（`LlmPolicy.kt:29-30`）。KEY 只进 header，永不进请求体（`LlmSession.kt:131-133`）。对应断言在 `LlmPolicyTest.kt:171-174`（不泄漏 `SECRET-KEY-VALUE`）与 `LlmPolicyTest.kt:366-367`（文案必须中文、不含 `Exception`）。
7. `Retry-After` 取 max 而不是覆盖：`maxOf(err.retryAfterMs ?: 0, backoffMs(...))`（`LlmSession.kt:119`），注释写明是为了「渠道回 0/1 秒时变成疯狂重打」；配套用例 `b3`（`LlmPolicyTest.kt:121-127`）。
8. 退避不占闸口：`f3` 用例专门断言「第 2 次发送应在退避结束时（1000）；若退避还占了闸口会变成 2000」（`LlmPolicyTest.kt:264-274`）。改 `RateGate` 时这条是最容易被破坏的不变量。
9. 解析层「个别兼容接口把 content 返回成分段数组」是显式兼容分支，不是过度设计（`LlmSession.kt:144-147`）；`choices` 为空数组/缺失时判为 `EmptyContent` 而非 `BadFormat`（`LlmSession.kt:138-139`）。
10. `finish_reason == "length"` 优先于内容判空：有正文也算 `Truncated`（`LlmSession.kt:154`），空正文 + length 同样算 `Truncated`（`LlmPolicy.kt:56` 文案指向「开保守模式或调小上下文窗口」）。
11. 状态码归类里 `408`/`425` 被当服务端错误处理（可重试），和 5xx 同分支（`LlmSession.kt:127`），其余 4xx 一律 `Reject` 不重试（`LlmSession.kt:128`）。
12. `listModels` 的解析失败**不抛错**：任何异常都返回空列表（`LlmSession.kt:169-171`），字段名兼容 `data`/`models` 与 `id`/`name`（`LlmSession.kt:160-167`）。所以设置页「一个模型都没有」有两种成因——HTTP 层错误（会抛，见 `LlmSession.kt:95`）与响应形状不认识（静默空列表）。
13. `LlmEndpoint.normalize` 是纯字符串规则：缺协议补 `https://`（`LlmPolicy.kt:98`）、剥掉误填的 `/chat/completions`（`LlmPolicy.kt:99`）、不以 `/v1` 结尾就补 `/v1`（`LlmPolicy.kt:100`）。它不校验主机名也不处理查询串，按此规则推得：填 `a.com/v1beta` 会得到 `a.com/v1beta/v1`（单测 `m` 里的 `https://a.com/x/v1` 是保持不变的例子，`LlmPolicyTest.kt:457`）。带 `?key=` 形式的地址同理会退化。
14. `LlmSession.chat` 每次新建、无内部缓存，`RateGate` 才是唯一跨请求状态（`LlmClient.kt:59-75`、`LlmPolicy.kt:70-71`）。反过来：`GlobalGate.nextSlotMs` 只增不减，长时间空闲后的第一个请求不会额外等待（`LlmPolicy.kt:76` 用 `now > nextSlotMs` 复位）。
15. 窗口/间隔的 `coerceIn` 在 `LlmClient` 与 `Tokens.fit` 各有一道地板（`LlmClient.kt:67-71`；`LlmPolicy.kt:195` 的 `MinWindow=1280`），加上设置里最低只能填 2048（`SettingsRepository.kt:98`），真机上走不到「窗口小于 1280」的路径；单测里出现的 1024 窗口是人为构造（`LlmPolicyTest.kt` 之外的 `LongTextPipelineTest.kt:214`）。

**单测覆盖点（`LlmPolicyTest`，27 例）**

- 退避与限流：`a` 严格 1s/2s（`:99-105`）、`d2` 用尽次数后抛限流且等待 1s/2s/4s（`:177-188`）、`d3` 网络抖动可重试（`:190-203`）、`d4` 408/425/5xx 重试而 400/404/451 不重试（`:205-221`）、`h` 退避封顶 60s 与 `Retry-After` 解析边界（`:330-340`）。
- `Retry-After`：`b` 头优先（`:107-112`）、`b2` 响应体里的秒数也认（`:114-119`）、`b3` 为 0 不得绕过退避（`:121-127`）。
- 截断降级：`c` 带体量规则只重问一次且保留原 system（`:129-140`）、`c2` 连续截断抛 `Truncated`（`:142-151`）。
- 错误形状与文案：`d` 空内容/401 不重试且不泄漏 KEY（`:153-175`）、`k` 五种畸形响应全部收敛成不可重试的中文错误（`:351-371`）、`m`/`m2` 地址规范化与非法地址不重试（`:452-479`）。
- 节流闸：`f` 相邻请求间隔不小于 `minIntervalMs`（`:244-252`）、`f2` 连续取号互不重复且严格递增一个间隔（`:254-262`）、`f3` 退避不占闸口（`:264-274`）。
- token 预算：`e` 估算规则（`:223-231`）、`e2` 截断不超上限且收在边界（`:233-242`）、`g`/`g2` 中文与纯英文超大输入仍发出请求（`:276-305`）、`g3` revise 级大输入不被打断（`:307-317`）、`g4` system 超长时压缩并把余量还给输出（`:319-328`）。
- 保守模式：`i` 间隔翻倍、窗口减半（`:342-349`）。
- 跨层：`z` 分段与预算联动（`:373-380`）、`zz` 接真实会话层在 429 注入下整条链路仍出图（`:382-418`）、`zz2` KEY 不对时经真实客户端也只在两段内停手（`:420-450`）。

## 最后更新

2026-09-20 · 补写模块总览（首次成文）
