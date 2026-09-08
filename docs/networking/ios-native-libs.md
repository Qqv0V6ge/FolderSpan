# iOS 原生网络依赖（FTP/SFTP/SMB）

本项目 iOS 端网络协议依赖如下：

- `libcurl`（FTP，CInterop）
- `libssh2`（SFTP，CInterop）
- `libsmb2`（SMB，CInterop）
- `libssl`、`libcrypto`（SFTP/SMB 依赖）

默认查找目录：`~/ios-native-xcframeworks`（仅用于 `libssh2/libsmb2/libssl/libcrypto`）。

## 1. 快速校验本地原生库（SFTP/SMB）

```bash
./scripts/verify-ios-native-libs.sh
```

如果输出包含 `stub 占位库`，说明当前只能通过编译，运行时 SFTP/SMB 会失败。

## 2. 生成 iOS xcframework（SFTP/SMB）

```bash
./scripts/build-ios-native-xcframeworks.sh
```

可选参数：

```bash
OUT_DIR=$HOME/ios-native-xcframeworks \
MIN_IOS_VERSION=14.0 \
DOWNLOAD_CONNECT_TIMEOUT=10 \
DOWNLOAD_MAX_TIME=180 \
./scripts/build-ios-native-xcframeworks.sh
```

脚本会下载并编译：OpenSSL / libssh2 / libsmb2，并输出到：

- `~/ios-native-xcframeworks/libssh2.xcframework`
- `~/ios-native-xcframeworks/libsmb2.xcframework`
- `~/ios-native-xcframeworks/libssl.xcframework`
- `~/ios-native-xcframeworks/libcrypto.xcframework`

## 3. FTP CInterop 依赖

FTP 在 iOS 端通过 `libcurl` 的 CInterop 接入：

- `core/src/iosMain/cinterop/libcurl.def`
- `core/src/iosMain/kotlin/com/folderspan/data/main/network/FtpNetworkClient.kt`

支持能力：FTP/FTPS、主动/被动模式切换、标准错误信息回传。

## 4. 构建验证

```bash
./gradlew :core:compileKotlinIosSimulatorArm64
cd app/iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
```

## 5. 常见问题

- `SSL connection timeout`：当前终端环境无法访问外网，先在可联网环境运行脚本，再拷贝 `~/ios-native-xcframeworks` 到本机。
- 仍提示 SFTP/SMB 连接失败：先运行 `./scripts/verify-ios-native-libs.sh`，确认不是 stub 库。
- FTP 依赖安装失败：确认本机已安装 CocoaPods，并可访问 CocoaPods CDN。
