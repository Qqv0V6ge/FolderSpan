## 1. 公共预览契约与媒体类型策略

- [x] 1.1 在 linkshare commonMain 中定义 Preview/Attachment 文件交付模式、`preview=1` 请求解析规则，并为 API 请求默认保持 Attachment 编写单元测试
- [x] 1.2 实现统一媒体类型解析器，覆盖完整 MIME、`.ext`、文件名扩展名、平台提示和 `application/octet-stream` 回退，并验证解析优先级不依赖 User-Agent
- [x] 1.3 实现安全的 inline/attachment `Content-Disposition` 文件名编码及主动内容分类策略，覆盖非 ASCII、响应头注入字符和 MIME/扩展名冲突测试
- [x] 1.4 为主动内容预览实现响应级 CSP 沙箱策略及无法隔离时的 Attachment 回退，并验证不授予脚本和同源权限

## 2. 跨平台文件响应实现

- [x] 2.1 重构 commonMain Device/Share 字节范围响应，使 Preview 与 Attachment 复用同一完整/Range 流并分别返回正确 MIME 与处置响应头
- [x] 2.2 在 `LinkShareRouteDispatcher` 完成现有授权与路径检查后选择交付模式，接入目录文件 `preview=1`、非 API 单文件根预览和普通/API 下载兼容逻辑
- [x] 2.3 扩展 `LinkSharePlatformFileResponder` 契约并更新 JVM 实现，使用平台 MIME 提示且保持完整文件、零字节和 Range 响应一致
- [x] 2.4 更新 Android 本地路径与 `content://` responder，优先使用 `ContentResolver` MIME 并遵循 Preview/Attachment 交付模式
- [x] 2.5 更新 iOS 本地文件 responder，接入统一媒体类型及交付模式并保持现有流式范围读取行为

## 3. 链接分享页面交互

- [x] 3.1 为链接分享模板增加可访问的“在浏览器中打开”图标/文案资源，并提供明确的下载操作标签
- [x] 3.2 重构普通文件卡片为下载主体链接与独立预览/下载图标链接，生成编码正确的 `preview=1` URL、`target=_blank` 和 opener 隔离属性，目录卡片保持原导航行为
- [x] 3.3 更新共享样式以支持双操作文件卡片，验证键盘焦点、至少 40px 触摸目标、文本截断及窄屏响应式布局

## 4. 回归与验收

- [x] 4.1 扩展 JVM 路由测试，覆盖预览 `200`、Range `206`、正确 MIME、inline、普通 attachment、未知类型回退和单文件分享行为
- [x] 4.2 增加预览安全回归测试，确认未授权、隐藏文件、Disk 禁止分享和符号链接逃逸不能通过预览参数绕过
- [x] 4.3 增加 HTML 模板测试，确认文件预览与下载入口并存、URL 编码正确、交互元素不嵌套且包含无障碍标签
- [x] 4.4 运行 `./gradlew :core:jvmTest` 和相关 common 测试，并在至少两种原生能力不同的浏览器中验证同一文件由各浏览器独立决定显示或下载

## 5. 预览资格与交互层级收敛

- [x] 5.1 仅为保守浏览器原生展示类型生成并接受 Preview，未知或非展示型文件保持 Attachment，并补充公共、路由与模板回归测试
- [x] 5.2 移除文件卡片内部操作的嵌套 ripple 与重复 hover 背景，先收敛为单层状态并保留键盘焦点可见性
- [x] 5.3 为内联 `text/*` 预览响应声明 `charset=UTF-8`，保持附件和原始字节不变，并补充公共及 JVM 路由回归测试
- [x] 5.4 修复页面打开入口与平台 MIME 附件回退不一致，按当前浏览器能力过滤明确不支持的 PDF/音视频，将打开、下载改为互不重叠且各有单层 hover 的图标链接，并更新 immutable 脚本缓存版本
- [x] 5.5 将文件卡片主体恢复为下载入口，使卡片未被操作图标占用的点击区域触发附件下载，并验证打开图标仍独立触发预览
