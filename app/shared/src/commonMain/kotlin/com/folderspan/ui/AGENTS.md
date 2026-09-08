# UI Agents 概览

## 目标
- 说明多端共享 Compose UI 层的职责分工。
- 明确各屏幕、状态容器与复用组件的协作方式。

## 目录结构
- `components/`：通用的 Jetpack Compose 组件（卡片、公告、对话框等）。
- `modules/`：按功能拆分的可复用 UI 模块或流程控制。
- `screen/`：使用 Voyager `Screen` 接口实现的顶层导航目的地。
- `state/`：通过 Koin 注入的 UI 状态容器。

## 核心职责
1. 业务逻辑尽量保留在 `commonMain`，平台差异放到对应 target 目录。
2. 通过 `FileState`、`FileFilterState` 等 `StateFlow`/`MutableStateFlow` 管理 UI 状态。
3. 触发文件操作、导航等副作用时，优先调用已注入的状态容器而非直接访问服务层。
4. 借助 `SnackbarHostState`、对话框与公告组件向用户反馈状态。

## 协作规范
- 屏幕使用 `koinInject()` 获取依赖，避免手动实例化状态。
- 组件应以回调暴露交互，不直接修改共享状态实例。
- 注意重组性能：使用 `remember`/`rememberSaveable` 保存 UI 层状态，并优先选择稳定的数据结构。
- 需要调试时统一通过 `LogKit` 打印日志。
- 使用 `GridList` 时：空列表用 `isEmpty` / `emptyMessage`，失败用 `errorState`；带浮动按钮时传入 `floatingActionButtonPadding = GridListFabPadding`，确保末尾卡片不会被按钮遮挡。下拉刷新与滚到底加载分别传 `onRefresh` / `onLoadMore`。
- 如果是长按事件（`onLongClick`），需要使用 `components/ContextClick.kt` 中的 `Modifier.combinedClickableWithContextClick` 扩展函数，以同时支持鼠标右键触发。
- 过滤相关 UI 统一采用 `FavoriteScreen` 风格：优先复用 `FilterSheetFrame`、`FilterSectionCard`、`FilterOptionChip` 组件，并保持筛选入口与激活态提示一致。

## 测试提示
- 纯逻辑测试放在 `commonTest`，平台相关流程在各自 target 下模拟。
- 单测可通过替换状态容器来模拟选择、过滤与 Snackbar 行为。
- 新增 ProtoBuf 模型时需验证序列化 tag 与跨端兼容性。

## 变更流程
1. 在 `commonMain` 更新或新增 Compose 组件。
2. 涉及 UI 改动的 PR 应附上截图或录屏。
3. 执行 `./gradlew :app:shared:check` 确认编译与测试通过。
4. 提交信息遵循 Conventional Commits（如 `feat(ui): ...`），PR 文档需说明行为影响。
