## Why

当前 URL 与剪贴板文件入口没有区分“打开”和“粘贴”：从剪贴板打开 URL 也会先显示下载设置并把完整文件下载到本地。产品语义应与拖入文件或从其他应用接收文件一致——打开 URL 时只探测文件元数据并立即加入分享列表；只有用户明确执行粘贴时，才显示下载设置，确认后创建可追踪任务并完整下载文件。

## What Changes

- 用户选择“从剪贴板打开”且内容为独立 URL 时，以最小 Range 请求检查响应、文件名、MIME 和总大小，构造 `FileSimpleInfo` 后加入侧栏 `DeviceState.shares` 的系统分享来源，并在主文件浏览器中显示其文件列表；不进入发送文件用的 `FileShareScreen`，也不预先下载完整文件。
- 用户从该来源选中文件、复制并粘贴到目标目录时，才显示下载设置；确认后由同一 `TaskType.Download` 任务下载完整文件并写入选定目标，临时文件在交付结束后释放。
- Desktop/Web 文件浏览区域收到非编辑态 `Ctrl+V` / `Cmd+V` 时视为主动粘贴；只有该入口的独立 URL 显示一次性下载设置弹窗。其他应用分享纯文字继续走既有文字接收行为。
- 用户在设置弹窗确认后，系统先创建可见的下载任务，再由该任务访问 URL、检查状态码/内容类型/总大小和文件名；确认前保持零网络请求。
- URL 检查、重试、单线程/分段下载、速度与字节进度、失败和取消都进入现有 `TaskState`/任务列表，不再用独立的检查中、下载中或重试中弹窗承载进度。
- 保留任务级重试次数、Cookie、User-Agent、自动请求头和线程数配置；原生平台允许本次任务覆盖，Web 对浏览器禁止控制的字段显示受限状态。
- 下载客户端继续隔离应用已有 Cookie、Authorization 等会话凭据；任务 Cookie 只用于本次原生下载和初始同源目标，响应 `Set-Cookie` 不持久化，Web 使用 `credentials: omit`。
- 直接粘贴 URL 的下载结果注册到侧栏系统分享来源并显示文件列表；已打开 URL 文件条目的复制粘贴直接写入选定目标。两者均不打开发送文件用的 `FileShareScreen`。
- URL 元数据条目使用内存注册的匿名来源路径，主文件浏览器通过系统分享来源读取；关闭该分享来源或应用退出时释放注册。链接分享路由仍可按请求范围读取已授权的匿名来源。
- Android `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 文件和 iOS 文档/分享扩展文件保留既有平台导入行为；URL 来源单独接入侧栏系统分享文件列表，资源在对应来源或消费者仍持有时保持有效。
- 无法识别的主动粘贴内容或 URL 任务失败时，保留可滚动、可选择的原文弹窗并提供复制与关闭；日志与任务持久字段不记录完整 URL query/fragment、Cookie、User-Agent 或响应敏感信息。

## Capabilities

### New Capabilities

- `pasted-url-downloads`: 定义主动粘贴 HTTP/HTTPS URL 的设置确认、任务创建、响应检查、重试、任务级 Cookie/User-Agent、自动请求头、线程控制、进度、取消、暂存与分享列表交付。

### Modified Capabilities

- `open-clipboard-items`: 区分“打开”和“粘贴”来源；打开 URL 只探测元数据并加入分享列表，主动粘贴 URL 才进入下载设置与任务流程。
- `paste-clipboard-files`: Desktop/Web 非编辑态粘贴在没有真实文件载荷时转交同一次事件的文字快照，同时避免把文本 URL 当作当前目录文件复制任务。
- `share-list-drop-import`: 维持 Desktop 分享页拖入和 Android/iOS 外部文件导入的既有语义，明确 URL 来源使用主文件浏览列表及独立资源引用。

## Impact

- 影响共享剪贴板解析与抽屉入口、Desktop/Web 粘贴快捷键分发、URL 元数据探测与链接分享流式响应、URL 下载协调器、`TaskState` 任务展示，以及 Android/iOS 外部文件入口。
- 下载任务需要映射到任务类型、标题、图标、状态、字节进度、取消与失败结果；下载设置弹窗仍由剪贴板状态持有，但确认后的进度只在任务系统显示。
- 平台外部文件直接复用 `FileShareState.updateIncomingFiles`、既有导航及资源注册，不保留独立导入桥接；URL 元数据与直接下载结果使用 `ClipboardUrlShareFiles`，已打开条目的粘贴使用既有目标冲突规划与复制执行器。
- 不新增公开服务端 API；打开 URL 的元数据探测、分享时的延迟读取以及主动粘贴确认后的下载，都继续受系统 TLS、ATS、CORS、混合内容和明文传输策略约束。
