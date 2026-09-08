## 1. 依赖与持久化基础

- [x] 1.1 在 `core/commonMain` 建立基于 `kotlinx.serialization` 的 MCP/JSON-RPC 强类型协议模型与固定兼容测试向量，确认 Android、JVM、iOS 编译且不引入 Ktor Server 引擎。
- [x] 1.2 新增 SQLDelight MCP Token、Token Scope 和书签导入标记表，并为 `FileBookmark` 增加 `protocol`、`sourceId`、`createdAt`、`updatedAt` 字段。
- [x] 1.3 编写幂等数据库迁移，将旧书签保留原 ID/排序并归入 Local/空 sourceId，同时增加迁移与回滚兼容测试。
- [x] 1.4 实现 256 位 Token 生成、`fmcp_<lookupId>_<secret>` 解析、SHA-256 哈希、常量时间验证、一次性 Secret 结果及 Token/Scope CRUD repository。
- [x] 1.5 增加 MCP 服务设置模型，持久化启用状态、端口 `52137`、Origin allowlist，并确保 Token Secret 和 MCP 私有配置不进入 Pro 同步或日志。

## 2. 数据源作用域书签与 Share 写能力

- [x] 2.1 实现统一 `ScopedBookmarkRepository`，支持四类协议/实例的分页列表、创建、更新、删除、排序和来源可用状态。
- [x] 2.2 将抽屉、书签管理页面和文件导航改为使用 scoped repository，切换 Desk 时按 `{protocol, sourceId}` 加载且来源删除时默认保留书签。
- [x] 2.3 实现设备书签首次成功连接时的一次性导入与 marker，覆盖断线延迟导入、重复访问不重复导入和旧远端不回写测试。
- [x] 2.4 为 Share 连接/握手模型增加版本化 `read/upload/rename/delete` capability，并让旧对端缺省为只读。
- [x] 2.5 扩展 Share 服务端和客户端路由，分别实现授权的流式上传/创建、重命名、删除与逐项结果，并覆盖临时目标发布和失败清理。
- [x] 2.6 将 Share 菜单权限和文件能力动态映射到协商 capability，逐操作校验会话、授权根、隐藏规则和撤销后的最新授权快照。

## 3. 统一文件端点与跨源任务

- [x] 3.1 定义 `FileEndpointRef`、`FileLocator`、稳定 DTO、错误码和 `FileEndpointGateway`，实现不切换 UI Desk 的 sourceId 解析与路径规范化。
- [x] 3.2 实现 Local gateway 的列表、元数据、范围读取、重命名、创建、删除、流式读写，并复用现有符号链接边界检查。
- [x] 3.3 实现 Device 和 WebRTC-backed Device gateway，复用现有 path/file RPC、证书/批准状态和分块读取能力。
- [x] 3.4 实现 Network gateway，覆盖所有现有网络协议的列表、元数据、范围读取、重命名、删除及流式上传/下载。
- [x] 3.5 实现 Share gateway，连接新 capability 与写路由，并为旧只读 Share 返回稳定 `unsupported`/`permission_denied`。
- [x] 3.6 扩展复制协调器和任务提交接口，使 Local、Share、Device、Network 全部有向组合都可通过显式 source/target gateway 执行。
- [x] 3.7 实现 `error/skip/overwrite/rename` 无 UI 冲突策略映射，保证默认 `error` 和自动改名结果可预测。
- [x] 3.8 实现跨端有界 staging、磁盘空间预检、暂停/取消/检查点与 finally 清理，补齐 Device↔Share、Share↔Network、Share↔Share 等缺失矩阵。
- [x] 3.9 验证移动逐项复制成功后才删除源；将删除源失败保存为独立 retry stage，重试不得重复复制。
- [x] 3.10 实现四类 gateway 的文件范围读取，默认 256 KiB、最大 4 MiB，支持严格 UTF-8/base64 结果与 `hasMore`。

## 4. MCP 业务 Facade 与工具

- [x] 4.1 实现 `McpAutomationFacade`、不透明 cursor 分页、JSON Schema 参数校验和统一脱敏错误映射，避免工具处理器直接操作 Compose 状态。
- [x] 4.2 注册书签、收藏和最近工具，完成 Scope 映射、四数据源 locator 元数据解析、收藏置顶以及最近删除/清空。
- [x] 4.3 注册文件任务列表/详情/暂停/继续/取消/删除工具，复用 `TaskState` 并拒绝无效状态迁移。
- [x] 4.4 实现后台设备扫描 operation store；无 subnet 扫描全部活动 IPv4 子网，指定 CIDR 限制 4096 主机，并提供状态查询。
- [x] 4.5 注册 HTTP 设备列表/连接工具，只返回在线设备并按 connected/connecting/approval_required/discovered 分组，连接不得绕过证书和批准。
- [x] 4.6 注册 WebRTC 设备列表/连接工具，只使用当前已发现在线 peer，不增加网段扫描或房间管理。
- [x] 4.7 注册网络列表/连接工具，连接前执行根列表探测并对凭据错误脱敏。
- [x] 4.8 注册同步列表/手动运行工具，防止同一同步任务重复活动并立即返回接受状态。
- [x] 4.9 注册文件列表、元数据、范围读取、重命名、异步复制/移动/删除和链接/设备分享工具，逐项绑定 `files.read/write/share` Scope。

