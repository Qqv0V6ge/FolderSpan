## Context

见 `proposal.md`。当前实现已经具备严格 URL 解析、一次性下载设置、独立无凭据 HTTP 客户端、响应分类、重试、Range 分段、staging 和下载状态机，但交付与进度承载方向需要调整：URL 成功结果目前进入 `ExternalFileReceiveCoordinator` 的文件浏览 desk，检查/下载/重试进度由独立 Compose 弹窗显示。

FolderSpan 的“分享”包含两个不同上下文：`DeviceState.shares` 是侧栏中的可浏览文件来源，`FileShareState`/`FileShareScreen` 是向其他设备发送文件的页面。打开 URL 所需的是前者；URL 文件应能在主文件浏览器中被选择、复制并粘贴到目标。既有平台文件页面入口和其合并、授权同步规则不用于 URL 元数据导入。

现有 `TaskState` 提供任务持久快照、串行处理器、取消、状态、结果和运行时字节进度。主动粘贴 URL 应在用户确认后注册一个明确的下载任务，先由任务访问 URL 并检查响应，随后在同一任务中下载；确认前不创建任务、不联网。与此不同，“从剪贴板打开”URL 应立即执行最小元数据探测并生成可分享条目，不下载完整文件。

## Goals / Non-Goals

**Goals:**

- 一次明确的用户操作只消费一次剪贴板快照，真实文件、路径、独立 URL 与普通内容按稳定优先级分流，并保留“打开”或“粘贴”的入口来源。
- 从剪贴板打开独立 URL 时只探测元数据并加入分享列表；只有主动粘贴的独立 URL 显示下载设置弹窗。
- 用户确认后先创建 `TaskState` 下载任务，再由任务执行 URL 检查、重试、单线程或 Range 分段下载，并把进度与取消暴露给任务界面。
- URL 元数据和直接粘贴下载结果在系统分享来源中可见；从列表复制粘贴时使用既有目标冲突规划和复制执行器，保留平台文件入口的原有语义。
- 下载客户端与应用会话凭据完全隔离，日志和任务持久字段不泄露 URL query/fragment、Cookie、User-Agent 或响应敏感内容。

**Non-Goals:**

- 不实现通用浏览器、网页保存、HTML 资源抓取、表单登录、OAuth 或 JavaScript 渲染。
- 不实现并行多 URL、批量 URL、断点续传、应用重启后恢复 URL 下载或系统后台下载服务。
- 不把下载文件自动复制到当前文件浏览目录。
- 不新增第二套分享列表、合并弹窗或授权同步逻辑。
- 不为 HTTP 下载放宽 ATS、CORS、混合内容或 TLS 信任策略。

## Decisions

### 1. 单一分发器保留打开与粘贴来源

分发优先级保持为：

1. Desktop/Web paste event 携带真实文件或图片时交给剪贴板文件粘贴控制器；
2. 文字解析为存在的本地路径或 `file://` 路径时沿用目录打开与高亮；
3. 完整文字恰好是一条独立 HTTP/HTTPS URL 时，打开入口执行元数据探测，粘贴入口创建一次性设置 draft；
4. 其他主动粘贴文字进入可复制原文弹窗。

应用启动、恢复前台、剪贴板自行变化和外部分享文字都不读取剪贴板、不创建 URL 下载 draft。Android 外部纯文字恢复既有 `ShareHandler.handleSharedText`，不会借用剪贴板状态显示弹窗。

### 2. 打开 URL 只探测元数据并注册延迟分享来源

“从剪贴板打开”识别到独立 URL 后，使用与下载器相同的隔离 HTTP 客户端、重定向边界和响应分类规则发送 `Range: bytes=0-0` 探测。有效 `206 Content-Range` 的总长度或忽略 Range 时的有效 `Content-Length` 用于构造 `FileSimpleInfo`；文件名与 MIME 同样来自可信响应元数据。

这里的“分享列表”是侧栏 `DeviceState.shares` 对应的主文件浏览列表，不是发送文件页 `FileShareScreen` 的待合并队列。`ClipboardUrlShareFiles` 保留文件信息、原始 URL 与资源释放回调；文件标记为 `FileProtocol.Share`、`SYSTEM_SHARE_DESK_ID`。导入器注册系统分享来源并把 `FileState` 切到其根目录；同一来源已打开时直接刷新列表。系统分享根目录读取同时兼容平台 `content://` 文件和 URL 元数据条目。

