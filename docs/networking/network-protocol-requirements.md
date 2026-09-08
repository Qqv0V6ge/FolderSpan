# 网络协议新增/维护需求清单

本文档用于统一网络协议（FTP / SFTP / SMB / WebDav / S3 / LinkShare）的平台行为、字段规范、UI 约束与实现要求。

## 1. 支持的协议与平台

### 1.1 协议清单
- FTP
- SFTP
- SMB
- WebDav
- S3（Ktor + SigV4）
- LinkShare

### 1.2 平台支持规则（不支持的协议可新增/保存；连接时抛不支持异常）
| 协议 | JVM | Android | iOS | JS / WASM |
| --- | --- | --- | --- | --- |
| FTP | 支持 | 支持 | 支持 | 不支持 |
| SFTP | 支持 | 支持 | 支持 | 不支持 |
| SMB | 支持 | 支持 | 支持 | 不支持 |
| WebDav | 支持 | 支持 | 支持 | 支持 |
| S3 | 支持 | 支持 | 支持 | 支持 |
| LinkShare | 支持 | 支持 | 支持 | 支持 |

## 2. 协议能力（最低要求）

每个网络协议都应支持以下能力：
- 列表（`list`）
- 读取（`download` / `read`）
- 写入（`upload` / `write`）
- 重命名（`rename`）
- 删除（`delete`）
- 复制/移动/粘贴（`copy` / `move` / `paste`，要求本地与网络互通）

## 3. 数据模型

### 3.1 公共字段（`NetworkDrive` 表 + `Network` 模型）
- `name`
- `protocol`
- `host`（包含端口，如 `192.168.0.10:445`；WebDav 使用完整 base URL）
- `username`
- `password`
- `pathSeparator`
- `pinned`
- `extras`（BLOB，保存协议扩展字段）

约束：
- 端口不单独存储，统一写入 `host`。
- `pathSeparator`：SMB 默认 `\\`，其他默认 `/`。

### 3.2 扩展字段（必须使用 ProtoBuf）

任何协议的额外字段必须进入 `extras`，禁止新增表字段。推荐结构如下：
- `FtpDriveExtras(passiveMode, ftpsEnabled, pathEncoding)`
- `SftpDriveExtras(privateKey, knownHosts)`
- `SmbDriveExtras(share, domain)`
- `WebDavDriveExtras(authType, token, tokenHeaderName, tokenPrefix, headers)`
- `S3DriveExtras(bucket, region, endpoint, sessionToken, forcePathStyle)`

新增协议时：
- 在 `NetworkDriveExtras` 中追加分组数据类。
- 使用 `@ProtoNumber` 标记字段号。
- `extras` 始终使用 ProtoBuf 编码。

## 4. 地址与端口规则

- 不提供独立端口输入框，统一写在地址输入框中。
- 通用解析使用 `parseNetworkAddress`，支持：
  - `host:port`
  - `scheme://host:port`
  - `\\host\share`（SMB）
- WebDav 例外：使用完整 URL（含 scheme + 可选 path），不走 `parseNetworkAddress`。
- S3 例外：地址框表示可选 endpoint（`https://host[:port]`），`bucket/region` 由 `extras.s3` 提供。
- 保存时：
  - FTP/SFTP/SMB：保存 `normalizedHost`
  - WebDav：保存规范化后的 base URL
  - S3：保存规范化 endpoint（若提供）
- 地址不合法时提示“链接地址格式错误”。

## 5. UI 规范

### 5.1 地址输入
地址输入框与 LinkShare 保持一致，要求：
- 单行输入
- 错误态显示错误文案
- 非错误态显示规范化地址预览（如“将使用 xxx”）

### 5.2 新增 / 编辑页
- 测试连接按钮使用 `ExtendedFloatingActionButton`。
- 对于平台不支持的协议（如 Web 上 FTP/SFTP/SMB）：
  - 新增/编辑页不提前禁用协议选择与保存。
  - 在测试连接或实际连接阶段由客户端抛出不支持异常。
  - UI 负责将异常转换为可读的错误提示。

## 6. 连接日志

FTP / SFTP / SMB / WebDav / S3 连接流程需要输出 `LogKit` 日志：
- 连接开始
- 连接成功
- 失败原因（异常信息）
- 列表数量

## 7. 数据库与加密约束

### 7.1 `NetworkDrive` 表字段
- `id, name, protocol, host, username, password, pathSeparator, pinned, extras`

### 7.2 加密要求（强制）
- `password` 与 `extras` 必须加密存储。
- 解密失败必须直接报错，不允许明文回退。
- 加解密统一使用 `SymmetricCrypto`。

### 7.3 迁移策略
- 当前项目处于开发阶段，不编写数据库升级/迁移代码。

## 8. 新增协议流程建议（Checklist）

1. 在 `NetworkProtocol` 中新增协议类型（如需要）。
2. 在 `NetworkDriveExtras` 中新增分组数据类并分配 `@ProtoNumber`。
3. 定义地址解析规则与默认端口（WebDav/S3 可按完整 URL 处理）。
4. 新增协议表单并接入统一地址输入样式。
5. 实现 `NetworkClient` 能力：`list / download / upload / rename / delete / createFolder / createFile`。
6. 连接与关键操作输出 `LogKit`。
7. `password` / `extras` 严格按加密约束持久化。
8. 校验新增/编辑/测试连接与实际浏览流程中的错误提示一致性。
