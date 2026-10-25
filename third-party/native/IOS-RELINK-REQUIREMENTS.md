# iOS 原生库发行前材料

本目录保存的是当前脚本默认 OpenSSL 3.3.2、libssh2 1.11.1、curl 8.12.1、libsmb2 6.2 的精确上游文本，和 Podfile.lock 125.6422.05 对应官方 WebRTC XCFramework 内实际 LICENSE。没有最终 IPA，默认值能被环境变量及外部 XCFramework 路径覆盖，因此不能把这里的版本表当作已验证的 iOS 成品清单。

| 组件 | 需随相应发行交付的原文 | 仍需落实的材料 |
| --- | --- | --- |
| libsmb2 客户端 6.2 | `licenses/libsmb2/COPYING`、`LICENCE-LGPL-2.1.txt` | LGPL-2.1-or-later。脚本默认静态库；按实际选择的 LGPL 第 6 条路径，提供库的完整对应源码及修改、能使用户修改库后重新链接应用的应用对象码或可用源码、构建与链接脚本、必要数据/工具说明，并保留告知。条款须允许有关修改及调试修改所需的逆向工程。 |
| OpenSSL 3.3.2 | `licenses/openssl/LICENSE.txt` | Apache-2.0。使用此版本实际版权/适用声明、修改告知；不得套用 OpenSSL 1.x 的旧双许可证。最终实际启用模块及外部后端需核对。未把只用于构建的 Perl Text-Template 当作已随应用链接的库。 |
| libssh2 1.11.1 | `licenses/libssh2/COPYING` | BSD-3-Clause；保留版权、条款、免责声明，OpenSSL 后端另计。 |
| curl 8.12.1 | `licenses/curl/COPYING` | 使用其 `curl` 许可原文，保留版权、条件和免责声明、不以作者背书；启用的 TLS/SSH/压缩等后端各自许可另计。 |
| WebRTC-SDK 125.6422.05 | `licenses/webrtc-ios/LICENSE` | 已从官方 XCFramework ZIP 实际提取，但该文件只有 WebRTC 主 BSD 文本，没有完整 native 第三方集合。不得据此声称 iOS WebRTC 许可已全部补齐；需拿到该 SDK 实际构建提交、GN 参数和依赖许可集合。Android 125.6422.06.1 的集合不能冒充这一份。 |

libsmb2 发行材料应至少建立：实际 `.a`/XCFramework 哈希、库源码固定提交及补丁、工具链与 SDK 版本、实际编译/链接选项、可复现的重链接步骤，以及用户在可支持设备上安装其重链接版本的说明。自有应用代码采用 Apache-2.0 并公开源码，有助于准备这些材料，但不自动证明 LGPL 静态链接条件已经满足。

确认实际签名、重装限制及目标商店条款是否能同时履行 LGPL 条件后，才能声称该 iOS 分发方案已完成核验。此项是 iOS 未来发行工作，不是 Android APK 或 macOS 采用 JNA Apache 路线的同一问题。
