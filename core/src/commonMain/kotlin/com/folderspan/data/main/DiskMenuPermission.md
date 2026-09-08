# DiskMenuPermission

本文档说明 DiskMenuPermission 在应用中的含义。

## paste

当 `DiskMenuPermission.paste == true` 时，当前磁盘被视为“可粘贴/可接收复制”的目标，
同时也会开启 JVM 桌面端的拖拽复制支持。

JVM 拖拽行为（见
`core/src/jvmMain/kotlin/com/folderspan/utils/DesktopFileDropHandler.kt`）：
- 本地磁盘：拖拽文件/文件夹会打开目标目录（文件则打开其父目录）。
- 设备磁盘：拖拽文件会触发“上传确认”流程。
- 其他 `paste == true` 的磁盘：拖拽文件会复制到当前目录，
  通过 `FileState.copyTo` 任务执行。

`paste == false` 的磁盘不会接收拖拽复制到当前目录的操作。
