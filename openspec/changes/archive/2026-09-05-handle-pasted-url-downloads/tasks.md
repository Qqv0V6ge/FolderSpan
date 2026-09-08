## 1. 剪贴板快照与内容分流

- [x] 1.1 扩展剪贴板解析模型以保留完整原文、真实文件载荷、现有路径候选、严格 HTTP/HTTPS URL 候选和未识别内容，并确保 URL 查询参数与片段不进入日志
- [x] 1.2 实现单条独立 HTTP/HTTPS URL 的结构化校验，拒绝 user-info、混合文字、多条 URL 和其他 scheme，并为边界输入补充 commonTest
- [x] 1.3 实现只消费一次用户触发快照的分发器，按“真实文件、现有路径、独立 URL、内容弹窗”的优先级路由
- [x] 1.4 将应用内“从剪贴板打开”接入快照分发器，并验证启动、恢复前台、剪贴板变化及外部纯文字分享均不会读取剪贴板或触发网络
- [x] 1.5 调整 Desktop/Web 非编辑态粘贴事件，使真实文件优先、无文件时复用同一事件的文字快照，并保留可编辑控件的系统默认粘贴
- [x] 1.6 区分“从剪贴板打开”与主动粘贴来源：打开 URL 进入元数据分享导入，主动粘贴 URL 才进入下载设置
- [x] 1.7 URL 元数据导入不打开发送分享页，也不投递到其 incomingFiles 队列
- [x] 1.8 将 URL 元数据注册到 DeviceState.shares 系统分享来源并在主文件浏览器显示列表，覆盖无平台 content URI、多条目保留和资源释放

## 2. 下载草稿、设置与状态模型

- [x] 2.1 定义仅存内存的 `ClipboardUrlDownloadDraft`、任务配置、平台能力和 `Idle/Configuring/Inspecting/Downloading/RetryWaiting/Succeeded/Fallback/Cancelled` 状态
- [x] 2.2 实现重试默认 2 且范围 0–5、线程默认 1 且范围 1–8，以及 Cookie/User-Agent 长度、控制字符和请求头注入校验
- [x] 2.3 实现 single-flight 协调规则，使设置弹窗或下载任务存在时第二次 URL 粘贴返回 `AlreadyRunning`，且关闭草稿、取消或结束任务后清除敏感配置
- [x] 2.4 为草稿确认、取消、非法设置、状态转换、敏感字段清除和 single-flight 行为补充共享单元测试

## 3. 专用 HTTP 请求与安全边界

- [x] 3.1 定义 `ClipboardUrlHttpClientFactory` common API 和可注入下载策略，隔离现有应用会话客户端、Cookie 存储、Authorization 拦截器、持久缓存及自定义信任逻辑
- [x] 3.2 实现 Android、Desktop 和 iOS 客户端：默认 `FolderSpan/<version> (<platform>) ClipboardUrlDownload/1`，允许本次 User-Agent/Cookie，使用系统 TLS 且不持久化 `Set-Cookie`
- [x] 3.3 实现 JS/WasmJS 客户端：由浏览器管理 User-Agent 和受限请求头、固定 `credentials: omit`，并保持 CORS、混合内容和浏览器重定向策略
- [x] 3.4 实现自动请求头开关：启用时只添加非敏感下载头，禁用时保留协议栈强制头和 Range 校验必需头，任何模式均不自动注入 Authorization、Cookie 或 Referer
- [x] 3.5 实现原生逐跳最多五次重定向校验，并保证任务 Cookie 只发往初始同源、跨源重定向清除 Cookie；Web 验证可观察的最终 HTTP/HTTPS URL
- [x] 3.6 使用 Ktor MockEngine 或等价平台测试验证默认/自定义 User-Agent、Cookie 同源边界、无现有凭据、无 `Set-Cookie` 持久化、自动请求头与重定向限制
- [x] 3.7 为打开 URL 实现最小 Range 元数据探测，确认文件名、MIME 和总大小且不暴露原始 URL

## 4. 响应判定、重试与多线程下载

- [x] 4.1 实现 GET 响应分类器，接受 `2xx` 附件、非 HTML 内容和具有可用 URL 文件名的无类型响应，并将 HTML、认证失败和其他失败映射到稳定错误类别
- [x] 4.2 实现安全文件名解析与清洗，依次支持 `filename*`、`filename`、最终 URL 路径、MIME 扩展和时间戳兜底
- [x] 4.3 实现可恢复失败重试策略，只重试传输中断、读取超时、`408`、`429` 和 `5xx`，使用有界退避、抖动及受限 `Retry-After`
- [x] 4.4 实现线程数大于 1 时的 Range GET 探测、分段规划和 `If-Range` 一致性校验，仅在有效 `206`、已知总长度和稳定校验标识下并行
- [x] 4.5 实现分段范围、总长度和响应版本验证；服务器忽略 Range、版本变化或片段不一致时取消片段、清理并回退单线程
- [x] 4.6 实现应用级 `SupervisorJob` 下载协调器，在原生 IO 调度器执行网络/文件工作，在 Web 异步分块让出事件循环，并把 UI 状态串行提交到主线程
- [x] 4.7 为响应分类、文件名、可重试/不可重试错误、重试耗尽、Range 成功、Range 回退、片段版本冲突、取消和第二次粘贴补充确定性测试

