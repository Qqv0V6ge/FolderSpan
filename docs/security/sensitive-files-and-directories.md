# 敏感与重要文件、目录清单

本文档列出 FolderSpan 自身产生或持有的敏感、重要数据。它既用于开发、运维、备份和安全审查，也是 `SensitiveFileAccessPolicy` 路径注册表的维护依据。文件系统调用方必须先完成 Token/设备角色授权和敏感路径分类，再为普通路径向 `PathUtils`、`FileUtils` 显式传入 `FileAccessPermission.Allowed`。

## 保护原则

- `FileAccessPermission` 表示单次底层 I/O 决策，不再用于区分“本地调用者”和“外部调用者”。任何远端请求都不得直接提供或反序列化该值。
- Device/WebRTC RPC 先校验认证 Token、设备角色、路径范围和符号链接；MCP 先校验 Token Scope、端点能力和路径安全。MCP 本地文件在词法分类之后还会解析真实路径并再次分类，且拒绝 `/proc`、`/sys`、`/dev`。只有通过授权且分类为 `none` 的路径才能传入 `Allowed`。
- `SensitiveFileAccessPolicy` 使用平台应用私有根目录和下表中的已知目录进行纯词法分类。无效或无法规范化的外部路径按 `critical` 失败关闭。
- 受保护路径必须在存在性探测、目录枚举、日志记录和 I/O 之前被拒绝。错误可以包含稳定类别，但不得包含被拒绝的真实路径。
- 已授权父目录的列表结果可以显示受保护子项的名称，但必须标注敏感度和类别，并把读取、写入、重命名、删除、共享能力全部设为不可用；该标记本身不是访问授权。
- 不得将本清单中的真实数据、密钥、口令、Token 或数据库副本提交到仓库、问题单、日志和测试夹具。
- 备份、迁移或清理这些数据前，应同时处理数据库旁路文件、临时文件和平台密钥材料；不要只复制或删除主文件。

## 对外敏感度标记

| 标记 | 含义 | 对外文件接口行为 |
| --- | --- | --- |
| `none` | 不在 FolderSpan 受保护存储注册表中；仍需通过 Token Scope、设备角色、端点能力和符号链接校验。 | 可按既有权限执行普通文件操作。 |
| `sensitive` | 用户正文副本、运行态、历史、日志或恢复缓存。 | 父目录列表可返回名称和稳定类别，直接元数据、内容和变更操作拒绝。 |
| `critical` | 数据库、凭据、身份密钥或整个应用私有数据根。 | 与 `sensitive` 相同地拒绝，并按最高敏感级别处理。 |

当前稳定类别包括 `application_private_data`、`application_cache`、`database`、`tls_identity`、`sync_tasks`、`share_history`、`task_runtime`、`task_failure_results`、`sync_staging`、`device_logs`、`editor_backups`、`editor_recovery`、`editor_content_cache`、`editor_line_index`、`mcp_staging` 和 `pro_http_cache`。新增或改名时必须同步修改注册表、本文档和覆盖测试。

## 最高敏感级别

| 文件或目录 | 数据内容与风险 | 处理要求 |
| --- | --- | --- |
| `folderspan.db` | SQLDelight/SQLite 主数据库，包含设备与连接记录、角色和权限、收藏/书签/最近记录、同步与共享配置；`NetworkDrive.password` 保存网络盘口令，`WebRtcRoom.turnPassword` 保存 TURN 口令，`McpToken.secretHash` 保存 MCP Token 哈希。 | 禁止通过 Device/MCP 文件接口读取、写入、重命名或删除；备份时按敏感凭据处理。 |
| `folderspan.db-wal`、`folderspan.db-shm`、`folderspan.db-journal` | SQLite 事务与恢复旁路文件，可能包含主数据库的历史页或未提交敏感数据。 | 与主数据库同级保护；复制数据库时使用一致性快照，不单独公开旁路文件。 |
| `tls-identity/` | 设备 HTTPS/TLS 身份材料。Android、Desktop 和 iOS 实现均使用该目录。 | 禁止导出或远程浏览；删除会导致设备身份变化或连接信任失效。 |
| `tls-identity/*.fmi` | TLS 身份的加密载荷；文件名由加密名称转十六进制生成。 | 即使载荷已加密也按私钥材料保护，不得公开、替换或回滚。 |
| `tls-identity/storage.salt` | 外层文件加密用的高熵盐，与 deviceId 一起派生存储密钥。 | 与身份文件同级保护；丢失会导致身份无法解密并重新生成。 |
| `tls-identity/.*.tmp` | TLS 身份原子写入期间产生的临时文件，可能包含完整加密载荷。 | 与正式 `.fmi` 文件同级保护；仅由身份存储实现清理。 |
| Desktop `secure-settings/` | AES-GCM 金库：主密钥 `master.key` 与密文 `values.v1`，保存会话 Token、Access Key、设备 TLS PKCS12 口令、凭据包装密钥（DEK）等。 | 与数据库同级；清理账号设置时删除该目录。 |
| Desktop Java Preferences | 非敏感设置（主题、端口、开关）。敏感键只写入 `secure-settings/`。 | 远端接口不得枚举或修改。 |
| iOS Keychain（`com.folderspan.secure-settings`） | 会话 Token、Access Key、设备 ID、凭据包装密钥等敏感设置。 | 仅应用访问；账号清理时删除该服务下的 Generic Password。 |
| Android `secure_settings` DataStore | 经 Tink AEAD 加密的设置，包括设备标识、连接与服务配置等。 | 仅允许应用私有访问；不得复制到公共存储或日志。 |
| Android Tink 密钥集 SharedPreferences / Android Keystore 项 | 用于解密 `secure_settings` 的密钥材料。 | 最高敏感；禁止导出、同步或由通用文件工具管理。 |
| Web `localStorage` 中的 FolderSpan 设置 | 非敏感 UI 设置明文存放。会话 Token、Access Key、DEK 与设备 ID 写入 `settings.web.secretVault.*` AES-GCM 密文；包装密钥仍在同源 `localStorage`，因此退出登录必须删除这些键。 | 按用户敏感数据保护；远端接口不得枚举或修改。 |

