# 免费渠道的实际配方与验证

更新：2026-10-27。这里只维护可运行的配方；旧 `docs/distribution-kit/` 中的 `.in` 是历史工作稿，不能覆盖本页的新结果。

本轮使用 GitHub Actions 远程执行，不在发布者的 Mac 上构建应用。应用来自已公开的普通版 v1.0.1，另制作 Linux 渠道包。代码在 `codex/channel-validation` 分支，尚未合并 master，也未提交下表平台的官方目录。

| 渠道 | 正式维护输入 | 已完成的技术验证 | 尚待处理 |
| --- | --- | --- | --- |
| WinGet | `winget/` 三份 YAML；x64 MSI | `winget validate` 无警告；旧版安装、新版覆盖升级、窗口响应、卸载与注册项检查 | 官方仓库查重与提交、平台审核；不把本地 MSI 测试称为已由 WinGet 官方源安装 |
| Scoop | `scoop/bucket/folderspan.json`；仅 ARM64 | 真实 ARM64 runner；临时 bucket 安装 1.0.0、更新至 1.0.1、窗口响应、卸载 | 维护者提交 Extras 或维护独立 bucket；本仓库根目录不是 Scoop bucket，不虚构 x64 portable 支持 |
| Chocolatey | `chocolatey/` | 实际 `choco pack`，安装旧版测试包、升级到 1.0.1、响应窗口与卸载；底层 MSI 升级也通过 | 真实维护者账号与社区审核；旧版测试包仅为未公开的测试输入，不冒充已在社区发行 |
| AUR | `aur/PKGBUILD`、`linux/folderspan.desktop` | Arch 容器实际 makepkg、安装、窗口启动、卸载；字体依赖已修正 | 原包和补充声明摘要均验证，复验通过；只剩维护者账号、提交与社区审查 |
| Nixpkgs | `nix/package.nix`、共用 desktop 文件 | 实际 Nix derivation 构建、ELF 修补、字体配置、窗口启动 | 独立配方及补充声明复验通过；使用 makeDesktopItem，无仓库外相对文件依赖，接下来由社区审查 PR |
| Snap Store | `snap/snapcraft.yaml` | 已产出 strict `.snap`，实际安装、窗口启动、移除 | 补充声明复验通过；[Snap 候选下载](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/folderspan-1.0.1-amd64-candidate.snap)已发布，尚未上传 Store；账号、名称注册和审核；隐藏目录、外部盘等受 strict 权限限制，窗口测试不等于完整文件功能验收 |
| AppImageHub | `appimage/AppRun`、`scripts/package_distribution_linux.sh`、`appimage/FolderSpan` | 已产出真实 Type 2 `.AppImage`，验证 ELF/AI 标识、真实窗口和 Gradle TAR 执行位修复 | 补充声明及 Ubuntu 22.04 断网启动均通过；[永久下载](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/FolderSpan-1.0.1-x86_64.AppImage)已发布，尚未提交目录 PR |
| F-Droid | `fdroid/metadata/com.folderspan.yml` | 原 v1.0.1 及含新版声明的固定提交均已通过官方 lint、源码扫描、未签名 APK 完整构建和二进制扫描；未使用 scanignore/scandelete | 主仓库依赖来源与开发历史资格审核；仓库候选保持 Disabled，不宣称已获收录 |
| Flathub | 不由助手生成受限提交文件 | 可协助研究规则、解释现有构建失败 | 官方政策要求人类编写 manifest，并由人类处理提交 PR、提交说明、commit message 和审核互动；已知 AI 参与也须披露 |

Windows、AUR、Nix、Snap、AppImage 的已通过基线：[Actions 40427232575](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40427232575)。其中 F-Droid 的早期失败已明确保留，不将这一轮说成全绿。后续独立复验：[Windows（含 Chocolatey 包升级及更新后的核验说明）](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40432101989)、[Linux 各包及 Ubuntu 22.04 断网](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40429645470)、[F-Droid 原发布源码](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40428761641)、[F-Droid 含新版声明源码](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40430519982)、[独立 Nix 配方](https://github.com/Qqv0V6ge/FolderSpan/actions/runs/40431981098)。

当前 [Maven 解析证据](../third-party/maven/current-runtime.json)涵盖普通版 Android release 与 Linux x64 host desktop 的 557 个组件及原始 POM/归档摘要。它不是全平台最终 SBOM，也不代替每个原生库的来源与许可核验。

v1.0.1 保留原安装包，并额外提供：

- [2026-10-27 补充声明](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/THIRD_PARTY_NOTICES-2026-10-27.txt)及[依赖元数据](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/DEPENDENCY_METADATA-2026-10-27.json)。Linux 新候选包携带原声明和补充声明。
- [已核对 JRE 的完整源码索引](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/JRE_SOURCES.json)：Linux x64 Temurin 17.0.20.1+1、Windows ARM64 Microsoft 17.0.19+10。另有[Windows x64 MSI 的对应证据](https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/JRE_WINDOWS_X64-2026-10-27.json)，确认也使用该 Temurin 版本。未把其他目标自动视为完成。

CI 的安装/升级/卸载与窗口响应检查是冒烟验证，不是全功能、数据迁移或所有安全策略的认证。Windows 防火墙保持开启；测试只在临时 runner 阻断应用入站以避免弹窗，不开放端口。

F-Droid 构建会在临时源码副本中移除 Android 不使用的预构建 WebAssembly 与审计 gzip，从已存 Unicode 文本重建 GBK 资源并强制校验相同摘要，使用固定、校验过的 Gradle。未重签用户原 APK、未使用用户私钥，也未宣称可复现上游签名。

Flathub 的限制来自[官方 Generative AI Policy](https://docs.flathub.org/docs/for-app-authors/requirements#generative-ai-policy)，不能通过让人复制 AI 生成内容来规避。本目录其他渠道的材料不应被当作 Flathub 受限提交稿。

已归档的简明检查记录见 [validation.json](validation.json)，无需把大安装包和截图重复提交进源码仓库。AUR 的 `.SRCINFO` 来自实际 makepkg 输出。
