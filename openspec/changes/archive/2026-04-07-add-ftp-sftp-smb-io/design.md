## Context
现有网络盘管理已具备协议选择与持久化能力，但实际仅 LinkShare 实现了列表/读取。FTP/SFTP/SMB 需要补齐完整文件操作，并跨 Android/JVM/iOS 提供实现，同时在 JS/WASM 上清晰提示不支持。

## Goals / Non-Goals
- Goals:
  - 在 Android/JVM/iOS 支持 FTP/SFTP/SMB 的列表、读取、写入（粘贴复制/移动）、重命名、删除
  - 协议表单按协议展示字段，并持久化所有字段
  - JS/WASM 协议项可见但禁用并提示不支持
- Non-Goals:
  - WebDav 实现
  - 高级能力（断点续传、目录同步、全文搜索）
  - 复杂的证书管理 UI（仅提供 FTPS 开关）

## Decisions
- 在 `shared` 定义协议无关的网络操作接口（list/read/write/rename/delete/move），由各平台 `actual` 提供实现。
- JVM 平台实现：
  - FTP：Apache Commons Net（支持被动模式与 FTPS）
  - SFTP：Apache Mina SSHD
  - SMB：SMBJ
- iOS 平台实现：引入专用本地库，通过 Kotlin/Native cinterop 访问。
  - 选型：libcurl(FTP/FTPS)、libssh2(SFTP)、libsmb2(SMB)。
- JS/WASM 平台提供空实现，返回“未支持”错误，并用于 UI 禁用提示。
- 默认值：FTP 端口 21、被动模式开启、FTPS 关闭；SFTP 端口 22；SMB 端口 445；SMB pathSeparator 默认为 `\\`（可编辑）。

## Risks / Trade-offs
- 证书校验：FTPS/SFTP 的证书与 known_hosts 处理需在实现中谨慎设置默认安全行为。
- iOS 依赖选择与编译集成复杂度较高。
- 大文件传输的内存压力，需要流式传输与任务取消支持。

## Migration Plan
- SQLDelight 迁移新增字段并设置默认值。
- 旧网络记录按协议补齐缺省字段（端口、被动模式、FTPS、SMB 共享名等）。

## Open Questions
- 暂无
