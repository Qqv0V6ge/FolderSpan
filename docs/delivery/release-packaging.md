# FolderSpan 各平台 Release 打包指南

本文记录 FolderSpan 各平台的 Release 打包方式、必要准备和产物位置。除 iOS 命令特别说明外，命令均在仓库根目录执行。

## 平台与产物速查

| 平台 | Release 命令 | 产物位置 |
| --- | --- | --- |
| Android APK | `./gradlew :app:androidApp:assembleRelease` | `app/androidApp/build/outputs/apk/release/*.apk` |
| Android AAB | `./gradlew :app:androidApp:bundleRelease -PandroidEnableAbiSplits=false` | `app/androidApp/build/outputs/bundle/release/*.aab` |
| Linux DEB/RPM/TAR.GZ（x86_64、ARM64） | 见“Desktop”章节 | `app/desktopApp/build/compose/binaries/main-release/` |
| Linux Flatpak（x86_64、ARM64） | 见“Desktop”章节 | `app/desktopApp/build/compose/binaries/main-release/flatpak/*.flatpak` |
| Windows x64 | `./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS` | `app/desktopApp/build/compose/binaries/main-release/{msi,exe}/` |
| Windows ARM64 | 见“Desktop”章节 | `app/desktopApp/build/compose/binaries/main-release/zip/*.zip` |
| macOS | `./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS` | `app/desktopApp/build/compose/binaries/main-release/{dmg,pkg}/` |
| iOS Archive | 见“iOS”章节 | `app/iosApp/build/release/FolderSpan.xcarchive` |
| iOS IPA | 见“iOS”章节 | `app/iosApp/build/release/export/*.ipa` |
| Web JS | `./gradlew :app:webApp:jsBrowserDistribution` | `app/webApp/build/dist/js/productionExecutable/` |
| Web Wasm | `./gradlew :app:webApp:wasmJsBrowserDistribution` | `app/webApp/build/dist/wasmJs/productionExecutable/` |

`server/` 当前只是模块骨架，没有可发布的服务端产物，因此不在本文的打包范围内。

## 发布前准备

### 版本号

Android 和 Desktop 可通过 Gradle 属性 `releaseVersion` 统一设置版本号；未提供时默认使用 `1.0.0`。CI 会从 `vMAJOR.MINOR.PATCH` 标签自动注入该属性。iOS 仍单独维护版本号：

- Android：`releaseVersion` 设置 `versionName`，`androidVersionCode` 设置 `versionCode`。
- Desktop：`releaseVersion` 设置 `desktopPackageVersion`。
- iOS：`app/iosApp/Configuration/Config.xcconfig` 中的 `CURRENT_PROJECT_VERSION` 和 `MARKETING_VERSION`。

本地指定统一版本的示例：

```bash
./gradlew <task> -PreleaseVersion=1.2.3
```

### 基础检查

建议在打包前执行：

```bash
./gradlew :core:jvmTest :app:shared:jvmTest
```

Desktop 原生安装包必须在目标操作系统上生成，不能在 Linux 上直接生成 Windows 或 macOS 安装包。iOS 必须在安装了 Xcode 的 macOS 上打包。

## Android

### 签名配置

Release 签名可以通过根目录的 `keystore.properties` 提供：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

也可以使用以下环境变量：

```text
ANDROID_STORE_FILE
ANDROID_STORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

四项必须同时有效。环境变量优先于 `keystore.properties`；未提供环境变量时，若本地配置中的 `storeFile` 指向不存在的文件，构建会在 `validateSigningRelease` 阶段失败。不要提交密钥文件、密码或包含密码的本地配置。

### APK

```bash
./gradlew :app:androidApp:assembleRelease
```

产物目录：

```text
app/androidApp/build/outputs/apk/release/
```

项目启用了 ABI 拆分，该目录会包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 和 universal APK。实际文件名以目录中的 `*.apk` 为准。

### AAB

用于 Google Play 等应用商店发布：

```bash
./gradlew :app:androidApp:bundleRelease \
  -PandroidEnableAbiSplits=false
