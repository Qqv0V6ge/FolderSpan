# 组件规范

本目录用于存放共享的 Jetpack Compose UI 组件。新增或调整组件时请遵循以下规范。

## 目录组织
- 按组件类型分组，放在对应的子目录中。
- 包名与目录路径保持一致。

示例：
- `components/dialog/` 用于对话框（基于 AlertDialog 的组件）。
- `components/drawer/` 用于抽屉相关区域与条目。
- `components/buttons/` 用于按钮组与按钮相关组件。
- `components/appbar/` 用于顶部栏与路径切换组件。
- `components/file/` 用于文件卡片、列表与文件相关组件。

如果新类型不适合现有目录，新增一个目的清晰、职责单一的子目录。

## @Preview 规则
- 可用简单参数渲染的 `@Composable`，应补充 `@Preview`。
- 预览中使用轻量样例数据与空回调。
- 需要 Koin（`koinInject`）或复杂依赖环境的组件，不添加 `@Preview`，不要为了预览构建运行时环境。

## 实现说明
- 预览函数尽量使用 `private`。
- 预览中避免复杂状态或副作用。
