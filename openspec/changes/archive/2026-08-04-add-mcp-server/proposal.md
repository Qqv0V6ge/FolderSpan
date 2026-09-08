## Why

FolderSpan 现有的书签、设备、网络、同步和文件能力只能通过应用界面或内部协议操作，外部智能工具无法以统一、可授权的方式调用。新增标准 MCP HTTP 服务后，用户可在受控 Token 权限下从局域网自动化这些能力，同时继续复用现有任务、设备连接和文件传输语义。

## What Changes

- 在 Android、Desktop 和 iOS 上新增可从工具箱管理的 MCP 服务，监听局域网接口并同时提供有状态 Streamable HTTP 与无状态 HTTP 端点；不提供 stdio 传输。
- 新增多 Token 管理与细粒度 Scope 授权，覆盖只读查询、书签、收藏/最近、任务控制、设备扫描/连接、网络连接、同步执行、文件读取/写入和分享；Token 仅以哈希形式持久化。
- 提供书签 CRUD、收藏管理、最近记录管理、文件任务管理、HTTP/WebRTC 设备、网络和同步任务等 MCP 工具。
- 为 Local、Share、Device、Network 建立按数据源实例隔离的书签，并提供兼容迁移。
- 提供四类数据源的列表、元数据查看、分段读取、重命名、删除、复制、移动和分享工具；复制、移动和删除进入现有异步任务系统。
- 扩展 Share 会话权限和文件协议，使获得明确写入/删除授权的 Share 可作为复制、移动、重命名和删除的来源或目标；未授权请求返回结构化权限错误。
- 文件读取支持 offset/length 分段以及 UTF-8/base64 编码，默认 256 KiB、单次硬上限 4 MiB。
- WebRTC 仅列出并连接应用已经发现的在线节点；HTTP 设备扫描默认扫描全部活动 IPv4 网段，也可限制到指定网段。

## Capabilities

### New Capabilities

- `mcp-http-server`: MCP Streamable HTTP 端点、局域网监听、会话/SSE、Token 鉴权、Scope 授权和工具箱管理界面。
- `mcp-automation-tools`: 书签、收藏、最近、文件任务、设备、WebRTC、网络和同步的 MCP 工具契约。
- `mcp-file-access`: 四类数据源的文件列表、元数据、分段读取、异步复制/移动/删除、重命名与两种分享方式。
- `scoped-bookmarks`: 按 Local、Share、Device、Network 及具体数据源实例隔离的统一书签存储和迁移。
- `writable-share-operations`: Share 会话的可写权限协商，以及上传、重命名、删除和移动支持。

### Modified Capabilities

- `manage-file-operation-tasks`: 文件任务新增面向 MCP 的查询、暂停、继续、取消和删除入口，并保持既有任务状态语义。
- `move-files`: 明确跨 Local、Share、Device、Network 移动时逐项“复制成功后才删除源”的原子边界和部分失败结果。

## Impact

- 影响 `core` 的 HTTP 服务、MCP 协议适配、鉴权、书签存储、Share 协议、文件操作协调器及业务状态适配层。
- 影响共享 Compose UI：`ToolboxScreen` 新增 MCP 入口及服务、地址、Token、Scope 管理页面；Web 端显示不支持服务端监听。
- 新增数据库迁移或等价持久化结构，用于数据源作用域书签、Token 元数据和 Share 权限；敏感 Token 正文不得入库或进入日志。
- 需要覆盖 Android、Desktop、iOS 的监听与生命周期测试，以及协议、鉴权、权限、跨源传输和 UI 测试。