```

产物目录：

```text
app/androidApp/build/outputs/bundle/release/
```

项目默认启用多 ABI APK 拆分。AGP 不允许在该配置下直接生成 AAB，因此构建 AAB 时必须通过 `androidEnableAbiSplits=false` 关闭 APK 拆分。CI 会自动将 AAB 与多 ABI APK 分成两个干净构建。

同时保留混淆映射目录，便于还原线上崩溃堆栈：

```text
app/androidApp/build/outputs/mapping/release/
```

## Desktop

Desktop 打包需要完整 JDK，且 JDK 目录中必须包含 `jmods/`。可通过 `compose.desktop.java.home` Gradle 属性或 `JAVA_HOME` 指定。

所有 Release 产物都位于：

```text
app/desktopApp/build/compose/binaries/main-release/
```

### Linux

分别生成各格式：

```bash
./gradlew :app:desktopApp:packageReleaseAppImage
./gradlew :app:desktopApp:packageReleaseDeb
./gradlew :app:desktopApp:packageReleaseRpm
./gradlew :app:desktopApp:packageReleaseTarGz
```

产物位置：

| 任务 | 产物位置 | 说明 |
| --- | --- | --- |
| `packageReleaseAppImage` | `main-release/app/FolderSpan/` | 包含应用和 Java Runtime 的可运行目录，不是单个 `.AppImage` 文件 |
| `packageReleaseDeb` | `main-release/deb/` | Debian/Ubuntu 安装包，需要系统提供 DEB 打包工具 |
| `packageReleaseRpm` | `main-release/rpm/` | Fedora/RHEL 系安装包，需要系统提供 RPM 打包工具 |
| `packageReleaseTarGz` | `main-release/targz/com.folderspan-<version>.tar.gz` | 对可运行目录进行归档，适合通用分发 |
| Flatpak CLI | `main-release/flatpak/FolderSpan-<version>-<arch>.flatpak` | 封装 release app image；CI 分别生成 x86_64 与 aarch64 bundle |

也可执行当前系统已配置格式的聚合任务：

```bash
./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS
```

该聚合任务不包含仓库自定义的 `packageReleaseTarGz`，需要时应单独执行。Flatpak 也不是 Compose Desktop 的原生 `TargetFormat`，由 GitHub Actions 在 release app image 生成后自动完成二次封装。

### Windows

在 Windows 上执行：

```powershell
gradlew.bat :app:desktopApp:packageReleaseDistributionForCurrentOS
```

也可以分别执行：

```powershell
gradlew.bat :app:desktopApp:packageReleaseMsi
gradlew.bat :app:desktopApp:packageReleaseExe
```

产物目录：

```text
app/desktopApp/build/compose/binaries/main-release/msi/
app/desktopApp/build/compose/binaries/main-release/exe/
```

CI 的 Windows ARM64 任务运行在 `windows-11-arm`，使用 Microsoft OpenJDK 17 执行 `packageReleaseAppImage`，随后将可运行目录压缩为：

```text
app/desktopApp/build/compose/binaries/main-release/zip/FolderSpan-<version>-windows-arm64.zip
```

该 ZIP 不依赖 WiX，解压后直接运行 `FolderSpan.exe`。`webrtc-java 0.14.0` 暂无 Windows ARM64 原生库，因此 ARM64 便携包暂不支持本机 WebRTC；Windows x64 MSI/EXE 不受此限制。

### macOS

在 macOS 上执行：

```bash
./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS
```

也可以分别执行：

```bash
./gradlew :app:desktopApp:packageReleaseDmg
./gradlew :app:desktopApp:packageReleasePkg
```

产物目录：

```text
app/desktopApp/build/compose/binaries/main-release/dmg/
app/desktopApp/build/compose/binaries/main-release/pkg/
```

需要签名和公证时，在安全的本地 Gradle 配置或 CI Secret 中提供以下属性：

```text
compose.desktop.mac.sign=true
compose.desktop.mac.signing.identity
compose.desktop.mac.signing.keychain
compose.desktop.mac.signing.prefix
compose.desktop.mac.notarization.appleID
compose.desktop.mac.notarization.password
compose.desktop.mac.notarization.teamID
```

不要把 Apple ID 密码或公证专用密码写入仓库。

## iOS

iOS 依赖 CocoaPods、WebRTC-SDK 和项目所需的原生网络 XCFramework。准备方式参见[《iOS 原生网络依赖》](../networking/ios-native-libs.md)。

### 准备

1. 在 `app/iosApp/Configuration/Config.xcconfig` 中设置 `TEAM_ID`、版本号和 Bundle ID。
2. 安装 Pod 依赖：

   ```bash
   cd app/iosApp
   pod install
   cd ../..
   ```

3. 始终使用 `app/iosApp/iosApp.xcworkspace`，不要使用单独的 `.xcodeproj`。

### 生成 Archive

下面的命令显式固定 Archive 位置，便于 CI 和发布人员查找：

```bash
mkdir -p app/iosApp/build/release
xcodebuild \
  -workspace app/iosApp/iosApp.xcworkspace \
  -scheme iosApp \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$PWD/app/iosApp/build/release/FolderSpan.xcarchive" \
  -allowProvisioningUpdates \
  archive
