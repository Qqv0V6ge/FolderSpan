## Purpose

定义设备仅在现有连接已获批、鉴权令牌有效且传输会话存活时互发最长 1 MiB 的实时纯文本消息，并规定跨 TLS Session 与 WebRTC 的分块传输、持久化确认、离线历史、安全和生命周期行为。

## Requirements

### Requirement: Sending requires an authenticated live device connection

系统 SHALL 仅允许通过已经完成设备连接审批、绑定有效授权令牌且仍处于存活状态的设备会话发送消息。设备已被发现、已保存、拥有历史消息或曾经批准 MUST NOT 单独赋予发送权限；连接建立前的唯一业务入口仍为现有连接流程。

#### Scenario: Connected device sends a message
- **WHEN** 目标设备处于在线已连接状态，并且当前传输会话的授权令牌仍然有效
- **THEN** 发送方可以提交消息
- **AND** 接收方通过该已认证设备会话接收消息数据

#### Scenario: Discovered device is not connected
- **WHEN** 设备仅被发现或保存在设备列表中，但尚未建立已认证连接
- **THEN** 系统禁止向该设备发送消息
- **AND** 系统不会为了发送消息而隐式发起连接

#### Scenario: Connection approval is pending
- **WHEN** 设备连接正在等待用户批准
- **THEN** 请求方和接收方均不能通过该待审批会话发送消息

#### Scenario: Offline device has message history
- **WHEN** 用户可以查看某设备的本地历史消息，但该设备当前不在线或未连接
- **THEN** 系统允许查看历史
- **AND** 禁止提交新消息

#### Scenario: Authorization is revoked
- **WHEN** 已连接会话的授权令牌被撤销、失效或设备被永久拒绝
- **THEN** 系统立即拒绝该会话后续发送和接收的消息数据
- **AND** 不会把拒绝期间提交的消息留待重新连接后发送

### Requirement: Messaging is bidirectional across supported device transports

系统 SHALL 在 TLS Session 和 WebRTC 两种已连接设备传输上提供相同的双向文本消息行为。接收方看到的发送设备身份 SHALL 来自已经认证的会话对端，而不是来自消息负载中可由发送方任意填写的身份字段。

#### Scenario: TLS Session peer sends in either direction
- **WHEN** 两台设备通过已认证 TLS Session 保持连接
- **THEN** 会话发起方和会话接收方都可以向对方发送消息

#### Scenario: WebRTC peer sends in either direction
- **WHEN** 两台设备通过已认证 WebRTC 设备连接保持连接
- **THEN** 任一对端都可以向另一端发送消息
- **AND** 用户可观察行为与 TLS Session 消息一致

#### Scenario: Sender identity conflicts with the session
- **WHEN** 消息元数据声明的发送设备身份与已认证会话对端不一致
- **THEN** 接收方拒绝该消息或使用会话对端身份覆盖不可信声明
- **AND** 不把消息归属到其他设备

### Requirement: Long messages transfer as bounded plain-text streams

消息正文 SHALL 作为纯文本处理并按原样展示，不执行 HTML、Markdown 或脚本。去除首尾空白后正文 MUST 非空，UTF-8 编码后的正文 MUST 不超过 1,048,576 字节。长消息 SHALL 通过有界分块传输，接收方 MUST 在声明长度、实际长度和完整性摘要全部匹配后才接受完整消息。

#### Scenario: Small valid text message
- **WHEN** 用户发送一条非空且 UTF-8 大小不超过传输单帧限制的文本消息
- **THEN** 接收方按纯文本展示正文
- **AND** 保留正文中的合法换行

#### Scenario: One MiB message spans multiple chunks
- **WHEN** 用户通过在线已认证连接发送一条 UTF-8 大小为 1,048,576 字节的文本消息
- **THEN** 系统以多个有界数据块传输消息
- **AND** 接收方在完整接收并校验后显示一条完整消息

#### Scenario: Empty message is rejected
- **WHEN** 用户提交空字符串或仅包含空白字符的消息
- **THEN** 系统在开始传输前拒绝该消息

#### Scenario: Oversized message is rejected
- **WHEN** 消息正文的 UTF-8 编码大小超过 1,048,576 字节
- **THEN** 发送方拒绝提交并显示大小限制错误
- **AND** 接收方也会拒绝绕过发送方校验的超限消息

#### Scenario: Long-message transfer is incomplete
- **WHEN** 长消息传输结束时实际长度或完整性摘要与元数据不一致
- **THEN** 接收方拒绝该消息且不把不完整正文加入历史
- **AND** 发送方不会收到已送达确认

### Requirement: Receipt follows durable acceptance and messages are deduplicated

接收方 SHALL 在消息通过连接鉴权、负载校验、完整性校验并成功持久化后返回接收确认。发送方 SHALL 持久化发送记录并将收到确认的消息标记为已送达；发送失败、确认超时或连接中断 MUST 产生明确的持久化失败或未确认状态。接收方 SHALL 使用稳定消息 ID 对重复投递进行持久化去重。

#### Scenario: Receiver acknowledges a persisted message
- **WHEN** 接收方成功校验并持久化一条完整消息
- **THEN** 接收方返回该消息 ID 的确认
- **AND** 发送方把本地对应消息标记为已送达

#### Scenario: Duplicate message is retried after reconnect
- **WHEN** 接收方再次收到已经持久化的同一发送设备和消息 ID
- **THEN** 接收方不会创建或展示第二条消息
- **AND** 可以再次返回该消息 ID 的接收确认

