这是一个面向 Android、iOS、Web、Desktop (JVM) 的 Kotlin 多平台项目。

## 项目结构

* [/app/shared](./app/shared/src) 存放 Compose Multiplatform 共享 UI、UI 入口 API，以及必须与 `expect` 声明同模块编译的平台 `actual` 实现。
* [/app/androidApp](./app/androidApp/src) 存放 Android 应用入口、Manifest、应用资源、签名与打包配置。
* [/app/desktopApp](./app/desktopApp/src) 存放 Desktop (JVM) 应用入口、托盘集成和原生分发配置。
* [/app/webApp](./app/webApp/src) 存放 Web (JS/Wasm) 应用入口和浏览器资源。
* [/app/iosApp](./app/iosApp/iosApp) 包含 iOS 入口工程（用 Xcode 管理），并依赖 `app:shared` 产出的 `ComposeApp` framework。
* [/core](./core/src) 存放跨端领域/数据逻辑、SQLDelight schema、共享资源与平台实现。
* [/server](./server) 保留服务端模块目录，用于存放相关文档与配置；当前源码已移除。

## 构建和运行 Android 应用

```shell
./gradlew :app:androidApp:assembleDebug
```

安装并启动到指定 Android 设备：

```shell
DEVICE=emulator-5554
ANDROID_SERIAL=$DEVICE ./gradlew :app:androidApp:installDebug && adb -s $DEVICE shell monkey -p com.folderspan 1
```

## 构建和运行桌面应用

```shell
./gradlew :app:desktopApp:run
```

使用 Compose Hot Reload 运行桌面应用：

```shell
./gradlew :app:desktopApp:hotRunJvm
```

## 构建和运行 Web 应用

Wasm 目标：

```shell
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```

JS 目标：

```shell
./gradlew :app:webApp:jsBrowserDevelopmentRun
```

## 构建和运行 iOS 应用

使用 Xcode 打开 [/app/iosApp](./app/iosApp) 目录并运行。Xcode 构建脚本会调用：

```shell
./gradlew :app:shared:embedAndSignAppleFrameworkForXcode
```

## 测试

```shell
./gradlew :core:jvmTest
./gradlew :app:shared:jvmTest
```

了解更多关于 [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)、
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform)、
[Kotlin/Wasm](https://kotl.in/wasm/) 的信息。
