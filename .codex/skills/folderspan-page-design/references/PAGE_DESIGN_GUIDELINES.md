# FolderSpan 页面设计规范

本规范用于 FolderSpan 的 Compose Multiplatform（CMP）页面。Android、iOS、Desktop 与 Web 的页面共享同一套 `commonMain` Material 3 Widget 树；平台入口只负责承载共享 `App()` 和提供平台能力，页面不得直接堆叠大量一次性 UI。

## 0. 技术边界：这是 CMP 规范，不是 Web 规范

- 页面 UI 统一使用 Kotlin、`@Composable`、Compose `Modifier` 与 `androidx.compose.material3`。
- 浏览器只是 CMP 的一个运行目标，由 `ComposeViewport { App() }` 承载；共享页面禁止使用 HTML、CSS media query、DOM 布局、`@material/web` 或 Material Web Components。
- Android、iOS、Desktop、Web 不各自实现一套页面。布局根据当前 Compose 窗口的宽度、高度、不可占用分隔区域和输入能力适配，不根据平台名或设备型号适配。
- 只有系统集成（文件选择器、窗口控制、原生分享、折叠信息读取等）允许位于平台 source set，并通过接口或 `expect`/`actual` 向 `commonMain` 提供能力。

| 目标 | 平台承载方式 | 共享页面实现 |
|---|---|---|
| Android | `ComponentActivity.setContent { App() }` | `app/shared/src/commonMain` |
| iOS | `ComposeUIViewController { App() }` | `app/shared/src/commonMain` |
| Desktop | Compose `Window { App() }` | `app/shared/src/commonMain` |
| Web（JS/Wasm） | `ComposeViewport { App() }` | `app/shared/src/commonMain` |

## 1. 强制原则

1. 只使用 `androidx.compose.material3` 组件，不得混用 Material 2。
2. 页面必须由 Widget 组合：令牌 → 基础 Widget → 组合 Widget → 页面区域 Widget → 页面模板。
3. 颜色、排版和形状必须来自 `MaterialTheme.colorScheme`、`MaterialTheme.typography` 与 `MaterialTheme.shapes`；标准 Material 3 组件优先使用默认尺寸，页面布局需要的间距和尺寸直接使用清晰的 Compose `Dp` 值，禁止创建页面私有令牌对象或自定义尺寸 `CompositionLocal`。
4. 页面必须支持五档窗口宽度、可滚动的矮窗口，以及宿主提供的双屏接缝/折叠铰链间隔。
5. 共享页面实现优先位于 `app/shared/src/commonMain`；通用 UI 组件同时优先复用 `core/src/commonMain/kotlin/com/folderspan/ui/components`；平台 API 只能放在对应平台 source set。
6. 状态由页面入口接入，Widget 只接收不可变数据与事件回调，不直接访问 ViewModel、服务或全局状态。
7. 所有交互必须支持无障碍语义、至少 48dp 的触控目标，以及目标平台具备的触摸、键盘、鼠标/触控板等输入方式。

## 2. Widget 定义与分层

本项目中的 Widget 指职责单一、可组合、可独立预览的 `@Composable` 函数。

实现新 Widget 前必须先检查并复用以下组件目录，不得在页面内重复实现已有组件：

- `app/shared/src/commonMain/kotlin/com/folderspan/ui/components`
- `core/src/commonMain/kotlin/com/folderspan/ui/components`

| 层级 | 职责 | 示例 |
|---|---|---|
| 令牌 | 提供颜色、排版、形状及标准控件默认尺寸 | `MaterialTheme`、Material 3 组件默认值 |
| 基础 Widget | 包装一个 Material 3 组件或一个最小交互单元 | 图标按钮、属性行 |
| 组合 Widget | 组合多个基础 Widget，完成一个独立功能 | 文件列表项、搜索栏 |
| 页面区域 Widget | 形成可复用的页面区域 | 文件列表面板、文件详情面板 |
| 页面模板 | 只定义 Scaffold、导航槽和区域布局 | 列表—详情模板 |
| 页面入口 | 接入状态与回调，并组合模板及区域 | `PageDesignReferencePage` |