从列表选择 URL 文件并粘贴到目标时，`FilePasteTaskExecutor` 先使用既有冲突规划保留目标路径与替换/跳过/保留两者选择，再交给 `ClipboardUrlDownloadCoordinator.downloadForCopy` 显示设置。确认前没有下载任务和完整文件请求；确认后同一个下载任务先下载 staging，再经既有复制执行器写入选定目标。该分支不调用发送分享页 presenter；取消或交付结束后释放 staging，多条 URL 顺序确认和处理。

条目的 `path` 是不包含原始主机、query 或 fragment 的进程内匿名路径。注册表只在内存保存实际 URL；链接分享路由识别已授权的匿名路径后，可按客户端请求的字节范围从原 URL 流式转发。探测阶段不发布响应体、不创建 Task、不下载完整文件。关闭系统分享来源或应用退出后，由 `ClipboardUrlShareFiles` 释放来源注册。

### 3. URL 设置弹窗只负责确认，不承载任务进度

设置保持重试次数 0–5、线程数 1–8、任务 Cookie、自定义 User-Agent 和自动请求头开关。原生允许合法的任务级 Cookie/User-Agent，Web 明确显示浏览器限制。

确认前不创建 HTTP 请求或任务。确认时把合法设置复制到内存任务配置并立即注册 `TaskType.Download`；设置 draft 随即离开配置态。`Inspecting`、`Downloading` 和 `RetryWaiting` 仍可作为协调器内部状态用于 single-flight 与失败回退，但 Compose host 不再为这些状态显示独立进度弹窗。

### 4. URL 下载使用 `TaskState` 的下载任务

`TaskType` 增加 `Download`，在任务列表中提供下载标题、图标、进度和失败/取消状态。下载成功且文件交付完成后，协调器通过 `TaskState.delete` 移除任务及其持久快照，不保留成功任务；复制到目标尚未完成时仍保留任务，下载或交付失败则保留失败信息。移除任务不释放已交给系统分享来源的文件。任务只持久化脱敏目标摘要，不持久化原始 URL、Cookie 或自定义 User-Agent；完整配置只存在协调器内存和任务处理器闭包中。

确认流程按以下顺序执行：

1. 构造 `TaskType.Download` + `StatusEnum.LOADING`，路径字段使用脱敏目标摘要；
2. `TaskState.addOrUpdate`，确保任务先对用户可见；
3. `TaskState.registerTaskHandler` 注册下载处理器；
4. 处理器调用现有下载引擎，首个 GET 完成重定向、状态、内容类型、总大小和文件名检查；
5. 检查通过后继续流式/分段下载，并把字节数、总大小、尝试次数和并发段数写入任务运行时进度；
6. 任务取消会取消处理器协程，下载引擎与 staging 通过协程取消清理未完成资源。

URL 任务进入现有任务队列，避免独立后台 Job 与任务取消状态分叉。URL 下载不支持暂停后恢复；任务界面对 Download 隐藏暂停/继续，只提供取消和结果查看。

保留原通用 Task 详情弹窗，不增加 Download 专用布局或分支。下载协调器根据 `ClipboardUrlDownloadProgress` 设置任务数据：将字节比例转换成 `progressCur`（0–100）并设置 `progressMax=100`，避免把 Long 字节数直接写入 Int 进度字段；在既有结果消息中写入“已下载 / 总大小”、一位小数百分比及尝试状态。速度、预计剩余时间和并发数继续使用既有运行时指标。尚未获得总大小的进度对象写入 `progressCur=0`，消息显示已接收大小及“总大小未知”，不生成百分比。原弹窗直接消费这些通用字段，普通复制、移动和删除任务不受影响。

### 5. 专用 HTTP 客户端和检查策略保持不变

下载继续使用独立 `ClipboardUrlHttpClientFactory`：系统 TLS、无 Cookie 存储、无 Authorization、无持久 cache，原生默认最小 FolderSpan User-Agent，Web `credentials: omit`。原生逐跳处理最多五次重定向，任务 Cookie 只向初始同源发送，跨源清除。

线程数为 1 时使用一次完整 GET；线程数大于 1 时用小范围 GET 探测，只有有效 `206`、已知总长度和稳定校验标识才分段。最终响应必须为 `2xx`、总大小明确，并具有 attachment、非 HTML 内容类型或可用 URL 文件名。检查失败时任务失败，不发布文件。

