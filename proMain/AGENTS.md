# Pro Source Set Guide

本文件适用于仓库根目录下的 `proMain` 及其子目录。这里是 FolderSpan 的可拆卸 Pro 功能源码，通过独立的 `:proMain` Kotlin Multiplatform 模块接入多平台构建。

## Agent 专用说明

- 本工作区内助手所有回复必须使用中文（`zh-CN`），包括过程更新、解释与最终答复。
- 若用户要求切换其他语言，需先与用户确认。
- 完成用户回答或任务后，必须先执行以下飞书通知脚本。命令需在前台执行，最多等待 1 秒；失败或超时后无需重试：
  ```bash
  timeout 1s /home/webb/.codex/bin/feishu-private-notify.sh "具体执行的任务" || true
  ```
- 飞书通知脚本执行后，再执行以下系统通知命令。命令同样在前台执行，最多等待 1 秒；失败或超时后无需重试：
  ```bash
  timeout 1s notify-send "任务已完成" "具体执行的任务" || true
  ```
- 上述命令中的任务描述必须替换成实际完成的任务，不要保留占位符。

## 项目结构与模块组织

- 根项目模块包括 `:core`、`:proMain`、`:app:shared`、`:app:androidApp`、`:app:desktopApp`、`:app:webApp` 和 `:server`；本目录是独立的 Kotlin Multiplatform 模块。
- 根目录 `settings.gradle.kts` 通过 `include(":proMain")` 注册模块；`proMain/build.gradle.kts` 将 `proMain/kotlin` 作为 `commonMain` 源目录，并配置 Android、JVM、iOS、JS、Wasm 目标。
- Pro 代码根包为 `com.folderspan.pro`，源码位于 `kotlin/app/filemanager/pro`。
- 目录职责：``
  - `core/`：通用基础能力，例如网络配置、请求签名、会话存储、通用结果模型和 Pro 级 UI 组件。
  - `data/`：远程 API、DTO、mapper、repository 实现和分页数据源。
  - `domain/`：业务模型、repository 接口和 use case。
  - `presentation/`：Navigation 3 路由、页面、ViewModel、UI state 和可复用界面组件。
  - `di/`：Pro 功能依赖组装，优先复用项目既有 Koin/服务注册模式。

## 编码风格与命名约定

- Kotlin 使用 4 空格缩进，遵循项目既有 Compose Multiplatform 写法。
- 包名保持在 `com.folderspan.pro.*` 下，按 `core`、`data`、`domain`、`presentation`、`di` 分层放置。
- `@Composable`、页面和类型使用 `PascalCase`；函数、属性、事件处理器使用 `camelCase`；常量使用 `UPPER_SNAKE_CASE`。
- ViewModel、UI state、事件和 route 命名需表达业务语义，例如 `LoginViewModel`、`MarketplaceUiState`、`ProfileRoutes`。
- Pro 逻辑应尽量保持在 `proMain` 可共享范围内；确实需要平台 API 时，先评估能否放到 `core` 既有平台 source set，再用明确边界接入。
- 网络、序列化、本地设置、分页和依赖注入优先复用项目已有的 Ktor、kotlinx.serialization、multiplatform-settings、Paging、Koin 模式。

## 分层与依赖边界

- `presentation` 只通过 domain repository/use case 或注入服务访问业务能力，不直接拼接底层 HTTP 请求。
- `domain` 不依赖 Compose、Ktor client 细节或具体存储实现；接口和模型应便于测试。
- `data` 负责 DTO、API 调用、错误映射和 repository 实现，不泄漏服务器原始异常到 UI。
- `core/network` 维护网关配置、运行时配置、设备身份、签名和 `HttpClient` 创建；不要在页面或 ViewModel 中复制请求签名逻辑。
- 会话令牌和敏感身份信息只能通过 `SessionManager`、`AuthSessionStore` 等既有会话抽象流转，禁止写入日志。

## 页面交互与状态规范

- 可见文案面向最终用户，避免“接口异常”“服务未启动”“Unknown error”等开发侧表达。
- 所有文字和提示文案必须让普通用户能看懂，避免直接出现 token、接口、响应、ID、请求配置、登录态等专业或技术术语。
- 页面正文、卡片标题、空状态和提示文案不得重复使用当前页面标题；需要改用更具体的动作、对象或状态描述。
- 页面至少考虑加载中、空状态、失败、无权限、未登录、登录过期、网络错误，以及数据过多/过少时的布局表现。
- 登录、注册、找回密码、修改密码、个人资料、市场等流程必须明确按钮 loading、成功反馈、错误提示和可重试入口，避免重复提交。
- 涉及退出登录、删除、覆盖数据、支付、授权变更等特殊或不可逆操作时必须提供二次确认。
- Compose 页面需覆盖移动端、桌面宽屏、暗色模式和不同分辨率；优先使用项目已有响应式模式和 Material3 window size class。
- 导航变更遵循 AndroidX Navigation 3 现有结构，保持 route 可序列化、返回栈语义清楚。

## 测试指南

- Pro 纯逻辑测试优先放在 `proMain/src/commonTest/kotlin`；涉及 JVM 文件系统、平台异常或本地实现时放在 `proMain/src/jvmTest/kotlin`。
- 修复缺陷时优先添加聚焦回归测试，再修改生产代码。
- 网络层测试优先使用 Ktor MockEngine 或 repository 级 fake，避免真实网络请求。
- 测试名描述行为，例如 `loginFailureShowsHelpfulMessage`、`expiredSessionClearsStoredToken`。
- 提交前根据改动范围从仓库根目录运行相关 `core` 或 app 级测试/编译任务；不要在 `proMain` 目录内直接运行构建命令。

## 安全与配置提示

- 不提交密钥、令牌、私有证书、本机路径或 `local.properties`。
- API 地址、网关路径、请求签名和运行时配置集中维护在现有网络配置类中。
- 日志不得包含 token、签名材料、密码、验证码、设备私钥、完整鉴权头或用户敏感资料。
- 服务器错误需要映射成用户可理解的提示；内部错误细节只保留在受控日志中。

## 提交与文档

- 提交信息使用 Conventional Commits，例如 `feat(pro): ...`、`fix(auth): ...`、`refactor(shared): ...`。
- PR 需说明变更摘要、影响平台、已运行测试命令；可见 UI 变化需附截图或录屏。
- 修改或新增 Markdown 文档时，同步更新仓库根目录 `md_descriptions_paths.md`。