```

产物：

```text
app/iosApp/build/release/FolderSpan.xcarchive
```

### 导出 IPA

准备与发布方式匹配的 `ExportOptions.plist`，例如 App Store Connect、Ad Hoc 或 Development，再执行：

```bash
xcodebuild \
  -exportArchive \
  -archivePath "$PWD/app/iosApp/build/release/FolderSpan.xcarchive" \
  -exportPath "$PWD/app/iosApp/build/release/export" \
  -exportOptionsPlist /absolute/path/to/ExportOptions.plist \
  -allowProvisioningUpdates
```

产物：

```text
app/iosApp/build/release/export/*.ipa
```

也可以在 Xcode 中打开 workspace，选择 `Any iOS Device (arm64)` 后执行 `Product > Archive`，再通过 Organizer 分发；GUI 导出位置由操作人员选择。

## Web

### JS

```bash
./gradlew :app:webApp:jsBrowserDistribution
```

可部署目录：

```text
app/webApp/build/dist/js/productionExecutable/
```

### Wasm

```bash
./gradlew :app:webApp:wasmJsBrowserDistribution
```

可部署目录：

```text
app/webApp/build/dist/wasmJs/productionExecutable/
```

发布时必须部署整个 `productionExecutable/` 目录，不能只复制 `index.html` 或 `webApp.js`。Web 服务器需要为 `.wasm` 返回正确的 `application/wasm` MIME 类型。

如果发布平台要求单个归档文件，可在完成构建后执行：

```bash
mkdir -p app/webApp/build/releases
tar -czf app/webApp/build/releases/folderspan-web-js.tar.gz \
  -C app/webApp/build/dist/js/productionExecutable .
tar -czf app/webApp/build/releases/folderspan-web-wasm.tar.gz \
  -C app/webApp/build/dist/wasmJs/productionExecutable .
```

归档产物：

```text
app/webApp/build/releases/folderspan-web-js.tar.gz
app/webApp/build/releases/folderspan-web-wasm.tar.gz
```

## 发布检查清单

- 各平台版本号一致，Android `versionCode` 和 iOS `CURRENT_PROJECT_VERSION` 已递增。
- Android APK/AAB、Windows、macOS 和 iOS 产物已使用正式证书签名。
- Android 混淆映射与 iOS Archive/dSYM 已与发布产物一起保存。
- Web 发布目录中的 JS、Wasm、资源和静态文件完整。
- 在目标系统或真机上完成安装、启动、文件访问、网络连接和分享功能冒烟测试。
- 对最终分发文件生成并保存 SHA-256 校验值。