#### Scenario: Connection drops before receipt
- **WHEN** 消息发送期间连接断开且发送方没有收到接收确认
- **THEN** 发送方持久化该次发送为失败或未确认
- **AND** 系统不会在重新连接后自动补发该消息

#### Scenario: Application stops during an in-flight send
- **WHEN** 应用在消息已写入本地但尚未收到接收确认时退出
- **THEN** 应用重启后显示该消息为未确认或失败
- **AND** 不会在后台自动恢复发送

### Requirement: Message history remains available offline

发送方和接收方 SHALL 在各自本地持久化完整消息正文、对端设备 ID、方向、发送时间、接收时间、送达状态和未读状态。历史 SHALL 按设备身份聚合而不依赖当前会话或传输类型，并在设备离线和应用重启后继续可读，直至用户在本机删除。消息历史 MUST NOT 自动加入账号配置同步或充当其他设备的离线中继队列。

#### Scenario: User views history while peer is offline
- **WHEN** 对端设备断开后用户打开该设备的消息界面
- **THEN** 系统从本地存储显示此前完整收发的消息
- **AND** 发送控件保持禁用

#### Scenario: Application restarts
- **WHEN** 应用进程退出并重新启动
- **THEN** 系统恢复本地持久化的消息历史、送达状态和未读状态

#### Scenario: Connection transport changes
- **WHEN** 同一设备先通过 TLS Session、后通过 WebRTC 或相反方式连接
- **THEN** 系统在同一设备消息历史中显示两种传输期间的消息
- **AND** 不因传输类型变化创建重复的设备对话

#### Scenario: User deletes local conversation history
- **WHEN** 用户确认删除某设备的本地消息历史
- **THEN** 系统从本机删除该设备的消息正文、状态和未读计数
- **AND** 不向对端发送远程删除命令

#### Scenario: Account configuration sync runs
- **WHEN** 系统同步账号配置或设备可信列表
- **THEN** 消息正文和消息历史不会被上传为账号配置

### Requirement: Offline devices have no outgoing queue

系统 SHALL 在 UI 和传输服务两层检查目标设备实时在线连接状态。设备离线时 MUST 禁止创建新的待发送消息、禁止进入发送队列，并且 MUST NOT 通过服务器、账号服务或稍后自动重连进行延迟投递。

#### Scenario: User attempts to send while offline
- **WHEN** 目标设备当前没有存活且已认证的消息端点
- **THEN** 消息输入的发送操作不可用
- **AND** 绕过 UI 的发送请求返回设备离线错误且不创建待发送记录

#### Scenario: Device reconnects after being offline
- **WHEN** 设备重新建立已认证连接
- **THEN** 系统重新启用发送操作
- **AND** 离线期间没有任何消息被自动投递

#### Scenario: User explicitly retries an unconfirmed message
- **WHEN** 设备重新在线后用户明确重试一条失败或未确认消息
- **THEN** 系统可以使用原稳定消息 ID 再次发送
- **AND** 接收方通过持久化去重避免重复历史记录

### Requirement: Message UI separates offline history from online sending

系统 SHALL 为有本地历史或当前在线的设备提供消息界面。界面 SHALL 始终显示持久化历史，并仅在当前存在存活且已认证的消息端点时启用输入与发送；连接状态变化 SHALL 立即反映到发送能力。界面 SHALL 根据当前可用窗口尺寸调整会话列表密度、内容留白和消息正文宽度，不得按平台名称采用固定布局。

#### Scenario: User opens messaging for a connected device
- **WHEN** 用户从当前已连接设备进入消息界面
- **THEN** 系统显示该设备的持久化消息历史
- **AND** 启用消息输入与发送操作

#### Scenario: Device disconnects while messaging is open
- **WHEN** 消息界面打开期间设备连接断开
- **THEN** 系统立即禁用发送操作并显示设备已离线
- **AND** 已持久化历史仍可继续查看

#### Scenario: Incoming message is persisted while conversation is closed
- **WHEN** 在线设备发来消息且其消息界面当前未打开
- **THEN** 系统持久化该消息并增加持久化未读计数
- **AND** 用户稍后离线打开历史时仍能看到该消息

#### Scenario: Multiple authenticated transports are online
- **WHEN** 同一设备同时存在多个存活且已认证的消息端点
- **THEN** 消息界面不显示传输通道选择控件，并优先沿用当前存活端点
- **AND** 当前端点失效时，系统从排序后的在线端点中确定性选择一个端点，而不隐式建立连接或排队发送

#### Scenario: User edits a message draft
- **WHEN** 用户在消息输入框中输入正文
- **THEN** 输入框辅助信息以“当前使用 / 总数”格式显示正文的 Unicode 字符数量，而不是 UTF-8 字节数量
- **AND** 1 MiB 上限仍按 UTF-8 编码后的字节数校验

#### Scenario: Messaging is opened in a compact window
- **WHEN** 用户在窄屏手机、窄桌面窗口或竖屏 Web 视口中打开设备消息
- **THEN** 会话入口保持单列，消息正文和编辑器在可用宽度内完整显示
- **AND** 发送、复制、重试和删除操作仍保持可触达

#### Scenario: Messaging is opened in a wide window
- **WHEN** 用户在平板横屏、宽桌面窗口或宽 Web 视口中打开设备消息
- **THEN** 会话入口可使用自适应多列并限制整体可读宽度
- **AND** 消息历史与编辑器居中限宽，不随窗口无限拉伸
