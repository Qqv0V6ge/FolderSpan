# FolderSpan CI/CD 自动打包与发布

本文说明 FolderSpan 的 GitHub Actions 预览与发布流水线、签名配置、构建产物和发布流程。工作流定义位于：

```text
.github/workflows/ci.yml
.github/workflows/shared-quality.yml
.github/workflows/build-preview.yml
.github/workflows/build-release.yml
```

各平台的本地 Release 打包命令参见[《各平台 Release 打包指南》](release-packaging.md)。

## 流水线概览

流水线使用 Gradle Wrapper 和 JDK 17，在不同 GitHub 托管 Runner 上完成测试、打包和发布。

| 触发方式 | 共享测试 | Android | Web | Desktop | GitHub Release |
| --- | --- | --- | --- | --- | --- |
| 推送 `main` / Pull Request 到 `main` | 执行 | 不执行 | 不执行 | 不执行 | 不创建 |
| 推送 `develop` | 执行 | Debug APK | Development ZIP | 全平台 Debug 预览包 | 不创建 |
| Pull Request 到 `develop` | 执行 | Debug APK | Development ZIP | 不执行 | 不创建 |
| 手动运行 `Build Preview` | 执行 | Debug APK | Development ZIP | 全平台 Debug 预览包 | 不创建 |
| 手动运行 `Build and Release` | 执行 | 签名 APK/AAB | 生产 ZIP | 全平台 Release 包 | 默认创建草稿，可取消 |
| 推送 `v*` 标签 | 执行 | 签名 APK/AAB | 生产 ZIP | 全平台 | 创建或更新草稿 |

共享测试包含：

```bash
./gradlew --no-daemon :core:jvmTest :app:shared:jvmTest :core:verifyNotificationRoutes
```

