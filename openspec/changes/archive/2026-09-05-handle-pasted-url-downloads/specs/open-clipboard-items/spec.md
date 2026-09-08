## ADDED Requirements

### Requirement: 剪贴板处理仅响应用户操作
系统 SHALL 仅在用户主动选择“从剪贴板打开”或受支持的非编辑态粘贴快捷键时读取剪贴板并执行路径、URL 或普通内容分流。

#### Scenario: 用户触发从剪贴板打开
- **WHEN** 用户选择“从剪贴板打开”操作
- **THEN** 系统读取一次当前剪贴板快照
- **AND** 系统基于该快照完成本次分流

#### Scenario: 从剪贴板打开独立 URL
- **WHEN** 用户选择“从剪贴板打开”且快照是独立 HTTP/HTTPS URL
- **THEN** 系统发起最小范围请求检查最终响应的文件名、MIME 与总大小
- **AND** 系统将元数据构造成 `FileSimpleInfo` 并加入侧栏 `DeviceState.shares` 的系统分享来源
- **AND** 系统在主文件浏览器显示该来源的文件列表，同一来源已打开时刷新条目
- **AND** 系统不打开 `FileShareScreen`，也不向其 `incomingFiles` 队列投递 URL 元数据
- **AND** 系统不显示下载设置弹窗、不创建下载任务且不预先下载完整文件

#### Scenario: 后台事件不读取剪贴板
- **WHEN** 应用启动、恢复前台或剪贴板内容自行变化
- **AND** 用户未主动触发剪贴板操作
- **THEN** 系统不读取剪贴板

### Requirement: 只有主动粘贴的独立 URL 确认后创建下载任务
系统 SHALL 仅在快捷键等主动粘贴入口的内容是可下载候选独立 HTTP/HTTPS URL 时显示下载设置弹窗，并 MUST NOT 在用户确认前创建下载任务或发起请求；确认后系统 SHALL 先创建可见任务，再由任务访问 URL、检查响应并下载文件。“从剪贴板打开”入口 SHALL NOT 显示该弹窗。

#### Scenario: 独立 URL 显示下载设置弹窗
- **WHEN** 用户主动粘贴一条可作为下载候选的独立 HTTP/HTTPS URL
- **THEN** 系统显示下载设置弹窗而不是立即请求网络
- **AND** 用户确认后先创建下载任务
- **AND** 下载任务开始后才访问 URL 并检查响应

#### Scenario: 复制已打开的 URL 文件到目标目录
- **WHEN** 用户在系统分享来源的文件列表选中 URL 条目并复制，然后在可写目标目录粘贴
- **THEN** 系统显示该文件的下载设置
- **AND** 用户确认前不创建下载任务或发起完整文件请求
- **AND** 确认后同一下载任务下载文件并将其写入用户选定的目标目录
- **AND** 此过程不打开发送文件用的分享页面

#### Scenario: 外部分享文字不显示剪贴板弹窗
- **WHEN** 其他应用通过系统分享入口提供纯文字或 URL 文字
- **AND** 用户没有在 FolderSpan 内执行剪贴板粘贴操作
- **THEN** 系统保持平台既有文字接收行为
- **AND** 系统不显示 URL 下载设置或原文弹窗

### Requirement: 未处理内容显示原文弹窗
系统 SHALL 保留本次读取的完整原始文字；当内容不是可打开的现有路径、不是可下载的独立 HTTP/HTTPS URL，或 URL 下载失败时，系统 SHALL 显示可滚动、可选择的内容弹窗并提供复制与关闭操作。

#### Scenario: 普通文字显示弹窗
- **WHEN** 用户主动粘贴普通文字且内容无法解析为可打开路径或可下载 URL
- **THEN** 系统显示完整原始文字
- **AND** 弹窗提供“复制”和“关闭”操作

#### Scenario: URL 下载失败显示原文
- **WHEN** 用户主动粘贴并确认的独立 HTTP/HTTPS URL 检查或下载失败
- **THEN** 系统显示简短失败原因和原始 URL
- **AND** 用户可以复制原始 URL

#### Scenario: 用户复制弹窗内容
- **WHEN** 用户选择弹窗的“复制”操作
- **THEN** 系统把本次原始文字写回系统剪贴板
- **AND** 系统提供成功或失败反馈

## MODIFIED Requirements

### Requirement: Clipboard content logging
The system SHALL log only privacy-safe clipboard diagnostics via LogKit and MUST NOT log raw clipboard text, complete file paths, URL query parameters, fragments, or response-sensitive data.

#### Scenario: Clipboard diagnostics logged
- **WHEN** clipboard content is read after a user-triggered clipboard action
- **THEN** the system logs only entry types, counts, lengths, and redacted error categories

### Requirement: URL logging
The system SHALL log URL classification and download outcome using redacted diagnostics and MUST NOT log the complete pasted URL.

#### Scenario: URL diagnostics logged
- **WHEN** the clipboard contains an HTTP/HTTPS URL candidate
- **THEN** the system logs the scheme, redacted host classification, non-sensitive setting flags, and outcome without query parameters, fragments, Cookie, or User-Agent values