## 5. MCP 协议、鉴权与会话

- [x] 5.1 基于共享协议核心实现工具注册表和 `RawMcpStreamableHttpTransport`，支持 initialize、initialized、ping、tools/list、tools/call 与标准 JSON-RPC 错误。
- [x] 5.2 实现 `/mcp/stateless` POST-only 处理，覆盖版本协商、通知无响应和批量/单请求边界。
- [x] 5.3 实现 `/mcp` 有状态 POST、GET/SSE、DELETE，会话 30 分钟空闲过期、每 Token 32 会话上限和并发安全清理。
- [x] 5.4 在 HTTP 分发前实现 Bearer 鉴权、Token 启用/最后使用更新、Scope 过滤 `tools/list` 和 `tools/call` 二次强制授权。
- [x] 5.5 实现 1 MiB 请求体、连接/工具/SSE 容量限制，以及 Host/Origin allowlist 和 DNS-rebinding 防护。
- [x] 5.6 为所有协议响应添加正确 Content-Type、MCP 版本/会话响应头、401 Bearer challenge、405 和 SSE keepalive 行为。

## 6. 原生局域网服务器与生命周期

- [x] 6.1 抽取可复用的 Raw HTTP 解析/写入与 HTTP/TLS 前缀判定，使 MCP 监听器不复制设备业务 dispatcher。
- [x] 6.2 实现 JVM 与 Android `RawMcpHttpServer`，公共端口绑定所有接口、内部 HTTP/TLS 绑定回环，并支持同端口两种 scheme。
- [x] 6.3 实现 iOS POSIX `RawMcpHttpServer` 的同端口 HTTP/TLS 分流、SSE 流写入、停止与重启。
- [x] 6.4 实现 common expect/actual `McpHttpService` start/stop/restart/status，保证重复调用、端口占用、启动失败和设置变更可恢复。
- [x] 6.5 在 Android、Desktop、iOS 应用生命周期接入 MCP 自动启动/停止；JS/Wasm actual 返回明确不支持且不监听。
- [x] 6.6 枚举 loopback 与全部可广告 LAN 地址，生成两个端点的 HTTP/HTTPS URL 和 TLS 指纹，并验证多网卡结果去重。

## 7. Toolbox MCP 管理界面

- [x] 7.1 在 `ToolboxScreen` 增加 MCP 卡片并接入新的 `McpManageScreen` 路由，保持现有网格响应式布局。
- [x] 7.2 实现服务状态、启停/重启、端口校验、启动错误和 JS/Wasm 不支持状态的 Material 3 界面。
- [x] 7.3 实现局域网地址卡片，HTTPS 优先，支持复制有状态/无状态 URL 与 TLS 指纹，并持续显示明文 HTTP 风险提示。
- [x] 7.4 实现 Origin allowlist 编辑、去重、校验、保存与重启提示。
- [x] 7.5 实现 Token 列表、创建、改名、Scope 编辑、只读/操作员/管理员预设、启用/禁用、轮换和删除确认。
- [x] 7.6 实现创建/轮换后的单次 Secret 对话框与复制操作，关闭后不可再次查看，所有交互满足 44 dp 触控目标和无障碍描述。
- [x] 7.7 添加所需本地化字符串与共享 UI Widget 测试，覆盖窄屏单列、宽屏双列、深浅主题和动态色。

## 8. 验证与文档

- [x] 8.1 添加 MCP 协议契约测试，覆盖两个端点、两种协议版本、SSE/会话删除/过期、无效 JSON-RPC 和方法限制。
- [x] 8.2 添加安全测试，覆盖缺失/错误/禁用 Token、常量时间验证、Scope 隔离、Origin/Host 拒绝、请求上限和日志脱敏。
- [x] 8.3 添加业务工具测试，覆盖分页、收藏/最近、任务控制、全/单网段扫描、在线设备分组、WebRTC、网络连接和同步执行。
- [x] 8.4 添加四数据源文件矩阵测试，覆盖列表/元数据/范围读取、四种冲突策略、异步删除和两种分享。
- [x] 8.5 添加 Share 权限与移动安全测试，覆盖旧对端只读、能力撤销、upload 不隐含 delete、目标失败保留源和删除源失败单独重试。
- [x] 8.6 在 JVM 集成测试中从非回环地址验证 LAN HTTP/HTTPS，并为 Android/iOS 增加启动、端口冲突和前后台生命周期测试。
- [x] 8.7 运行 `:core:jvmTest`、`:app:shared:jvmTest`、Android debug 编译、Desktop 编译和可用的 iOS 编译检查，记录平台限制与结果。
- [x] 8.8 更新用户/开发文档与 `md_descriptions_paths.md`，说明端点、Token Scope、HTTPS 信任、明文风险、工具清单和 4 MiB 读取上限。
