# 安装版本与渠道切换

Preview 和 Release 均使用生产构建和原有应用标识、签名、数据目录。内部安装版本由 `resolve-install-versions.py` 统一生成，不使用各工作流独立的 `github.run_number`。

- `version` 是对外展示的 `MAJOR.MINOR.PATCH`。Preview 留空时沿用对应产品最近的发布标签版本；Release 必须明确指定版本，创建新草稿须选用尚未被其他提交占用的版本。
- 源码修订号 `R` 是该发布仓库完整 Git 历史的 `git rev-list --count HEAD`，准备作业必须使用 `fetch-depth: 0`。不要重写发布仓库历史。
- 内部构建号为 `2 * R + C`，Preview 的 `C=0`、Release 的 `C=1`。同一修订的正式包比预览包新；下一修订的预览包又比上一修订的正式包新。重跑工作流不会改变编号。
- Android 的 `versionCode` 编码版本的 major、minor 与内部构建号；Windows MSI/EXE 单独使用 `major.minor.内部构建号`，不会修改界面展示版本。Apple 使用同一修订/渠道规则，另为 Pro、普通版分配不同构建号；从 1000 段开始，避开旧工作流使用的 40、25 等序号。
- 开源 Release 的 Winget 清单从 MSI 的实际 `ProductVersion` 生成 `PackageVersion` 与 `DisplayVersion`，保证包管理器也按安装版本比较；下载地址仍使用对外发布标签。
- 当前完整历史修订号上限为 32767，Windows 内部第三段不得超过 65535，Android 不得超过 2100000000。超过范围、浅克隆、非法版本或低于发布基线时直接失败，禁止截断、取模或静默降级。

用户安装同一发布仓库、同一应用与签名、相同代码或更新代码的正式包时，无需为了渠道切换而卸载或清理数据。已经下载的旧包无法修改内部版本，须重新构建正式包；此前使用 Debug 密钥的旧预览包还受 Android 签名兼容限制，不能仅靠改版本号覆盖。真正回退到更旧代码、跨不同源码仓库或重写过的 Git 历史不在这个升级顺序保证内；不要通过强制降级绕过系统保护。

已发布兼容依据：公开仓库的 `v1.0.0`、`v1.0.1`、`v1.1.0` 和 `v1.1.1`；此前安装版本来自语义版本和独立工作流序号。此次仅调整打包元数据，不修改应用 ID、签名密钥、数据库或数据目录，不需要数据迁移。

| 对象 | 发布证据 | 兼容处理 | 数据迁移 | 版本提升 |
| --- | --- | --- | --- | --- |
| Android 安装版本 | `v1.0.0` 至 `v1.1.1` 的 `app/androidApp/build.gradle.kts`，保留 `com.folderspan` | 保留应用 ID 和正式签名，替换旧工作流计数 | 无数据格式变化 | 内部 `versionCode` 递增；不代选新展示版本 |
| Windows 安装版本与清单 | `v1.1.1` 的 Desktop Gradle 配置和渠道配方脚本 | 保留应用身份，MSI/EXE 与 Winget 按实际安装版本比较 | 无数据格式变化 | 独立内部安装版本；展示版本由发布者指定 |

Apple 商店中已经上传的完整历史未在本机核实；当前验证覆盖工作流来源的构建编号与格式，商店上传和真机安装仍需平台验收。

# Install versions and channel switching

Preview and Release share production builds, app identity, signing keys and data paths. The resolver derives install versions from complete source history, never from per-workflow counters. For source revision `R`, Preview uses `2R` and Release uses `2R+1`; a subsequent source revision is newer than either build of its predecessor. Re-running the same source revision and channel keeps the same install version.

The public semantic version remains separate from Android's `versionCode`, Windows MSI/EXE versions and Apple build numbers. Winget manifests use the MSI's actual `ProductVersion` for package and installed-version comparisons, while download URLs retain the public release tag. An empty Preview version uses the latest release-tag version for the client; Release requires an explicit version, and a new draft needs an unused version. Source revisions, platform ranges and published baselines are validated before packaging.

The guarantee applies to the same publishing repository, app identity and signing key with unchanged or newer source. Existing old artifacts must be rebuilt; changing a version number does not make old debug-signed APKs compatible with the production signing key. It does not authorize downgrading older source, changing repositories or rewriting publishing history. No app data migration or uninstall is introduced. Actual device installation remains a separate platform acceptance check.