每个可复用 Widget 必须：

- 提供 `modifier: Modifier = Modifier`，并把它应用到根节点。
- 通过 slot 接收可变内容，通过参数接收数据，通过回调暴露事件。
- 不在 Composable 内硬编码颜色、字号或字体粗细。
- 不读取 ViewModel；只有页面入口可以连接状态容器。
- 为简单状态提供 `@Preview`，复杂运行时依赖不得为了预览而伪造完整容器。
- 按“是什么”命名，不按所在页面或视觉颜色命名。

## 3. Material 3 令牌

### 颜色

- 页面背景使用 `surface` 或项目 Scaffold 默认背景。
- 页面 body、pane、连续列表和元数据默认沿用页面 `surface`，不为分组叠加 `surfaceContainer*` 背景；选中项使用 `secondaryContainer`。
- 容器上的内容必须使用对应的 `on*` 颜色，例如 `secondaryContainer` 配 `onSecondaryContainer`。
- 错误区域使用 `errorContainer` 与 `onErrorContainer`；危险操作使用 `error`。
- 需要区分连续内容时，可适量使用 Material 3 `HorizontalDivider` / `VerticalDivider`，颜色使用 `outlineVariant`；不得为每个区域重复加线，也不得以阴影作为默认层级表达。
- 禁止在 Widget 内出现业务用途的 `Color(0x...)`、`Color.Black` 或 `Color.White`。

### 排版

- 页面主标题：`titleLarge` 或 `headlineSmall`。
- 区域标题：`titleMedium`，并添加 `heading()` 语义。
- 列表项标题：`bodyLarge` 或 Material 3 `ListItem` 默认样式。
- 辅助信息：`bodyMedium` / `bodySmall`，颜色使用 `onSurfaceVariant`。
- 按钮与标签使用 Material 3 默认 label 样式，禁止单独指定字号。

### 形状和层级

- 页面 body、pane、连续列表和元数据采用扁平结构，不使用 `Card` 或带圆角、tonal 背景的 `Surface` 包裹。
- 本规范页面默认不使用卡片表达分组；只有产品需求明确要求独立、可单独操作或搬移的信息实体时，才可评估使用 `Card`。
- 优先通过标题、排版、留白和适量分隔线表达层级；只有选中、错误、强调或浮层等明确语义才使用对应 tonal container 或阴影。
- 确需形状的 Material 3 控件统一使用 `MaterialTheme.shapes`，不为单一页面创造任意圆角。

### 间距

间距使用 4dp 基准；标准 Material 3 组件的触控尺寸优先沿用组件默认值，不在页面重复指定 48dp，也不为下列少量标准值额外创建尺寸令牌体系：

| Compose `Dp` 值 | 用途 |
|---:|---|
| `4.dp` | 图标与紧邻信息 |
| `8.dp` | 组件内部紧凑间距 |
| `16.dp` | 默认组件内部留白 |
| `24.dp` | 页面区域内部留白、区域间距 |
| `32.dp` | 大区域分隔 |

## 4. 多平台、多窗口与多屏幕

共享页面使用仓库的 `WindowSizeClass` 与 `calculateWindowSizeClass`。以下断点是 Compose `Dp` 窗口约定，不是 CSS 媒体查询；窗口旋转、分屏或缩放后必须即时重排。

| 宽度级别 | 范围 | 页面行为 | body 外边距 |
|---|---:|---|---:|
| Compact | `< 600dp` | 单栏；列表与详情通过页面状态切换 | 0dp |
| Medium | `600–839dp` | 双栏；窄列表 + 弹性详情 | 0dp |
| Expanded | `840–1199dp` | 标准双栏或 supporting pane | 0dp |
| Large | `1200–1599dp` | 双栏/三栏；pane 填满可用宽度，长正文可单独限制行宽 | 0dp |
| Extra-large | `≥ 1600dp` | 双栏/三栏；增宽列表或增加 supporting pane | 0dp |

