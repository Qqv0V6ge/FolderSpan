# 设置与数据同步白名单

本文件记录允许同步到服务器的普通设置项与数据快照。同步代码中的普通设置白名单必须与这里“允许同步的普通设置”表保持一致；数据快照 key 必须与手动数据同步分类保持一致。

同步数据分两类：

- 普通设置：逐个 `settings.*` key 同步，用户修改某个白名单设置时只上传该 key。
- 数据快照：书签、收藏、设备、角色、网络盘、同步任务等不是 `settings.*` 单项设置，而是以一个快照 key 上传当前分类配置。

同步策略：普通设置按远端 `updatedAt` 增量拉取，远端较新的白名单值会覆盖本机已有值；手动从指定设备完整拉取时同样以远端值覆盖。用户修改某个白名单设置时，仅上传该设置 key，不批量更新整份白名单快照。

失败补推策略：更新什么，下次只补推什么。白名单设置或数据快照上传失败时记录具体 key；下次启动、登录或同步前先补推这些待上传 key，成功后清除对应记录；如果补推仍失败，本轮不从服务器拉取远端值，避免远端旧值覆盖本机新值。

## 手动数据同步分类

| 页面分类 | 上传方式 | 远端 key | 内容范围 | 备注 |
|---|---|---|---|---|
| 设置 | 普通设置项 | 下方 `settings.*` key | 可跨设备共享的应用设置 | 逐项上传和拉取，详见“允许同步的普通设置”。 |
| 书签 | 数据快照 | `app.bookmarks.snapshot` | 侧边栏书签 | 按书签类型和路径合并去重。 |
| 收藏 | 数据快照 | `app.favorites.snapshot` | 文件收藏列表 | 不同步 Share 协议收藏。 |
| 设备 | 数据快照 | `app.devices.snapshot` | 已保存设备、连接配置、接收分享路径 | 不包含本机 `settings.deviceId`。 |
| 角色 | 数据快照 | `app.roles.snapshot` | 设备访问角色、权限、角色-权限关联 | 远端快照为空时不覆盖本机角色配置。 |
| 网络盘 | 数据快照 | `app.networks.snapshot` | 网络存储配置 | 密码和扩展配置以本地 AES-GCM 密文同步；上传前会同时写入同一 target 的包装密钥。 |
| 同步任务 | 数据快照 | `app.syncTasks.snapshot` | 自动同步任务配置 | 运行时间、下次执行时间、最近状态和消息会重置为初始状态。 |
| （随配置目标） | 数据快照 | `app.secrets.dataEncryptionKey` | 当前配置目标的凭据包装密钥 | 不出现在手动同步勾选列表，也不是普通 `settings.*` 项。使用另一台设备的配置时随该 target 拉取并写入本机金库，以便解开网盘/WebRTC 密文。 |

## 允许同步的普通设置