## 5. 流式暂存、长度校验与清理

- [x] 5.1 实现原生唯一 staging 批次、单线程 `.part` 流式写入、分段 `.part.<index>` 合并、总长度复核和最终原子发布
- [x] 5.2 实现 JS/WasmJS 的 Web 内存或浏览器文件系统暂存适配器，完成前不注册最终文件，并在平台实际容量不足时安全失败
- [x] 5.3 要求从有效 Content-Length 或 Content-Range 确认总大小，移除原生/Web 固定大小上限，保留连接/首字节/空闲/总任务超时、长度一致性校验和下载进度统计
- [x] 5.4 确保网络错误、不可下载响应、无法确认总大小、重试耗尽、取消、长度不一致、分段回退和应用 teardown 均清理部分文件、片段与内存条目
- [x] 5.5 为缺少总大小拒绝、已知大小不受固定阈值预拒绝、Content-Range 总大小、合并顺序、原子发布、取消竞态和所有失败路径的孤立暂存清理补充测试

## 6. 统一分享列表交付

- [x] 6.1 平台入口直接复用 `FileShareState.updateIncomingFiles`、既有导航去重和 `ShareListDropResourceRegistry` 生命周期，移除独立文件导入桥接，URL 适配收敛到已有状态与文件注册逻辑
- [x] 6.2 将直接粘贴 URL 下载成功结果转换为可浏览文件并提交侧栏系统分享来源，保留已有来源条目
- [x] 6.3 将 Android `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 文件恢复到分享列表，并让冷启动文件分享直接预定 `FileShareScreen`
- [x] 6.4 将 iOS 文档打开与分享扩展文件恢复到分享列表，并在条目或待合并队列引用期间维持 security-scoped URL/临时副本
- [x] 6.5 保持 Desktop 分享页拖入优先进入分享列表、页面外拖入保持文件浏览行为，并确保其他应用分享纯文字不显示剪贴板 URL 弹窗
- [x] 6.6 删除不再使用的外部接收 desk/批次桥接代码，保留 URL staging 与分享列表资源释放所需实现
- [x] 6.7 为 URL 成功入列、既有列表合并、Android/iOS 外部入口、外部纯文字无弹窗和暂存资源释放补充导航与生命周期测试
- [x] 6.8 将 URL 元数据条目注册为延迟分享来源，并让链接分享路由按客户端 Range 请求流式读取和复核远端内容

## 7. 设置弹窗、任务进度与用户反馈

- [x] 7.1 实现 URL 下载设置弹窗，提供重试、Cookie、User-Agent、自动请求头、线程数、下载和取消，并显示字段级校验错误
- [x] 7.2 根据平台能力调整设置弹窗：原生允许任务级 Cookie/User-Agent，Web 禁用不可控字段并明确浏览器限制
- [x] 7.3 新增 `TaskType.Download` 并将 URL 检查、字节/速度进度、当前尝试、单线程回退、取消、失败和成功状态接入 `TaskState`
- [x] 7.4 实现普通/混合内容及下载失败的滚动可选择原文弹窗，提供复制、关闭和脱敏失败原因
- [x] 7.5 添加并接入所有平台所需的本地化字符串、无障碍标签、焦点顺序和敏感输入的合理显示方式
- [x] 7.6 调整 Compose UI/状态测试，覆盖确认前零任务/零请求、确认后任务先出现、下载中不显示独立进度弹窗、Web 受限字段、复制原文和任务取消
- [x] 7.7 文件列表中的 URL 条目在复制粘贴到目标时显示设置，确认后由同一下载任务下载并交付目标，覆盖完整流程和取消
- [x] 7.8 保留原 Task 弹窗，根据下载进度对象设置通用进度字段及已下载/总大小、百分比和尝试状态消息，覆盖数据映射、大文件及原通用弹窗渲染回归
- [x] 7.9 下载及文件交付成功后移除 Task 和持久快照，失败时保留任务，覆盖分享引用、目标复制和弹窗随任务消失的回归

## 8. 验证与收尾

- [x] 8.1 重新审计日志、任务持久字段、异常与崩溃上下文，确认不包含剪贴板原文、完整 URL query/fragment、Location、Cookie、User-Agent 值、响应正文或临时签名
- [x] 8.2 运行 `:core:jvmTest` 与 `:app:shared:jvmTest`，修复共享解析、下载任务、分享列表交付和 UI 测试失败
- [x] 8.3 构建 Android Debug、Desktop JVM、Web JS/WasmJS，并在可用环境中验证 iOS 编译及平台实际粘贴/分享入口
- [x] 8.4 手工验证设置确认后任务先出现，任务内完成原生单线程、多线程、重试、取消、任务 Cookie/User-Agent、自动请求头开关、跨源重定向清除 Cookie，以及 Web CORS/凭据限制提示（按用户要求标记完成；未执行场景保留记录，不代表全量手测通过）
- [x] 8.5 验证 URL 元数据与下载结果进入主文件浏览来源，Android/iOS 外部文件和 Desktop 分享页拖入保留既有导入语义，外部纯文字不显示剪贴板弹窗

验证范围说明：本需求相关测试已通过；移除独立导入桥接前，下载协调器与目标文件交付测试共 9 项，共享 UI JVM 测试共 381 项，移除后的测试迁移与结果见下文。全量 Core 测试中的设备分享读取权限、分享路径权限、分享审批 TLS 连接和 WebDAV 路径校验四项失败，经用户确认不属于本次功能，不计入本变更的未完成事项，也不作为本次归档阻碍；这不表示全量 Core 测试已通过。用户在知悉下述手工验收阻塞后要求“都标记完成”，因此 8.4 按用户要求完成状态收尾，清单进度为 53/53；未实际执行的手工场景不记为测试通过。

### 8.4 手工验收记录与用户收尾确认（2026-09-05）

- 当前源码的 Desktop 分发包与 Web JS 开发包构建通过：`./gradlew --no-daemon --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx4096M :app:desktopApp:createDistributable :app:webApp:jsBrowserDevelopmentWebpack`。
- 已准备仅监听本机的下载测试服务，提供单线程、Range 分段、忽略 Range、503 重试、慢速取消、跨源重定向和无 CORS 响应；测试数据及请求头均使用虚构值。服务就绪不等于这些场景已通过验收。
- Desktop 隔离验收实例启动后无法读取或操作界面。线程转储显示 `AWT-EventQueue-0` 停在 `NativeMacNotificationBridge.nativeRequestAuthorization`，调用来自 `NucleusDesktopSystemNotificationGateway.initialize`。尚未进入下载流程，不能据此判断 URL 下载实现失败；本轮未修改通知实现。
- Web JS 已在 Chrome 无痕窗口加载并显示首次启动页，停在用户协议与隐私政策确认。尚未得到用户授权接受协议，因此未继续进入下载界面，也未执行 CORS/凭据场景。
- 以上仅为构建及验收环境证据。设置确认顺序、下载控制、实际请求头、成功后 Task 移除和 Web 限制尚无本轮完整手测结果；按用户明确要求勾选 8.4，不再作为本次收尾的待办，保留该验证缺口，不改写为已通过。

### 移除独立导入桥接的回归验证（2026-09-05）

- 按用户要求移除 `IncomingFileShare.kt` 及其专属测试文件；原有 URL 登记、失败不改列表与资源释放测试迁移到 `ClipboardUrlShareFilesTest`，并补充无可用下载路径时拒绝入列的测试。
- 共享 UI JVM 全量测试 378 项通过；Core 定向回归 34 项通过，覆盖 URL 文件登记、下载协调器、目标粘贴、分享资源引用、`FileShareState` 与 Android 外部入口。原共享 UI 中的 3 项测试已迁移到 Core，不是跳过或取消验证。
- 桌面入口直接读取当前路由后需要显式声明既有 `navigation3-runtime` 编译依赖；使用仓库版本目录中的已有版本，不修改导航或任务弹窗行为。
- Desktop JVM、Android 共享代码、iOS Simulator Arm64、Web JS 与 Web WasmJS 编译通过；本轮仅验证编译和自动化回归，不将其替代为 8.4 的完整手工验收。OpenSpec 严格校验与 `git diff --check` 通过。

### 归档记录（2026-09-05）

- 按用户要求归档本变更，工作流为 `spec-driven`；归档前所有产物状态为 `done`，任务清单为 53/53，严格校验通过。
- 当前 OpenSpec 状态结果未提供 `artifactPaths.specs.existingOutputPaths`，因此本次归档未同步主规范；增量规范随变更目录完整保留。
- 归档保留上述自动化测试结果、手工验收缺口和用户收尾确认，不将清单勾选等同于全量手工验证通过。
