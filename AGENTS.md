
# 仓库指南

## 项目结构与模块组织
- `app/shared/`：Compose Multiplatform 共享 UI。共享 UI 位于 `app/shared/src/commonMain/kotlin`；与共享 UI 的 `expect` 配套的平台 `actual` 放在 `app/shared/src/<target>Main/kotlin`。
- `app/androidApp/`：Android 应用入口模块，包含 `MainActivity`、Manifest、Android 资源、签名与打包配置。
- `app/desktopApp/`：Desktop (JVM) 应用入口模块，包含桌面 `main()`、托盘集成与原生分发配置。
- `app/webApp/`：Web (JS/Wasm) 应用入口模块，包含浏览器 `main()` 与 Web 资源。
- `app/iosApp/iosApp`：iOS 入口工程（用 Xcode 管理）。
- `core/`：共享领域/数据逻辑。核心代码在 `core/src/commonMain/kotlin`，SQLDelight schema 在 `core/src/commonMain/sqldelight`，资源在 `core/src/commonMain/composeResources`。
- `proMain/`：独立的 Pro Kotlin Multiplatform 模块。共享源码位于 `proMain/kotlin`，并依赖 `:core`。
- 构建产物位于 `build/`、各模块 `build/` 和 `kotlin-js-store/`（自动生成）。

## 构建、测试与开发命令
使用 Gradle Wrapper（macOS/Linux 为 `./gradlew`，Windows 为 `gradlew.bat`）。Agent 执行 Gradle 命令时必须添加 `--no-daemon`，避免任务结束后保留 Gradle 守护进程。
```bash
./gradlew --no-daemon :app:androidApp:assembleDebug                # Android debug APK
./gradlew --no-daemon :app:desktopApp:run                          # Desktop (JVM) app
./gradlew --no-daemon :app:webApp:wasmJsBrowserDevelopmentRun      # Web (Wasm)
./gradlew --no-daemon :app:webApp:jsBrowserDevelopmentRun          # Web (JS)
./gradlew --no-daemon :core:jvmTest                                # Core JVM tests
./gradlew --no-daemon :app:shared:jvmTest                          # Shared UI JVM tests
```
- 每次 Gradle 任务完成、失败或被中断后，必须等待并确认本次启动的 `gradlew` 及其子进程已经退出；对于 `run`、浏览器开发服务器等常驻任务，结束验证后先发送 `SIGINT`，必要时再发送 `SIGTERM`，不得遗留应用、开发服务器或 Gradle 进程。
- 仅清理本次命令对应的进程树；禁止使用无范围限制的 `pkill`、`killall` 等命令，以免终止用户或其他 Agent 正在执行的构建。
iOS 通过 Xcode 打开 `app/iosApp/` 并运行。

## 编码风格与命名规范
- 遵循 Kotlin 标准风格：4 空格缩进，整洁的 import 顺序，表达式保持简洁。
- 包名使用 `com.folderspan.*` 命名空间；代码放在正确的 source set（`commonMain`、`androidMain`、`iosMain` 等）。
- 类与 `@Composable` 函数用 `PascalCase`，函数/变量用 `camelCase`，常量用 `UPPER_SNAKE`。
- 避免“冗余限定符名称”（如 `com.folderspan.xxx` 或不必要的全限定名）；优先使用合适的 import。

## 测试指南
- 公共测试位于 `app/shared/src/commonTest` 与 `core/src/commonTest`，使用 `kotlin.test`。
- 测试命名以 `*Test` 结尾；跨平台逻辑优先放在 `commonTest`。

## 提交与 Pull Request 规范
- 提交信息采用 Conventional Commits，例如 `feat(ui): add multi-platform font support`、`fix(build): align app modules with shared core`。
- PR 需包含清晰摘要、影响的平台、已运行的测试命令；UI/UX 变更请附截图或短视频。

## 配置提示
- `local.properties` 仅用于本地 Android SDK 路径，不应提交。
- 避免提交敏感信息，优先使用环境变量或 Gradle 属性。

## 文档同步
- 当仓库内新增或修改 `.md` 文件时，需同步更新 `md_descriptions_paths.md`（按该文件既有格式维护描述与路径）；同步范围排除根目录其他通用文档和 `server/`，`openspec/` 仅登记主规范、活跃变更与已归档变更。
