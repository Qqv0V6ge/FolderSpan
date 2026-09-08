# 网络表单

该目录用于存放网络新增/编辑页面复用的表单组件（基于 GridList 的 LazyGridScope 扩展）。

## 新增表单
- 在该目录创建新的 `*.kt` 文件。
- 包名保持为 `com.folderspan.ui.screen.network.form`。
- 组件优先使用 `internal` 以限定使用范围。
- 表单函数建议定义为 `LazyGridScope` 扩展，便于直接插入 `GridList`。
- 分组标题使用 `NetworkEditSectionTitle`，并通过 `GridItemSpan(maxLineSpan)` 独占一行。

## 接入位置
- 在 `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/network/NetworkAddScreens.kt` 中接入新增表单。
- 在 `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/network/NetworkEditScreen.kt` 中接入编辑表单。
- 通过 `when` 按协议或类型切换页面。

## 布局建议
- 表单布局放在本目录；外层页面负责 padding/scroll 与 GridList 间距。
- TextField 默认占一列，由屏幕列数自动决定排列。
