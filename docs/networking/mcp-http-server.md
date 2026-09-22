# MCP HTTP 服务

FolderSpan 内置 MCP（Model Context Protocol）服务，让受信任的自动化客户端在 Token 授权范围内操作书签、收藏、最近记录、文件任务、设备、网络盘、同步任务和文件。入口位于“工具箱 → MCP”。默认只监听本机；需要时再打开「允许局域网连接」。

## 启用与访问

1. 在 MCP 管理页设置监听端口（默认 `52137`）。
2. 创建 Token 并选择最小必要 Scope。完整 Token 仅在创建或轮换后显示一次。
3. 启用服务，复制管理页展示的本机 URL（`127.0.0.1` / `localhost`）。
4. 只有需要同一网络中的其他设备连接时，才打开「允许局域网连接」。

服务只实现 MCP Streamable HTTP，不提供 stdio 或 WebSocket 传输。一个公共端口同时接受：

- 有状态端点：`https://127.0.0.1:52137/mcp` 或 `http://127.0.0.1:52137/mcp`
- 无状态端点：`https://127.0.0.1:52137/mcp/stateless` 或 `http://127.0.0.1:52137/mcp/stateless`

打开局域网后，管理页还会列出网卡 IP 对应的 URL。

所有请求必须带有：

```http
Authorization: Bearer fmcp_<lookupId>_<secret>
Content-Type: application/json
```

例如：

```bash
curl --insecure \
  -H 'Authorization: Bearer fmcp_xxx_xxx' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"example","version":"1"}}}' \
  https://127.0.0.1:52137/mcp
```

有状态客户端应保存响应中的 `Mcp-Session-Id`，后续 POST、GET/SSE 和 DELETE 请求均携带该请求头，并在初始化成功后发送 `notifications/initialized`。会话空闲 30 分钟过期，每个 Token 最多同时拥有 32 个会话。无状态端点仅接受 POST，不创建会话。

## HTTPS 与明文 HTTP

HTTPS 使用 FolderSpan 设备自签名证书。首次连接前应从 MCP 管理页复制 SHA-256 指纹并在客户端固定或人工核验；不应在生产自动化中无条件关闭证书校验。

本机 loopback（`127.0.0.1` / `localhost` / `::1`）可以同时使用 HTTPS 与明文 HTTP。打开「允许局域网连接」后，局域网只接受 HTTPS，管理页也只广告 `https://<lan-ip>` 端点，不会在局域网上以明文传输 Token、路径或文件内容。停用或轮换 Token 会立即阻止后续请求。

Android 17（API 37）及以上在打开「允许局域网连接」时会请求“本地网络”权限；iOS 首次访问局域网时会显示系统本地网络权限提示。仅本机监听不需要该权限。拒绝权限后需到系统设置重新授权。

## Token Scope

| Scope | 能力 |
| --- | --- |
| `bookmarks.read` / `bookmarks.write` | 按 Local、Share、Device、Network 实例读取或修改书签 |
| `favorites.read` / `favorites.write` | 列表、添加、移除和置顶收藏 |
| `recents.read` / `recents.write` | 列表、删除或清空最近记录 |
| `tasks.read` / `tasks.control` | 查看任务，暂停、继续、取消或删除任务 |
| `devices.read` / `devices.connect` | 查看在线 HTTP/WebRTC 设备、发起连接 |
| `networks.read` / `networks.connect` | 查看网络源并在根目录探测成功后连接 |
| `sync.read` / `sync.run` | 查看同步任务并手动执行空闲任务 |
| `files.read` / `files.write` / `files.share` | 列表、元数据、范围读取、直接写入、创建目录、重命名、复制、移动、删除和分享。可写链路分享（`folderspan_files_share_link` 的 `allowUpload=true`）属于 mutation，除 `files.share` + `files.read` 外还需要 `files.write`，并且宿主必须已打开网页上传 |

管理页提供只读、操作员和管理员预设，也可以逐项选择 Scope。`tools/list` 只返回 Token 可见的工具，`tools/call` 会再次校验 Scope。

## 工具清单

- 书签：`folderspan_bookmarks_list/create/update/delete`
- 收藏：`folderspan_favorites_list/add/remove/pin`
- 最近：`folderspan_recents_list/delete/clear`
- 任务：`folderspan_tasks_list/get/pause/resume/cancel/delete`
- HTTP 设备：`folderspan_devices_list`、`folderspan_device_connect`
- WebRTC 设备：`folderspan_webrtc_devices_list`、`folderspan_webrtc_device_connect`
- 网络：`folderspan_networks_list`、`folderspan_network_connect`
- 同步：`folderspan_sync_list`、`folderspan_sync_run`
- 文件：`folderspan_files_list`、`folderspan_file_info`、`folderspan_file_read`、`folderspan_file_write`、`folderspan_directory_create`、`folderspan_file_rename`、`folderspan_files_copy/move/delete`、`folderspan_files_share_link/device`

HTTP 与 WebRTC 设备工具仅处理当前已经发现的在线设备，不主动扫描网段或管理 WebRTC 房间。

