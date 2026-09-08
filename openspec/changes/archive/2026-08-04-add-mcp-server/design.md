## Context

FolderSpan 是 Kotlin/Compose Multiplatform 应用。共享领域与数据逻辑位于 `core/commonMain`，原生设备 API 使用项目自有的 Raw HTTP/TLS 服务器；Android、Desktop 和 iOS 具备入站监听能力，JS/Wasm 没有。书签目前只覆盖本机与当前远程设备，Share 会话主要只读，文件复制协调器只实现了部分 Local/Share/Device/Network 组合。设备、网络、同步和文件任务已经各有状态与执行模型，MCP 必须复用这些模型，不能创建第二套业务状态。

本变更会新增局域网可访问的外部自动化入口，并允许持有 Token 的客户端执行破坏性文件操作。传输合规、鉴权边界、路径安全、后台任务生命周期和旧 Share 客户端兼容性因此是主要约束。

## Goals / Non-Goals

**Goals:**

- 在 Android、Desktop、iOS 提供标准有状态与无状态 Streamable HTTP MCP 服务，并允许受控局域网访问。
- 提供多 Token、可组合 Scope、一次性显示 Secret、哈希持久化和可审计的最近使用信息。
- 通过薄 MCP 适配层复用现有书签、收藏、最近、任务、设备、网络、同步和文件服务。
- 建立不依赖当前 UI Desk 的显式文件端点模型，补齐四类数据源的复制、移动、读取和 Share 可写能力。
- 保持现有任务的清单、检查点、暂停、取消、重试、进度和部分失败语义。

**Non-Goals:**

- 不提供 stdio MCP、MCP Resources、Prompts、OAuth 动态客户端注册或协议层实验性 `tasks/*`。
- 不在 JS/Wasm 中模拟入站 MCP 服务，也不新增云端中继。
- 不通过 MCP 创建/编辑网络配置、同步定义、WebRTC 信令房间、设备角色或 Token；这些仍由应用 UI 管理。
- 不允许一次 MCP 响应返回无界文件正文，也不绕过设备证书/人工批准、Share 授权或符号链接安全策略。

## Decisions

### 1. 独立 MCP 监听器，共享底层服务器组件

新增 `McpHttpService` 及原生 `RawMcpHttpServer`，默认端口 `52137`，与设备 API 端口和链接分享端口独立。服务器复用现有 HTTP/1.1 解析器、响应写入器、端口冲突通知和设备 TLS 身份，但拥有独立生命周期、容量限制和路由分发器。公共套接字绑定所有接口；Android、Desktop、iOS 都增加同端口 HTTP/TLS 协议探测，内部明文与 TLS 处理器只绑定回环地址。

选择独立监听器而不是把 `/mcp` 加到设备 API，是为了不改变 `http-socket-tls-transport` 已规定的设备 API 仅 HTTPS 边界，也允许单独启停、改端口和限流。备选方案“复用设备 API 端口”耦合了文件共享开关、访问密钥和 MCP Token，无法安全独立管理，因此不采用。

### 2. 两个标准端点，共享工具注册表

`/mcp` 实现有状态 Streamable HTTP：POST 处理 JSON-RPC，GET 建立 SSE，DELETE 销毁会话；`/mcp/stateless` 只接受 POST，每个请求独立处理。两者共享同一工具定义、Scope 校验和业务适配层。服务器接受 `2025-06-18`、`2025-11-25`，初始化时协商版本；会话以并发安全内存存储保存，30 分钟空闲过期，每 Token 最多 32 个。

在 `core/commonMain` 使用 `kotlinx.serialization` 实现最小、强类型的 MCP 协议核心，覆盖 JSON-RPC、能力、工具和 Streamable HTTP 所需模型，并连接 FolderSpan Raw HTTP 请求/响应；不引入 Ktor Server 引擎。协议兼容性通过 MCP 规范中的请求/响应样例、跨平台共享契约测试和固定 JSON 测试向量保证。官方 `io.modelcontextprotocol:kotlin-sdk-server:0.14.0` 仅提供 JVM、JS、Linux、macOS、Windows 与 Wasm variant，没有 Kotlin/Native iOS variant，因此不能作为 Android、Desktop、iOS 共享实现的依赖。备选的“仅在 JVM/Android 使用官方 SDK、iOS 单独实现”会造成平台语义分叉，也不采用。

### 3. 多 Token 哈希存储和逐工具 Scope