## 重要应用数据

| 目录 | 主要内容 | 影响 |
| --- | --- | --- |
| `sync-tasks/` | 同步任务定义、运行记录及相关持久化数据。 | 泄露本地/远端路径和同步关系；修改可能造成错误覆盖或删除。 |
| `share-history/` | 文件共享历史及其 Protobuf 行式记录。 | 泄露文件名、路径、设备和传输时间等隐私。 |
| `task-runtime/` | 正在执行或待恢复任务的运行态、队列和进度文件。 | 修改会破坏恢复、重复执行或造成任务状态不一致。 |
| `task-failure-results/` | 任务失败明细及错误记录。 | 可能包含路径、设备、网络端点和错误上下文。 |
| `sync-stage/` | 同步过程的暂存文件。 | 可能包含用户文件的完整或部分副本；不得视为普通缓存公开。 |
| `device_logs/` | 按设备保存的请求/诊断日志。 | 可能泄露设备 ID、请求路径、端点和错误信息。 |

## 编辑器与恢复缓存

以下内容虽然位于缓存目录，仍可能包含用户文件正文或可恢复片段，必须按用户数据保护：

| 目录 | 主要内容 |
| --- | --- |
| `file-editor-backups/` | 编辑器备份和范围备份。 |
| `file-editor-recovery/` | 崩溃恢复日志与待恢复编辑内容。 |
| `editor-content/` | 编辑器分页或内容缓存，可能包含完整正文。 |
| `file-editor-line-index/` | 大文件行索引及与源文件结构相关的缓存。 |
| `mcp-file-staging/` | MCP Network 读取或分块写入使用的内部暂存文件，可能包含完整远端文件。 |
| `pro_http_cache/` | Pro 用户资料与设备列表 HTTP 响应的 AES-GCM 缓存。Desktop 位于应用数据目录，Android / iOS 位于应用沙箱缓存目录。密钥为每安装实例 DEK，不得通过 FileProvider 或通用文件接口导出。 |

缓存清理可以由应用的专用生命周期逻辑以可信内部上下文执行，但 Device/MCP 通用文件接口不得读取或修改这些目录。

## 平台位置

实际根路径由平台沙箱和系统 API 决定，不能通过字符串拼接假定所有设备一致：

- Android：`folderspan.db` 位于应用私有数据库目录；`tls-identity/` 位于 `filesDir`；上述运行态和编辑器目录主要位于应用私有 files/cache 目录。
- Desktop：应用数据根目录为 macOS `~/Library/Application Support/FolderSpan`、Windows `%APPDATA%/FolderSpan`、Linux `~/.local/share/FolderSpan`；数据库、`tls-identity/`、`secure-settings/`、JVM Pro HTTP 缓存 `pro_http_cache/` 与运行态缓存 `cache/`（目录 `0700`、文件 `0600`）位于该应用数据根目录。编辑器备份/恢复、任务运行态、失败明细、设备日志、同步暂存与 MCP 暂存都在 `cache/` 下，不再使用全局 `java.io.tmpdir`。
- iOS：`folderspan.db` 位于应用沙箱；TLS 身份位于 `Library/Application Support/FolderSpan/tls-identity`。数据库文件与 TLS 身份目录均设置 `NSURLIsExcludedFromBackupKey` 与 `NSFileProtectionCompleteUntilFirstUserAuthentication`。非敏感设置位于 FolderSpan 的 `NSUserDefaults` 域，敏感设置位于 Keychain 服务 `com.folderspan.secure-settings`。
- Web：设置位于浏览器 `localStorage`。敏感键不写明文，而是经 AES-GCM 写入 `settings.web.secretVault.values`；包装密钥在 `settings.web.secretVault.master`。文件和运行态数据可能位于内存文件系统、OPFS 或 `/tmp` 风格的虚拟路径。浏览器存储仍属于用户敏感数据。

## 新增数据时的维护要求

新增以下任一内容时，必须同步更新本文档，并检查权限入口、清理逻辑与测试：

- 凭据、Token、哈希、密钥、证书或加密载荷；
- 数据库、事务旁路文件或数据库迁移产生的新文件；
- 用户文件副本、编辑器恢复数据、同步/传输暂存数据；
- 记录设备、路径、网络端点或操作历史的日志与索引；
- 新的平台设置存储、密钥库或应用数据根目录。
