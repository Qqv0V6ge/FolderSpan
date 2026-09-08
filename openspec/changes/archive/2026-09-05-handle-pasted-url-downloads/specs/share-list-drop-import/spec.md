## MODIFIED Requirements

### Requirement: Mobile external files open the share screen

Android 与 iOS SHALL 把系统提供的外部文件直接写入分享列表并打开 `FileShareScreen`。如果该页面已经位于导航栈顶，系统 SHALL 只更新待处理条目而不重复导航。外部入口的纯文字 SHALL 保持平台既有文字接收行为，并 SHALL NOT 显示仅供主动剪贴板粘贴使用的 URL 设置或原文弹窗。

#### Scenario: Android receives shared files

- **WHEN** Android 收到包含一个或多个文件的 `ACTION_SEND` 或 `ACTION_SEND_MULTIPLE`
- **THEN** 系统把可读取 URI 归一化后写入 `FileShareState.updateIncomingFiles`
- **AND** 打开 `FileShareScreen`

#### Scenario: Android receives shared text

- **WHEN** Android 收到不包含文件的纯文本 `ACTION_SEND`
- **THEN** 系统保持既有的首页文本分享行为
- **AND** 不显示粘贴 URL 设置或原文弹窗
- **AND** 不强制打开 `FileShareScreen`

#### Scenario: iOS opens or receives external file URLs

- **WHEN** iOS 通过系统文件打开入口或分享扩展收到一个或多个文件 URL
- **THEN** 系统把可读取文件归一化后写入 `FileShareState.updateIncomingFiles`
- **AND** 打开 `FileShareScreen`

#### Scenario: Share screen is already active

- **WHEN** Android 或 iOS 收到外部文件且 `FileShareScreen` 已位于导航栈顶
- **THEN** 系统更新待合并条目
- **AND** 不再压入第二个 `FileShareScreen`

#### Scenario: Payload carries no readable item

- **WHEN** 系统入口不包含任何可读取文件
- **THEN** 分享列表与导航栈保持不变
- **AND** 已取得的临时资源访问权被释放

### Requirement: Imported items use existing merge semantics

当发送分享页已有内容且平台新条目集合与之不同时，系统 SHALL 让用户在替换、追加与取消之间选择。当列表为空时，系统 SHALL 直接使用新条目。当集合相同时，系统 SHALL 不提示用户。URL 元数据及直接粘贴 URL 的结果 SHALL 使用侧栏系统分享来源，不进入该发送分享页合并流程。

#### Scenario: Opened URL metadata joins the share list

- **WHEN** “从剪贴板打开”对独立 URL 的元数据检查成功
- **THEN** 系统把生成的 `FileSimpleInfo` 注册为侧栏系统分享来源的文件
- **AND** 系统在主文件浏览器中打开该来源的文件列表，已有 URL 条目继续保留
- **AND** 系统不使用 `FileShareState.incomingFiles` 或 `FileShareScreen` 处理该元数据条目
- **AND** 元数据检查阶段不下载完整文件

#### Scenario: Pasted URL download joins an empty share list

- **WHEN** 主动粘贴 URL 的下载任务成功发布文件
- **AND** 系统分享来源列表为空
- **THEN** 下载文件成为系统分享来源的文件列表内容
- **AND** 系统不显示合并选择

#### Scenario: Pasted URL download joins an existing source list

- **WHEN** 主动粘贴 URL 的下载任务成功发布文件
- **AND** 系统分享来源已有不同条目
- **THEN** 系统保留已有来源条目并加入新下载文件
- **AND** 系统刷新主文件浏览列表

### Requirement: External resource availability

对于平台临时授予访问权的资源，系统 SHALL 在分享页条目或待合并队列引用期间保持可读。URL 来源及直接下载结果 SHALL 在系统分享来源关闭或应用退出时释放；目标粘贴产生的 staging SHALL 在目标交付结束、失败或取消后释放。

#### Scenario: Close an opened URL source

- **WHEN** 用户关闭侧栏的系统分享来源或应用退出
- **THEN** 系统释放该来源保存的 URL 元数据条目和资源注册
- **AND** 已捕获 URL 且正在执行的下载任务仍由其自身取消和清理机制管理

#### Scenario: Stream an opened URL only when requested

- **WHEN** 分享客户端请求“从剪贴板打开”产生的 URL 文件条目
- **THEN** 链接分享服务按请求范围从已检查 URL 流式读取内容
- **AND** 系统复核远端响应范围与已登记总大小
- **AND** 匿名分享路径不泄露原始 URL 的主机、query 或 fragment

#### Scenario: Keep URL staging readable while referenced

- **WHEN** URL 下载文件位于系统分享来源或正在交付粘贴目标
- **THEN** 分享服务和预览仍可读取该文件

#### Scenario: Release URL staging after source closure or copy completion

- **WHEN** 用户关闭系统分享来源，或文件粘贴任务完成、失败或取消
- **THEN** 系统释放并清理对应 staging 批次

#### Scenario: Release resources on app teardown

- **WHEN** 应用退出
- **THEN** 尚未释放的平台权限与 URL 下载 staging 被释放