### 6. staging 完成后按触发来源交付

原生仍在唯一缓存批次内写 `<name>.part` 或分段文件，校验总长度后原子发布；Web 使用内存文件存储。成功产生 `ClipboardStagedDownload` 后转换为 `FileSimpleInfo`：名称、大小、内容类型、非目录、本地/平台可寻址路径。

直接粘贴 URL 的成功结果通过下载协调器的结果回调注册到 `ClipboardUrlShareFiles`，并由 `FileState.openSystemShareFiles` 打开系统分享来源的主文件列表；staging 随该来源关闭或应用退出而释放。从已打开 URL 文件发起的粘贴由协调器的交付回调在同一任务内复制到已规划目标，完成、失败和取消均释放 staging。

URL 元数据检查与文件登记收敛到 `ClipboardUrlShareFiles.inspectAndAdd`，主文件列表切换由已有 `FileState` 承担；不再保留 `IncomingFileShare.kt`、独立 URL importer/presenter 类或对应的 importer 注入接口。URL 元数据和 URL 下载交付不触发发送分享页导航。

### 7. Android/iOS 外部入口恢复分享列表语义

Android 文件 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 使用既有 URI 归一化后直接调用 `FileShareState.updateIncomingFiles`，存在有效条目且当前不在发送分享页时再请求打开 `FileShareScreen`；冷启动可预定 `FileShareScreen`。纯文字 `ACTION_SEND` 保持首页文字接收，不创建 URL draft 或原文弹窗。

iOS 文档打开和分享扩展文件读取元数据后直接更新 `FileShareState` 并按当前路由决定是否导航。security-scoped URL 与分享扩展临时副本的 release callback 注册到 `ShareListDropResourceRegistry`，直到分享列表或待合并队列不再引用；无有效条目时立即释放。

Desktop 分享页拖入继续由 `ShareListDropRegistry` 优先投递到 `FileShareState.updateIncomingFiles`；分享页不可见时保持文件浏览器原行为。

### 8. 失败反馈与敏感数据边界

主动粘贴 URL 的任务失败时更新任务失败状态，并可发布 `Fallback` 原文弹窗，方便用户复制原始 URL。外部分享文字不进入该 fallback。任务路径与结果只使用 scheme/host 等脱敏摘要和稳定错误类别；不得写入完整 URL、query/fragment、Location、Cookie、User-Agent、响应正文或 staging 签名。

## Risks / Trade-offs

- [URL 下载占用现有任务队列] → 任务获得一致的可见性和取消语义；本变更不引入第二套并发任务调度器。
- [URL 条目在分享时仍依赖远端可用性] → 加入列表前确认名称、类型与大小；实际传输时复核范围和总长度，远端变化会终止该次响应而不发送拼接内容。
- [下载任务不能暂停恢复] → Download 类型不提供暂停操作，取消会清理 staging；断点续传仍不在本变更范围。
- [临时来源提前释放] → URL 来源保持资源到侧栏来源关闭或应用退出；复制下载产生的 staging 保持到目标交付结束。
- [大文件耗尽磁盘或浏览器内存] → 必须先确认总大小，流式暂存并受平台容量、超时、取消和长度一致性约束。
- [任务持久化泄露粘贴 URL] → 仅持久化脱敏目标摘要，完整配置只存在处理器闭包且任务结束立即清除。
- [浏览器能力受限] → Web 继续受 CORS、混合内容、User-Agent 和凭据限制，不尝试绕过。

## Migration Plan

1. 先更新任务模型与协调器测试，证明确认前零任务/零请求、确认后任务先出现、进度写入任务、取消清理和失败状态。
2. 将 URL 元数据和直接下载结果注册到系统分享文件来源，接通根目录读取、重复打开刷新和目标粘贴任务。
3. 恢复 Android/iOS 外部文件分享列表路由与外部纯文字既有行为。
4. 移除 URL 检查中、下载中、重试中和取消终态的独立进度弹窗，只保留设置与主动粘贴失败回退弹窗。
5. 运行共享/core 测试、Android/Desktop/Web 构建、可用的 iOS 编译和 OpenSpec 严格校验。

回滚时可恢复协调器独立进度 UI 与旧交付 presenter；staging 没有持久业务数据，清理暂存根即可，不需要数据库迁移。