宽度决定导航结构、pane 数量和可读内容宽度。高度没有产生不同布局行为时，不得仅为保存断点而创建 `WindowHeightClass`；矮窗口通过区域滚动保证关键内容和操作可访问。只有产品行为确实随高度变化时，才增加对应的高度状态与测试。

页面入口接收平台无关的窗口信息，不接收平台枚举：

```kotlin
data class PageWindowInfo(
    val widthSizeClass: WindowSizeClass,
    val separatingPaneGap: Dp = 0.dp,
)
```

`separatingPaneGap` 表示双屏接缝或纵向折叠铰链的不可占用宽度。平台宿主负责读取原生窗口特征并转换成 Compose `Dp`；共享列表—详情模板把该区域作为两个 pane 之间的间隔。普通窗口传 `0.dp`。

强制要求：

- `Scaffold` 内容区域、页面 body 和 pane 必须依次使用 `fillMaxSize()` / `weight()` 占满 `Scaffold` padding 与不可占用分隔区域之外的全部空间，不得添加页面级 start、end 或 bottom 外边距。
- 只有详情 pane 内的长篇正文文字本身可以限制可读行宽；pane、列表、元数据、操作区和 supporting 区域仍须填满其父级可用空间，不得给整个区域设置 840–1040dp 最大宽度。
- 内容较高时由区域自身滚动，不依赖固定屏幕高度。
- `Scaffold` 的 padding 必须传递给页面内容；系统栏、刘海和 IME 由宿主的 `WindowInsets` 处理。
- 页面 `body` 自身禁止设置顶部 padding；顶部空间只能来自 `Scaffold` 传入的内容 padding 或宿主处理的 `WindowInsets`，不得在 `body` 上重复叠加。
- 折叠屏或双屏不得把关键内容放在接缝/铰链区域；存在纵向分隔区域时，列表与详情分别落在两个 pane。
- 平台差异只能改变能力接入或交互增强，不能复制页面结构。例如鼠标平台可增加悬停和右键菜单，但同一动作仍应能通过标准 Material 3 控件完成。
- 不允许使用 `PlatformType`、User-Agent、设备品牌、平台名称或横竖屏布尔值决定页面 pane；这些信息必须先归一化为窗口能力。

## 5. 页面状态和数据流

页面入口接收一个 UI state，并向下传递最小数据：

```text
State / ViewModel
       ↓ state + callbacks
Page Entry
       ↓
Page Template
       ├── List Pane Widget
       │      └── List Item Widget
       └── Detail Pane Widget
              ├── Metadata Widget
              └── Action Widget
```

- 加载态使用 Material 3 进度组件，并设置 `LiveRegionMode.Polite`。
- 错误态使用 `errorContainer`，提供可恢复时的“重试”动作。
- 空态说明下一步操作，不使用空白页面表达“没有数据”。
- 标准加载、错误、空数据与内容状态必须优先复用 `core/src/commonMain/kotlin/com/folderspan/ui/components/pagestate` 中的 `PageStateLayout`、`PageViewState` 和 `resolvePageViewState`，并按需通过 slot 复用同一 core 组件体系中的 `LoadingBase`、`ErrorBase`、`ErrorEmptyData`；页面内不得重复编写互斥状态 `when` 分支。
- 选中态同时通过颜色和 `selected` 语义表达，不能只依赖颜色。
- 列表使用稳定 key；大数据集使用 `LazyColumn` / `LazyGrid`。
- 临时 UI 状态尽量下沉；业务状态由页面入口提升。

## 6. 无障碍与输入

- Material 3 Button、IconButton、ListItem 等标准组件优先于自制点击区域。
- 图标按钮必须提供用途明确的 `contentDescription`；与文本重复的装饰图标使用 `null`。
- 页面和区域标题添加 `heading()`，动态错误使用 Assertive，普通状态使用 Polite。
- 自定义可点击 Widget 必须提供正确 role、状态描述和至少 48×48dp 的目标尺寸。
- 阅读顺序应与视觉顺序一致；双栏布局需要保持列表在详情之前。
- 正文和普通文字满足至少 4.5:1 对比度，大文字和必要边界至少 3:1。
- 具备指针输入的平台，其悬停提示不能成为唯一的信息来源；具备键盘输入的平台，其焦点必须可见。