Token 格式为 `fmcp_<lookupId>_<secret>`；`lookupId` 是随机公开索引，`secret` 为 256 位随机值。数据库只保存 `lookupId`、SHA-256 Secret 哈希、名称、启用状态、创建/更新时间、最后使用时间；Scope 使用关联表保存。验证先按 lookup ID 查找，再常量时间比较哈希。完整 Token 只在创建/轮换结果中出现一次，日志、错误、同步快照和导出不得包含 Secret。

工具注册表为每个工具声明所需 Scope；`tools/list` 过滤无权工具，`tools/call` 再做强制校验，防止客户端缓存工具列表后越权。Token 与 MCP 服务配置只可在本机 UI 管理，不通过 MCP 自管理。选择细分 Scope 而非固定角色，是因为文件读取、扫描、连接和写入的风险不同；UI 提供只读、操作员、管理员预设，但最终存储仍是 Scope 集合。

### 4. 局域网与浏览器安全边界

界面枚举所有可用非回环地址并同时生成 HTTP/HTTPS URL，HTTPS 排在前面；明文 HTTP 始终显示 Token 可被同网段监听的警告。带 `Origin` 的请求只接受回环 Origin 或用户维护的 allowlist；同时验证 Host 属于回环、当前接口地址或显式允许主机，以降低 DNS rebinding。无 Origin 的原生 MCP 客户端仍须 Bearer Token。请求体限制 1 MiB，连接、SSE 会话与并发工具调用分别限额。

备选方案“只绑定回环”不满足局域网访问；“默认接受任意 Origin”会放大浏览器与 DNS 重绑定风险，因此不采用。

### 5. 业务工具通过 Facade 和显式 DTO 接入

新增 `McpAutomationFacade`，其工具处理器只负责参数解析、Scope 校验、调用 Facade 和将结果映射为稳定 DTO。Facade 依赖现有 Bookmark/Favorite/Recent/Task/Device/Network/Sync 服务，并新增显式查询方法，避免读取 Compose 快照列表时发生线程冲突。列表统一使用不透明 cursor，默认 50、最大 200；错误统一映射为稳定 code，不把本地化 UI 字符串当作协议契约。

设备扫描以后台 operation ID 返回：无 subnet 时根据实际网卡掩码扫描全部活动 IPv4 子网，指定 CIDR 最大 4096 主机；状态工具读取进度和结果。设备列表按 `connected`、`connecting`、`approval_required`、`discovered` 分组，只保留最近 Presence Monitor 确认在线的条目。WebRTC 只读取现有已发现 peer 并复用连接/批准流程，不新增房间和网段发现。

网络连接先用条目 ID 解析配置并执行根目录列表探测，成功后才进入 `connectedEntries`。同步运行只调用现有 `runNow`，立即返回接受状态。文件任务控制只委托 `TaskState`。

### 6. 统一文件端点与无 UI 上下文操作

定义 `FileEndpointRef(protocol, sourceId)`、`FileLocator(endpoint, path)` 和内部 `FileEndpointGateway`。Local、Device、Network、Share 分别实现 list/info/read/rename/create/delete/stream-read/stream-write 能力。`FileEndpointResolver` 通过 DeviceState、NetworkState 和 Share 会话表解析稳定 ID，不修改 `FileState.deskType`。

复制协调器增加接受显式 source/target gateway 的完整矩阵路由。能直接服务端复制时优先使用；否则使用现有分块流或有界本地 staging，临时文件必须在成功、失败、取消后清理。复制、移动、删除统一创建现有 Task/manifest；MCP 仅返回 task ID。冲突策略 DTO 为 `error|skip|overwrite|rename`，默认 `error`，并映射到现有 UI 的 Reserve/Replace/Jump 行为。

`folderspan_file_read` 使用 gateway 的范围读取：默认 256 KiB、最大 4 MiB；UTF-8 严格解码失败时要求改用 base64。响应只保留本次区间，不聚合整个文件。路径在端点原生分隔符下规范化，拒绝 `..` 越界、根重命名和不安全符号链接。

### 7. Share 可写协议采用显式能力协商

Share 握手/连接响应新增版本化 capability 集合：`read`、`upload`、`rename`、`delete`。字段缺失的旧对端视为只读。每个写路由同时检查会话身份、当前授权快照、允许根、隐藏文件规则和对应 capability；upload 不隐含 delete，移动源必须拥有 delete。

