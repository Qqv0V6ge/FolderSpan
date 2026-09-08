# FolderSpan 文档中心

本目录集中存放跨模块的开发、产品与运维文档。文档按职责分类，模块内部的说明仍放在对应源码目录中。

## 目录分类

| 目录 | 内容 |
| --- | --- |
| [`delivery/`](delivery/) | CI/CD、签名、打包与发布流程 |
| [`networking/`](networking/) | 网络协议、设备连接、HTTP、MCP 与平台原生网络依赖 |
| [`product/`](product/) | 通知、权限、设置同步与任务状态等产品行为约定 |
| [`security/`](security/) | 敏感数据、路径保护与安全维护清单 |

## 文档索引

### 构建与发布

- [CI/CD 自动打包与发布](delivery/ci-cd-pipeline.md)
- [各平台 Release 打包指南](delivery/release-packaging.md)

### 网络与连接

- [同账号设备局域网自动连接](networking/account-device-lan-auto-connect.md)
- [Device Session over WebRTC](networking/device-session-over-webrtc.md)
- [HTTP 客户端请求取消](networking/http-client-request-cancel.md)
- [iOS 原生网络依赖](networking/ios-native-libs.md)
- [MCP HTTP 服务](networking/mcp-http-server.md)
- [网络协议新增与维护需求](networking/network-protocol-requirements.md)

### 产品行为

- [通知开发说明](product/notifications.md)
- [权限需求与新增指引](product/permissions.md)
- [设置与数据同步白名单](product/settings-sync-whitelist.md)
- [任务传输提示文本](product/transfer-task-status-texts.md)

### 安全

- [2026-08-21 全仓安全审计报告](security/2026-08-21-security-audit-report.md)
- [2026-08-21 全仓安全审计报告（第二轮）](security/2026-08-21-security-audit-round-2.md)
- [2026-08-21 全仓安全审计报告（第三轮）](security/2026-08-21-security-audit-round-3.md)
- [2026-08-22 全仓安全审计报告（第四轮）](security/2026-08-22-security-audit-round-4.md)
- [2026-08-23 全仓安全审计报告（第五轮）](security/2026-08-23-security-audit-round-5.md)（FS-21～FS-27 已修复）
- [2026-08-23 全仓安全审计报告（第六轮）](security/2026-08-23-security-audit-round-6.md)（FS-28 已修复）
- [2026-08-23 全仓安全审计报告（第七轮）](security/2026-08-23-security-audit-round-7.md)（FS-29～FS-31 已修复）
- [2026-08-23 全仓安全审计报告（第八轮）](security/2026-08-23-security-audit-round-8.md)（FS-32～FS-37 已修复）
- [2026-08-23 全仓安全审计报告（第九轮）](security/2026-08-23-security-audit-round-9.md)（FS-38～FS-44 已修复）
- [2026-08-24 全仓安全审计报告（第十轮）](security/2026-08-24-security-audit-round-10.md)（技能面 CSRF/XSS 等无新登记）
- [2026-08-24 全仓安全审计报告（第十一轮）](security/2026-08-24-security-audit-round-11.md)（FS-45 / FS-46 已修复）
- [2026-08-24 全仓安全审计报告（第十二轮）](security/2026-08-24-security-audit-round-12.md)（整数溢出/拷贝 IDOR 等无新登记）
- [2026-09-02 全仓安全审计报告（第十三轮）](security/2026-09-02-security-audit-round-13.md)（WebRTC Session 启动授权、分享路径隔离、FSAR2 落盘；Device/Share 流式复制到网盘路径穿越，FS-47 已修复）
- [2026-09-03 全仓安全审计报告（第十四轮）](security/2026-09-03-security-audit-round-14.md)（Device/Share 目录复制到本机路径穿越，FS-48 已修复）
- [敏感与重要文件、目录清单](security/sensitive-files-and-directories.md)

## 维护约定

- 新文档应放入最贴近其职责的分类目录；仅在现有分类无法容纳一组文档时新增目录。
- 新增、移动或删除文档时，同步更新本索引和仓库根目录的 `md_descriptions_paths.md`。
- 文档之间优先使用相对链接；移动文档后同步更新源码、测试和活跃文档中的路径引用。
