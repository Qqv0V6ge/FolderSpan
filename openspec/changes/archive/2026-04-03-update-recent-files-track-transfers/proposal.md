# Change: Record transfer destinations in recent files

## Why
当前“最近文件”只在 `FileScreen` 点击文件或文件夹时记录，复制/上传/下载等传输完成后不会写入最近项。
这会导致用户刚传输完成的目标文件或目标文件夹无法在“最近文件”里快速返回。

## What Changes
- 扩展 `track-recent-files` 能力，在成功传输后记录目标文件或目标文件夹。
- 仅记录成功完成的目标路径，不记录失败或取消的传输。
- 复用现有最近项的去重、更新时间与裁剪规则。

## Impact
- Affected specs: `track-recent-files`
- Affected code: `FileState`, 传输/复制完成路径，`FileRecentState`，相关测试
