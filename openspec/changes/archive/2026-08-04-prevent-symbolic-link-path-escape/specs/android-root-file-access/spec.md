## ADDED Requirements

### Requirement: Android 特权文件访问必须采用不跟随链接语义

Root 和 Shizuku 文件服务 MUST 与普通本地 IO 一样识别符号链接，并在列举、遍历和递归删除中默认不跟随链接。两种特权后端返回的文件元数据和删除行为 MUST 保持一致。

#### Scenario: 特权列举返回目录链接

- **WHEN** Root 或 Shizuku 列举的目录包含指向目录的符号链接
- **THEN** 返回元数据明确标记该条目为符号链接
- **AND** 通用遍历不会把该条目加入目录队列

#### Scenario: 特权遍历遇到循环链接

- **WHEN** Root 或 Shizuku 遍历的目录树包含指向自身或祖先的链接
- **THEN** 遍历不进入链接并在有限时间内结束

#### Scenario: 特权删除目录链接

- **WHEN** Root 或 Shizuku 删除一个指向外部目录的链接
- **THEN** 只删除链接目录项
- **AND** 外部目标及其内容保持不变

#### Scenario: 特权删除包含链接的目录

- **WHEN** Root 或 Shizuku 递归删除的普通目录包含外部目录链接
- **THEN** 删除不进入外部目标
- **AND** Root、Shizuku 与普通本地删除结果一致