## 文件定位与跨源操作

文件参数使用显式 locator，不会改变当前 UI 正在浏览的 Desk：

```json
{
  "protocol": "Network",
  "sourceId": "sftp:example",
  "path": "/documents/report.txt"
}
```

`protocol` 支持 `Local`、`Share`、`Device`、`Network`。Local 的 `sourceId` 必须为空，其他类型必须填写稳定的源 ID。四种源之间的全部有向组合均可复制或移动；冲突策略为 `error`（默认）、`skip`、`overwrite` 或 `rename`。移动仅在逐项复制成功后删除源；删除失败会作为独立阶段保留供重试。

`folderspan_file_read` 默认读取 256 KiB，单次最多 4 MiB，可选择严格 UTF-8 或 base64，并返回 `offset`、总大小和 `hasMore`。

`folderspan_file_write` 在 `files.write` Scope 下直接创建或修改小文件。`data` 支持 `utf8`（默认）和 `base64`，按解码后的内容计算，单次最多 512 KiB。`mode` 支持 `overwrite`（默认，文件不存在时创建，空内容会清空现有文件）和 `append`（仅允许已存在的普通文件）。成功结果包含最新的 `entry`、`bytesWritten` 和实际 `mode`。例如：

```json
{
  "locator": {
    "protocol": "Local",
    "path": "/home/user/project/notes.txt"
  },
  "data": "追加一行\n",
  "encoding": "utf8",
  "mode": "append",
  "expectedSize": 128,
  "expectedUpdatedAt": 1786000000000
}
```

`expectedSize` 和 `expectedUpdatedAt` 均为可选的非负乐观并发条件。只要提供其中任意一项，目标就必须已存在且所有已提供字段都与写入前的元数据一致；否则返回 `conflict`，不会写入。该检查不是文件系统事务，适合在 `folderspan_file_info` 或读取后避免无意覆盖近期变更。

`folderspan_directory_create` 创建 locator 指定的单层目录，父目录必须已经存在；目标已经是目录时幂等成功，目标是普通文件时返回 `conflict`。复制、移动和删除仍属于异步文件任务，可通过任务工具查看和控制；超过 512 KiB 的内容继续使用复制或传输路径。

`folderspan_files_share_link` 创建限时局域网链路分享。默认只读。`allowUpload=true` 会给兑换 ticket 的访客写入权，因此属于 mutation：Token 必须同时具备 `files.write`，且宿主分享页已经打开上传开关；否则 `tools/call` 返回 `permission_denied`，不会签发可写 ticket。`tools/list` 在仅有 `files.share` + `files.read` 时仍可看到该工具，以便签发只读分享。

通过 Token Scope、端点能力及设备路径权限校验的普通 Local/Device 路径可以正常列举、读取和变更。本地网关会解析真实路径后再做敏感分类，并拒绝 `/proc`、`/sys`、`/dev`。列表项包含 `sensitivity`（`none`、`sensitive`、`critical`）与稳定的 `sensitivityCategory`；FolderSpan 数据库、TLS 身份、运行态、恢复缓存和 MCP 内部暂存区等受保护项可在已授权父目录列表中被标注，但不提供任何文件操作能力，直接访问或经符号链接 / `/proc` 别名访问会返回 `permission_denied`。完整分类见[敏感与重要文件、目录清单](../security/sensitive-files-and-directories.md)。

Share 写入能力由远端握手协商；旧版本远端默认只读。上传权限不会隐含重命名或删除权限，服务端会按当前会话授权根、隐藏文件规则和最新 capability 逐操作校验。

## 安全与排错

- 带 `Origin` 的请求只接受本机来源（`localhost` / `127.0.0.1` / `127.0.0.0/8` / `::1`）；`127.evil.com` 这类主机名不会被当成 loopback。无 Origin 的原生客户端仍需通过 Host 校验。
- Host 必须是 loopback，或在已打开局域网时为当前广告地址，防止 DNS rebinding。
- 请求体上限为 1 MiB；超限返回 `413`。并发请求、SSE 和会话容量超限返回 `429`。
- 直接写入按 UTF-8 编码或 Base64 解码后的字节数限制为 512 KiB；编码错误、超限数据和负数元数据条件返回 `invalid_argument`，并且会在解析目标端点前被拒绝。
- 缺失、错误或已禁用 Token 返回 `401`，并携带 `WWW-Authenticate: Bearer`。
- MCP 业务错误使用稳定错误码，如 `invalid_argument`、`not_found`、`not_connected`、`permission_denied`、`unsupported`、`conflict`、`io_error`、`cancelled`，错误文本不会返回凭据。
- 受保护路径错误只返回稳定敏感类别，不回显被拒绝的真实路径；敏感标记不会替代或扩大 Token Scope、设备角色与端点授权。本地文件会先规范化再解析真实路径，然后再次分类。
- 端口被占用时管理页显示可恢复错误；修改端口或局域网开关后会重启服务。
- JS/Wasm 构建会明确显示 MCP 服务不受支持，不会开启监听端口。