## 7. 标准页面骨架

下面的骨架展示页面只负责组合 Widget。可运行实现见 `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/design/PageDesignReference.kt`。

```kotlin
@Composable
fun FileListDetailPage(
    state: FileListDetailUiState,
    windowInfo: PageWindowInfo,
    onFileSelected: (String) -> Unit,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = fileListDetailLayout(windowInfo)

    PageTemplate(
        modifier = modifier,
        topBar = {
            PageTopBarWidget(
                showBack = layout.singlePane && state.selectedFile != null,
                onBack = onBackToList,
            )
        },
    ) { contentModifier ->
        if (layout.singlePane) {
            if (state.selectedFile == null) {
                FileListPaneWidget(
                    files = state.files,
                    onFileSelected = onFileSelected,
                    modifier = contentModifier,
                )
            } else {
                FileDetailPaneWidget(
                    file = state.selectedFile,
                    modifier = contentModifier,
                )
            }
        } else {
            Row(
                modifier = contentModifier.fillMaxSize(),
            ) {
                FileListPaneWidget(
                    files = state.files,
                    onFileSelected = onFileSelected,
                    modifier = Modifier
                        .width(layout.listPaneWidth)
                        .fillMaxHeight(),
                )
                if (layout.paneSpacing > 0.dp) {
                    Spacer(Modifier.width(layout.paneSpacing).fillMaxHeight())
                } else {
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                FileDetailPaneWidget(
                    file = state.selectedFile,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}
```

## 8. Preview、测试与验收

每个新页面至少覆盖 Compact、Medium、Expanded、Large、Extra-large 五档宽度，并额外覆盖一个横向矮窗口。推荐预览为 360×800、640×360、720×900、1024×768、1366×900、1600×900dp。预览代表窗口能力，不代表某个固定平台。检查：

- 页面无横向或纵向溢出，长内容可以滚动。
- Compact 的列表/详情切换完整，返回动作可用。
- Medium+ 双栏比例合理，列表与详情都可独立理解。
- 页面区域保持扁平，不以 Card 或圆角 tonal Surface 包裹 pane、连续列表和元数据；分隔线数量适度且使用 `outlineVariant`。
- 所有宽度档位的页面 body 与 pane 填满 `Scaffold` 提供的可用空间；只有长篇正文文字本身可以限制可读行宽。
- 矮窗口下关键动作仍可通过滚动访问；双屏/折叠间隔没有内容覆盖。
- 亮色、暗色与动态配色下没有硬编码颜色造成的不可读内容。
- 放大字体后信息不被裁切，控件仍能操作。
- 键盘、鼠标和触摸均可完成主要流程。
- 布局选择逻辑有 `commonTest` 单元测试，并分别编译 JVM、Android、JS 与 Wasm 目标；需要浏览器执行环境的测试另行配置 Chrome。

## 9. 禁止事项

- 页面直接访问服务层或在区域 Widget 内注入 ViewModel。
- 页面内重复实现已有 `components/` Widget。
- 页面自行重复实现 `PageStateLayout` 已覆盖的加载、错误、空数据与内容状态切换。
- 在页面 `body` 上设置顶部 padding。
- 用 `Card` 或带圆角、tonal 背景的 `Surface` 包裹页面 body、pane、连续列表项或元数据行。
- 以密集边框或分隔线替代清晰的排版、留白和信息层级。
- 混用 Material 2 与 Material 3。
- 硬编码业务颜色、字号、任意圆角或散落间距。
- 只为手机编写布局，或在超宽屏拉伸正文。
- 用 `Box.clickable` 重做已有 Material 3 Button/ListItem 且遗漏语义。
- 通过设备名称、平台名称或方向判断布局，代替窗口宽度、高度与分隔区域能力。
- 在 Web 目标为共享页面另写 HTML/CSS/Material Web 版本。