测试逻辑统一位于 `shared-quality.yml`，同时验证 Pro 与普通版；Pro 版本另外执行 `:proMain:jvmTest`。Linux Runner 安装 Xvfb 和 WebRTC 原生运行库，在虚拟显示环境中执行真实桌面剪贴板及 WebRTC 测试。运行库列表依据 [webrtc-java 官方说明](https://jrtc.dev/guide/get-started)，保留当前 0.16.0 需要的 PulseAudio、udev 和 D-Bus 依赖。

无声卡的 Runner 还会启动 PulseAudio，并通过 [`module-null-sink`](https://wiki.freedesktop.org/www/Software/PulseAudio/Documentation/User/Modules/) 提供虚拟输出和监听输入；仅安装 `libpulse0` 不足以初始化 WebRTC 音频模块。测试结束后停止本次启动的音频服务。

JVM 测试限制 Gradle 为 2 个 worker，Gradle 堆为 3 GiB，Kotlin 编译器堆为 2 GiB。失败时仍上传 `test-reports-pro` / `test-reports-no-pro`，包含 HTML 和 JUnit XML；通知路由目录在验证成功后单独上传。所有流水线 Gradle 命令使用 `--no-daemon`。

共享测试和正式打包显式设置 `folderspanBuildType=release`，使用正式网关且不启用本地开发代理。Web 的 `composeCompatibilityBrowserDistribution` 任务名不含 `release` / `production`，不能依赖任务名自动推断构建类型。

正式 Web 打包通过 `NODE_OPTIONS=--max-old-space-size=8192` 为生产压缩提供 8 GiB Node.js 堆，避免默认堆限制导致 `ERR_WORKER_OUT_OF_MEMORY`。该任务使用 `--max-workers=1`，防止 JS 和 Wasm 构建同时占用高峰内存。

`Build Preview` 不读取发布签名，只生成使用调试证书签名的 Debug APK。全平台 Desktop 预览包只在直接推送 `develop` 或手动运行预览工作流时构建，Pull Request 不执行耗时较长的 Desktop 矩阵。

## 自动发布产物

推送符合 `vMAJOR.MINOR.PATCH` 格式的标签后，流水线会生成以下产物：

| 平台 | 架构 | 产物 |
| --- | --- | --- |
| Android | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`、通用包 | APK、AAB |
| Web | JS + Wasm 兼容分发 | ZIP |
| Linux | x64 | DEB、RPM、TAR.GZ、Flatpak |
| Linux | ARM64 | DEB、RPM、TAR.GZ、Flatpak |
| Windows | x64 | MSI、EXE |
| Windows | ARM64 | 便携 ZIP |
| macOS | Intel x64 | DMG、PKG |
| macOS | Apple Silicon arm64 | DMG、PKG |

发布任务会下载各构建任务的产物，为重名文件添加平台和架构前缀，并生成：

```text
SHA256SUMS.txt
```

最终文件会作为附件上传到对应版本的 GitHub Release 草稿。Release 说明由 GitHub 根据提交记录自动生成，维护者检查产物和说明后再手动发布。流水线拒绝覆盖已发布版本，也不会移动指向其他提交的已有标签。已有草稿必须绑定同一构建提交，才允许重跑时覆盖附件；不同提交不能混用同一个版本草稿。

## Android 发布签名

### 必需 Secrets

在 GitHub 仓库中打开：

```text
Settings
  → Secrets and variables
  → Actions
  → New repository secret
```

创建以下四个 Repository Secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Release Keystore 的 Base64 内容 |
| `ANDROID_STORE_PASSWORD` | Keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 Key Alias |
| `ANDROID_KEY_PASSWORD` | 签名 Key 密码 |

四项必须同时存在。流水线不会将证书、密码或解码后的 Keystore 保存为构建产物。

### Actions 不提交 Keystore 如何完成签名

Release Keystore 不需要、也不允许提交到 Git。GitHub Actions 的处理过程如下：

1. `build-release.yml` 从 GitHub Repository Secrets 读取 Keystore 的 Base64 内容和三个签名参数，各 Secret 只注入确实需要它的步骤。
2. Runner 设置仅当前用户可访问的文件权限，并使用标准输入将 Base64 内容解码到 `$RUNNER_TEMP/folderspan-release.keystore`。
3. 工作流只把临时文件路径写入 `GITHUB_ENV`，并通过 `ANDROID_STORE_FILE` 传给 Gradle。
4. `app/androidApp/build.gradle.kts` 从环境变量创建 `release` 签名配置，然后执行 `bundleRelease` 和 `assembleRelease`。
5. Android Job 无论成功或失败都会显式删除临时 Keystore；它不会上传到 Artifacts，也不会写回仓库。

工作流不会输出 Secret 内容。GitHub 官方也支持通过 Secrets 保存 Base64 编码的二进制内容，参见 [在 GitHub Actions 中使用机密](https://docs.github.com/zh/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)。

### 生成 Keystore Base64

Linux：

```bash
base64 -w 0 /absolute/path/to/release.keystore
```

macOS：

```bash
base64 < /absolute/path/to/release.keystore | tr -d '\n'
```

PowerShell：

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("C:\absolute\path\to\release.keystore")
)
```

将命令输出的完整单行内容保存为 `ANDROID_KEYSTORE_BASE64`。

### 本地签名与 CI 签名优先级

本地仍可在仓库根目录使用被 Git 忽略的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

也可以提供以下环境变量：

```text
ANDROID_STORE_FILE
ANDROID_STORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

环境变量优先于 `keystore.properties`，以确保 CI 显式注入的配置不会被本地残留配置覆盖。

## 版本号规则

Android 和 Desktop 从 Gradle 属性 `releaseVersion` 读取版本号，默认值为 `1.0.0`。正式发布流水线在标签构建时把标签名注入该属性，并自动去除开头的 `v`。

例如：

```text
标签：v1.4.2
应用版本：1.4.2
```

当前仅接受严格的三段数字版本：

```text
MAJOR.MINOR.PATCH
```

Android `versionCode` 使用 GitHub Actions 的运行序号，保证后续流水线构建递增。iOS 版本仍在 `app/iosApp/Configuration/Config.xcconfig` 中独立维护。

`develop` 预览版使用以下三段数字版本，避免与正式版本混淆，同时满足各平台安装包对纯数字版本号的要求：

```text
0.0.<GitHub Actions 运行序号>
```

## 运行方式

### 构建 develop 预览版

推送 `develop` 会触发 `Build Preview`，生成 Android、Web 和六组 Desktop Debug 预览产物：

```bash
git push origin develop
```

构建完成后，可在 GitHub Actions 对应运行记录的 `Artifacts` 区域下载名称以 `preview-` 开头的产物。预览产物保留 14 天。

Pull Request 到 `develop` 只执行共享测试、Android Debug 和 Web Development 构建，不执行 Desktop 矩阵。

### 手动运行预览打包

在 GitHub 仓库中打开：

```text
Actions
  → Build Preview
  → Run workflow
```

手动预览打包与直接推送 `develop` 的产物一致，不需要配置 Android Release 签名 Secrets。

### 手动运行全平台 Release 打包

在 GitHub 仓库中打开：

```text
Actions
  → Build and Release
  → Run workflow
```

手动运行会构建 Android Release、Web 和全部 Desktop Release 产物，包括 Linux x86_64/ARM64、Windows x64/ARM64、macOS Intel/Apple Silicon，默认在全部构建通过后创建 GitHub Release 草稿。运行时填写 `version`（默认 `1.0.0`）；取消 `create_draft` 可只保存 Actions 产物。四个 Android 签名 Secrets 仍然是必需的。草稿明确绑定本次构建的提交。

### 准备并发布正式版本

推荐先手动运行 `Build and Release`，填写 `version=1.0.0` 并保持 `create_draft=true`。所有平台和两个版本均通过后，在 Releases 中检查 `FolderSpan 1.0.0` 草稿及 `SHA256SUMS.txt`，再点击发布。

也可以创建并推送三段数字版本标签；这种方式同样只生成草稿：

```bash
git tag v1.0.0
git push origin v1.0.0
```

不要把移动过的同名标签用于覆盖已有正式发布。需要修正发布内容时，应创建新的补丁版本标签。

## 平台限制与签名状态

- Desktop 原生安装包必须在目标操作系统上生成，因此流水线使用 Linux、Windows、macOS Intel 和 macOS arm64 Runner 分别打包。
- Flatpak 分别使用 `ubuntu-24.04` 和 `ubuntu-24.04-arm` 构建 x86_64、aarch64 bundle，两个架构不能互相转换。
- Flatpak 使用 Freedesktop Platform/SDK 25.08，运行时由 Flathub 下载；应用沙箱权限由 `packaging/flatpak/` 下的 manifest 统一维护。
- Windows ARM64 使用公开预览的 `windows-11-arm` Runner 和 Microsoft OpenJDK 17，当前生成包含 ARM64 Runtime 的便携 ZIP，不生成依赖 WiX 的 MSI/EXE。
- 当前 `webrtc-java 0.16.0` 的项目依赖配置未提供 Windows ARM64 原生库，因此该便携包暂不支持本机 WebRTC；其他功能仍需在 Windows ARM64 设备上冒烟验证。
- 当前 Windows x64 安装包未配置 Authenticode 签名。
- 当前 macOS 安装包未启用 Developer ID 签名和 Apple 公证，用户安装时可能看到系统安全提示。
- 当前流水线不生成 iOS IPA。iOS 自动发布需要额外配置 Apple 证书、Provisioning Profile、Team ID 和导出策略。
- Web 产物是可部署的静态站点 ZIP，当前流水线不会自动部署到 GitHub Pages 或其他服务器。

## 权限与安全

流水线默认只授予 `contents: read` 权限。仅创建 GitHub Release 的任务临时使用 `contents: write`。

签名相关注意事项：

- 不要提交 Release Keystore、证书密码或 `keystore.properties`。
- `.gitignore` 会忽略 `*.jks`、`*.keystore`、`*.p12` 和 `*.pfx`；`app/androidApp/signing/debug.keystore` 是仅供预览构建使用的非生产调试证书例外。
- 不要在工作流日志中输出 Secret 内容。
- Release Keystore 仅在 Android 发布任务的临时目录中解码。
- `build-preview.yml` 不引用任何发布 Secrets；Pull Request 不执行发布签名和 Desktop 打包。

## 常见问题

### Missing Android signing secrets

错误示例：

```text
Missing Android signing secrets
```

检查四个 Android Repository Secrets 是否全部存在，名称是否完全一致。

### releaseVersion 格式错误

错误示例：

```text
releaseVersion must use MAJOR.MINOR.PATCH format
```

标签必须使用类似 `v1.2.3` 的格式，不支持 `v1.2` 或包含预发布后缀的版本。

### AAB 与多 ABI APK 冲突

AGP 不允许在启用多 APK 拆分时直接构建 AAB。工作流已经自动分成两次构建：

1. 关闭 ABI APK 拆分并生成 AAB。
2. 清理 Android 应用构建目录，恢复 ABI 拆分并生成 APK。

手动构建 AAB 时应执行：

```bash
./gradlew :app:androidApp:bundleRelease \
  -PandroidEnableAbiSplits=false
```

### Linux 共享测试失败

- `HeadlessException`：确认 JVM 测试通过 `xvfb-run` 执行，未丢失 `DISPLAY`。
- `UnsatisfiedLinkError` / `NativeLibraries` 初始化失败：从 `test-reports-*` 的 JUnit XML 查看缺失的具体动态库，检查 `Install desktop test runtime` 是否成功。
- 局域网服务测试失败：查看同一报告中的断言详情、监听地址和异常；不要禁用测试来使流水线通过。

### Desktop 打包失败

- Linux DEB/RPM 需要 `fakeroot` 和 `rpm` 等系统工具。
- Linux Flatpak 需要 `flatpak`、`flatpak-builder`、`desktop-file-validate`、`appstreamcli` 以及 Freedesktop Platform/SDK 25.08。
- Flatpak 元数据校验失败时，先本地执行 `desktop-file-validate packaging/flatpak/com.folderspan.FolderSpan.desktop` 和 `appstreamcli validate --no-net packaging/flatpak/com.folderspan.FolderSpan.metainfo.xml`。
- Desktop 打包必须使用包含 `jmods/` 的完整 JDK。
- Windows MSI/EXE 需要 WiX Toolset。
- Windows ARM64 预览包使用 `packageAppImage`，正式包使用 `packageReleaseAppImage`，随后执行 `Compress-Archive`；两者都不依赖 WiX。
- macOS DMG/PKG 必须在 macOS 上构建。

### Release 没有创建

确认以下条件：

- 触发来源是推送到远端的 `v*` 标签，或手动运行且启用了 `create_draft`。
- Android、Web 和六个 Desktop 矩阵任务全部成功。
- 仓库允许 GitHub Actions 使用 `contents: write` 创建 Release。
