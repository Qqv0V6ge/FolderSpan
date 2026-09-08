## Purpose

定义 Desktop 分享页拖入与 Android/iOS 系统外部文件入口如何进入分享列表，包括路由归属、条目归一化、既有列表合并语义，以及外部资源在引用期间保持可读的要求。

## ADDED Requirements

### Requirement: Desktop drop routing while share screen is visible

在 Desktop 上，当文件分享页是当前活动页面时，系统 SHALL 把外部拖入的文件与文件夹路由到分享列表；当分享页不是当前活动页面时，系统 SHALL 保持既有的文件浏览器拖入行为不变。

#### Scenario: Desktop drop lands on share list

- **WHEN** Desktop 文件分享页是当前活动页面且用户从外部应用拖入一个或多个文件
- **THEN** 拖入条目进入分享列表
- **AND** 当前浏览路径与当前磁盘不发生变化
- **AND** 不创建文件复制任务

#### Scenario: Desktop drop outside share screen keeps existing behavior

- **WHEN** Desktop 文件分享页不是当前活动页面且用户从外部应用拖入文件
- **THEN** 拖入按既有的文件浏览器行为处理
- **AND** 分享列表保持不变

### Requirement: Mobile external files open the share screen

Android 与 iOS SHALL 把系统提供的外部文件直接写入分享列表并打开 `FileShareScreen`。如果该页面已经位于导航栈顶，系统 SHALL 只更新待处理条目而不重复导航。

#### Scenario: Android receives shared files

- **WHEN** Android 收到包含一个或多个文件的 `ACTION_SEND` 或 `ACTION_SEND_MULTIPLE`
- **THEN** 系统把可读取 URI 归一化后写入 `FileShareState.updateIncomingFiles`
- **AND** 打开 `FileShareScreen`

#### Scenario: Android receives shared text

- **WHEN** Android 收到不包含文件的纯文本 `ACTION_SEND`
- **THEN** 系统保持既有的首页文本分享行为
- **AND** 不强制打开 `FileShareScreen`

#### Scenario: iOS opens an external file URL

- **WHEN** iOS 通过系统文件打开入口收到一个或多个文件 URL
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

### Requirement: External item normalization

系统 SHALL 把受支持入口的原生载荷归一化为分享列表条目。每个条目 SHALL 携带可寻址路径、名称、大小、是否目录以及内容类型，同一批次 SHALL 按路径去重。

#### Scenario: Normalize a Desktop file

- **WHEN** Desktop 用户拖入单个文件
- **THEN** 条目使用 `FileProtocol.Local` 与真实绝对路径
- **AND** 保留文件名称、大小与内容类型

#### Scenario: Normalize Android shared content

- **WHEN** Android 系统分享提供可读取的 `content://` URI
- **THEN** 条目使用 `FileProtocol.Share` 与 `SYSTEM_SHARE_DESK_ID`
- **AND** 内容可由既有 `ContentResolver` / 系统分享读取链路读取

#### Scenario: Normalize iOS opened file

- **WHEN** iOS 系统文件入口提供可读取 URL
- **THEN** 条目使用 `FileProtocol.Local`
- **AND** 路径在分享列表引用期间保持可读取

#### Scenario: Duplicate paths in one batch

- **WHEN** 同一批载荷包含指向同一路径的多个条目
- **THEN** 该路径只产生一个分享列表条目

### Requirement: Imported items use existing merge semantics

当分享列表已有内容且新条目集合与之不同时，系统 SHALL 让用户在替换、追加与取消之间选择。当分享列表为空时，系统 SHALL 直接使用新条目。当新条目集合与现有分享列表相同时，系统 SHALL 不提示用户。

#### Scenario: Append to a non-empty share list

- **WHEN** 分享列表已有条目，用户导入不同条目并选择追加
- **THEN** 尚未在列表中的新条目被加入分享列表
- **AND** 原有条目全部保留

#### Scenario: Replace a non-empty share list

- **WHEN** 分享列表已有条目，用户导入不同条目并选择替换
- **THEN** 分享列表仅包含本次导入的条目

#### Scenario: Cancel the merge

- **WHEN** 用户在合并选择中取消
- **THEN** 分享列表保持不变

#### Scenario: Import into an empty share list

- **WHEN** 分享列表为空且外部文件进入
- **THEN** 新条目直接成为分享列表内容
- **AND** 不显示合并选择

#### Scenario: Imported items equal current list

- **WHEN** 新条目集合与现有分享列表完全相同
- **THEN** 不显示合并选择
- **AND** 分享列表内容保持等价

### Requirement: External resource availability

对于由平台临时授予访问权的外部资源，系统 SHALL 在对应条目仍存在于分享列表或待合并队列期间保持其可读，并 SHALL 在条目不再被引用后释放资源。

#### Scenario: Read an item after entry handling completes

- **WHEN** 外部入口处理已经结束，但对应条目仍在分享列表中
- **THEN** 已授权设备仍能读取该条目内容

#### Scenario: Keep pending merge resources readable

- **WHEN** 新条目正在等待用户选择替换、追加或取消
- **THEN** 这些条目的临时访问权保持有效

#### Scenario: Release resources after removal or cancellation

- **WHEN** 条目被移除、被替换掉或用户取消合并
- **THEN** 仅由这些条目引用的临时访问权被释放

#### Scenario: Release resources on app teardown

- **WHEN** 应用退出
- **THEN** 尚未释放的临时访问权被释放

### Requirement: Imported items follow existing share authorization rules

从 Desktop 拖入或移动端系统入口导入的条目 SHALL 与通过文件选择器加入的条目适用相同的分享授权与同步规则；系统 SHALL NOT 因入口不同而绕过链接分享的可分享性判定。

#### Scenario: Auto update enabled

- **WHEN** 存在已授权设备、对应分享列表自动更新偏好开启，且导入改变分享列表
- **THEN** 已授权设备的文件集合按更新后的分享列表同步

#### Scenario: Auto update disabled

- **WHEN** 存在已授权设备、对应分享列表自动更新偏好关闭，且导入改变分享列表
- **THEN** 已授权设备保留原文件集合
- **AND** 向用户提示分享列表已更新但设备保留原文件

#### Scenario: Item not shareable over link

- **WHEN** 某个导入条目所属存储位置不允许链接分享
- **THEN** 该条目不被纳入链接分享授权文件集合
- **AND** 判定结果与该条目经文件选择器加入时一致