| Key | 上传 | 类型 | 默认值 | 页面/功能 | 备注 |
|---|---|---|---|---|---|
| `settings.fileShare.enabled` | 是 | boolean | `true` | 文件共享 | 文件共享开关。 |
| `settings.fileShare.port` | 是 | int | `12040` | 文件共享 | 文件共享端口。 |
| `settings.fileShare.accessKey` | 是 | string | `{"enabled":false,"value":""}` | 文件共享 | `X-FolderSpan-Key` 访问保护的开关和值；远端较新配置会整体覆盖本机配置。 |
| `settings.fileShare.autoAuthorizeDeviceConnect` | 是 | boolean | `false` | 文件共享 | 自动授权设备连接请求。 |
| `settings.fileShare.autoAuthorizeRoleId` | 是 | long | `2` | 文件共享 | 自动授权默认角色。 |
| `settings.easyFileShare.port` | 是 | int | `1204` | 快捷分享 | 快捷分享端口。 |
| `settings.easyFileShare.autoStart` | 是 | boolean | `false` | 快捷分享 | 应用启动时自动启动快捷分享。 |
| `settings.easyFileShare.autoStartOnOpen` | 是 | boolean | `false` | 快捷分享 | 打开页面时自动启动快捷分享。 |
| `settings.easyFileShare.sharePaths` | 是 | stringList | `[]` | 快捷分享 | 默认分享路径列表。 |
| `settings.easyFileShare.autoStopOnExit` | 是 | boolean | `false` | 快捷分享 | 离开页面时自动关闭服务。 |
| `settings.easyFileShare.autoApprove` | 是 | boolean | `false` | 快捷分享 | 自动同意快捷分享请求。 |
| `settings.easyFileShare.passwordAccess` | 是 | boolean | `false` | 快捷分享 | 链接访问密码开关。 |
| `settings.easyFileShare.encryption` | 是 | boolean | `false` | 快捷分享 | 链接传输加密开关。 |
| `settings.easyFileShare.hideFile` | 是 | boolean | `false` | 快捷分享 | 链接分享隐藏文件开关。 |
| `settings.easyFileShare.tapToSendOnDevice` | 是 | boolean | `true` | 快捷分享 | 点按设备立即发送。 |
| `settings.easyFileShare.deviceHideFile` | 是 | boolean | `false` | 快捷分享 | 分享到设备时默认隐藏文件。 |
| `settings.easyFileShare.allowDeviceShare` | 是 | boolean | `true` | 快捷分享 | 允许其他设备分享给我。 |
| `settings.easyFileShare.allowUpload` | 是 | boolean | `false` | 快捷分享 | 链接分享页面允许上传。 |
| `settings.easyFileShare.autoUpdateLinkShareFiles` | 是 | boolean | `false` | 快捷分享 | 当前分享列表变化后自动更新已授权链接设备文件。 |
| `settings.easyFileShare.autoUpdateDeviceShareFiles` | 是 | boolean | `false` | 快捷分享 | 当前分享列表变化后自动更新已分享设备文件。 |
| `settings.file.remoteOpenConfirm` | 是 | boolean | `true` | 安全/远程打开 | 远程打开文件前确认。 |
| `settings.root.requestOnStartup` | 是 | boolean | `false` | 权限 | 启动时请求 Root 权限。 |
| `settings.file.filter.showHidden` | 是 | boolean | `false` | 文件 | 显示隐藏文件。 |
| `settings.file.view.grid` | 是 | boolean | `false` | 页面 | 文件页面网格视图。 |
| `settings.appearance.themeMode` | 是 | string | `System` | 外观 | 主题模式枚举名。 |
| `settings.appearance.dynamicColor` | 是 | boolean | `true` | 外观 | Android 动态取色开关。 |
| `settings.appearance.customColor.enabled` | 是 | boolean | `false` | 外观 | 自定义颜色开关。 |
| `settings.appearance.customColor.seed` | 是 | string | `#6750A4` | 外观 | Material 3 种子色。 |
| `settings.network.filter.protocol` | 是 | string | `ALL` | 网络 | 网络协议筛选。 |
| `settings.drawer.expand.bookmark` | 是 | boolean | `true` | 侧边栏 | 书签分组展开状态。 |
| `settings.drawer.expand.device` | 是 | boolean | `true` | 侧边栏 | 设备分组展开状态。 |
| `settings.drawer.expand.share` | 是 | boolean | `true` | 侧边栏 | 分享分组展开状态。 |
| `settings.drawer.expand.network` | 是 | boolean | `true` | 侧边栏 | 网络分组展开状态。 |
| `settings.drawer.expand.sync` | 是 | boolean | `true` | 侧边栏 | 同步分组展开状态。 |
| `settings.drawer.show.device` | 是 | boolean | `true` | 页面 | 显示设备页面入口。 |
| `settings.drawer.show.network` | 是 | boolean | `true` | 页面 | 显示网络页面入口。 |
| `settings.drawer.show.sync` | 是 | boolean | `true` | 页面 | 显示同步页面入口。 |

## 不同步的设置

本机设备名不是设置项：应用按平台实时读取或派生名称，不提供编辑入口，也不写入或同步 `settings.deviceName`。

| Key/范围 | 原因 |
|---|---|
| `settings.deviceId` | 设备唯一标识，本机态。首次非空写入后写一次，覆盖、删除、清空设置都不能改掉已分配 ID。 |
| `settings.crypto.*` | 本机金库中的凭据包装密钥副本；不以普通设置项同步。跨设备共用配置时走 `app.secrets.dataEncryptionKey`。 |
| `settings.app.lastCrash`、`settings.app.lastSessionForeground` | 运行状态和诊断信息，不属于用户偏好。 |
| `settings.file.remoteOpenDownloadDir` | 本机绝对路径，跨设备不可移植。 |
| `settings.fileShare.accountDeviceAutoConnectEnabled` | “自动连接我的设备”开关；默认关闭，只影响当前设备的局域网发现与同账号临时授权，不能被其它设备的 Pro 设置同步覆盖。 |
| `settings.bookmark.defaultInitialized` | 初始化状态，不属于用户偏好。 |
| `settings.notification.startupPermissionReminderHandled` | 本机提醒状态。 |
| `settings.notification.announcementReadCutoff` | 公告已读水位线，只表示当前设备看到哪个发布时间，不跨设备同步。 |
| `settings.app.onboarding.completed` | 首次引导状态，本机态。 |
| `settings.sync.lastTimestamp` | 全局同步游标，不属于用户偏好。 |
| `settings.sync.entryTimestamps` | 按配置/快照条目维护的本机增量游标表；普通设置项直接使用对应设置 key 记录，不上传到服务器。 |
| `settings.sync.pendingSettingKeys`、`settings.sync.pendingSnapshotKeys` | 本机失败补推队列，不上传到服务器。 |
| `settings.sync.manualData.selectedCategories` | 手动数据同步页分类开关状态，仅用于本机记住勾选项，不上传到服务器。 |
| `settings.editor.showLineNumbers`、`settings.editor.automaticWrap` | 编辑器本机显示偏好；自动换行与设备屏幕尺寸相关，不跨设备同步。 |