上传采用分块流和临时目标，支持时以原子 rename 发布；批量 rename/delete 返回与设备文件路由一致的逐项结果。Share 到/从其他端点无法直接流式互通时使用本地 staging，但任务清单、暂停、取消、检查点和清理仍由现有执行器管理。此设计避免把网页 LinkShare 的 `allowUpload` 误当作设备 Share 会话的完全写权限。

### 8. 四数据源书签本地统一存储

扩展 SQLDelight `FileBookmark`，增加 `protocol`、`sourceId`、`createdAt`、`updatedAt`，并新增 `BookmarkScopeImport` 记录设备一次性导入。迁移时现有行设为 Local/空 sourceId，保留 ID 和排序。设备第一次可用时导入远端书签并写入 marker；之后本地 scoped store 权威，不做远端双向同步。Network、Share 从空 scope 开始。

保留来源配置删除后的书签，列表标记 `sourceAvailable=false`，由用户显式清理。选择本地统一索引能离线管理并为四种协议提供一致 CRUD；继续把 Device 书签存远端会导致离线行为不一致，双向同步又需要额外冲突模型，均不采用。

### 9. Toolbox 管理界面沿用 Material 3

在 `ToolboxScreen` 增加 MCP 卡片，并新增共享管理屏：顶部状态/启动操作，服务配置，优先 HTTPS 的地址卡片，明文警告，Origin allowlist，以及 Token 列表。Token 编辑使用名称、Scope 分组和只读/操作员/管理员预设；创建/轮换成功使用不可跳过的单次 Secret 对话框和复制动作。所有图标按钮提供无障碍描述，触控目标不小于 44 dp；页面在窄屏单列、宽屏双列。Web 显示禁用说明但允许查看规范帮助。

## Risks / Trade-offs

- **[明文 HTTP 会泄露 Token 与路径]** → UI 优先展示 HTTPS、持续显示风险警告、支持随时禁用/轮换 Token，并在文档中要求不可信网络只使用 HTTPS。
- **[局域网监听扩大攻击面]** → 默认关闭 MCP；启用后仍要求 Bearer Token、Origin/Host 校验、请求大小限制、并发限制、会话上限和严格参数校验。
- **[自签名 HTTPS 影响客户端接入]** → 地址卡片同时展示 TLS 指纹并提供复制；客户端显式信任该设备证书，不回退到未提示的 HTTP。
- **[iOS 同端口协议探测实现复杂]** → 抽取现有 JVM/Android switching proxy 的协议判定为共享测试向量，在 iOS 用 POSIX socket 先读少量前缀后转交 HTTP/TLS 内部处理器。
- **[SSE 长连接占用容量]** → SSE 使用独立会话容量与写队列，空闲超时和 Token 会话上限避免耗尽普通工具调用容量。
- **[Share 写能力与旧客户端不一致]** → 采用缺省只读的显式版本化 capability；任何缺失或未知能力都拒绝写入。
- **[跨端 staging 消耗磁盘]** → 预检可用空间，使用任务专属临时目录、流式处理和 finally 清理，并把 staging 状态计入任务恢复数据。
- **[设备书签改为本地权威后不再跨设备同步]** → 只做一次性导入并在迁移 UI 中说明；远端书签接口继续保留给旧客户端，但新 scoped repository 不做隐式回写。
- **[Tool 响应或文件正文过大]** → 列表强制分页，文件读取强制范围与 4 MiB 上限，任务只返回摘要和可分页详情。

## Migration Plan

1. 先落地数据库迁移、Token/Scope 和 scoped bookmark repository；迁移必须幂等，并为旧数据库增加 Local scope 默认值。
2. 增加显式文件 gateway 与业务 Facade，在不启用 MCP 的情况下用单元测试覆盖全部端点矩阵和 Share capability。
3. 增加 MCP 协议层、原生监听器和服务生命周期；功能开关保持默认关闭。
4. 增加 Toolbox 管理 UI，并在内部测试中创建 Token、验证 LAN HTTP/HTTPS、会话/SSE和无状态调用。
5. 最后启用 Share 可写协商；旧对端继续只读。发布后可通过关闭 MCP 开关立即回滚外部访问，且不影响设备 API或文件 UI。
6. 若需代码回滚，新表和新增列保留不删除；旧版本会忽略额外表/列。已协商的 Share capability 因字段可选而自动回落为只读。

## Open Questions

无。协议端点、平台范围、鉴权颗粒度、书签权威源、文件读取上限、WebRTC 发现范围和 Share 写权限均已在本变更中确定。
