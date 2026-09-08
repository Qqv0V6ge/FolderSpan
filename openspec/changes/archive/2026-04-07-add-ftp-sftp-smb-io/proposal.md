# Change: Add FTP/SFTP/SMB network drive IO

## Why
FTP、SFTP、SMB 是常见的局域网文件协议，当前系统仅支持 LinkShare（且 WebDav 未完善）。需要补齐这些协议的核心文件操作能力，并在 JS/WASM 上明确提示不支持。

## What Changes
- 新增 FTP/SFTP/SMB 的列表、读取、写入（粘贴复制/移动）、重命名、删除能力
- 扩展网络协议表单字段（端口、被动模式、FTPS、私钥、known_hosts、SMB 共享名/域）并持久化
- Android/JVM 使用指定的 Apache Commons Net、Mina SSHD、SMBJ
- iOS 增加专门的本地库实现（Kotlin/Native）
- JS/WASM 协议项可见但禁用并提示不支持

## Impact
- Affected specs: `manage-network-drives`, `access-network-drives` (new)
- Affected code: `shared/` network model & SQLDelight, `composeApp/` network UI, platform implementations (androidMain/jvmMain/iosMain), About Libraries 资源
