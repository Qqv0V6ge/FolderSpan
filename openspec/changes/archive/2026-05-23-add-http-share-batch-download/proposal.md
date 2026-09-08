# Change: Add HTTP Share Batch Download

## Why
当前链接分享页面的“批量下载”主要提供脚本下载，浏览器内无法直接把当前分享目录打包保存。需要新增一个低内存的流式 ZIP 下载路径，让用户在网页中批量保存文件，同时保留默认 HTTP 打开体验。

流式保存依赖浏览器安全上下文，实际下载时需要切换到 HTTPS；由于本项目使用自签名证书，必须在跳转前解释浏览器证书提示是预期行为，并记住用户已同意的选择。

## What Changes
- 在链接分享网页中新增浏览器内批量下载能力，将当前分享范围打包为 ZIP 并以流式方式保存。
- 复用 `fflate.min.js` 与 `folder-zip-worker.js` 进行 ZIP 流式压缩，避免把整个归档缓存在内存中。
- 复用现有列表与文件下载鉴权语义，批量下载仍受分享会话、隐藏文件可见性和 Disk 分享权限限制。
- 保持用户默认打开 HTTP 分享链接；仅在用户触发流式保存时引导到 HTTPS。
- 为 HTTPS 自签名证书增加用户说明、确认与已同意记忆；直接打开 HTTPS 时先回到 HTTP 说明页，再由用户决定是否跳转回 HTTPS。
- 保留现有脚本下载作为流式下载不可用或用户不继续 HTTPS 时的备用方式。

## Impact
- Affected specs: `browse-share-network`, `http-socket-tls-transport`
- Affected code: `HttpShareFileServerCommon.kt`, platform `HttpShareFileServer` implementations, link-share HTML templates, `share-index.js`, `i18n.js`, shared styles, existing `streamsaver` and `workers` static assets
