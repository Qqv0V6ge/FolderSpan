# 抽屉展开/收起

所有支持展开/收起的 Drawer 模块必须：

- 在 `DrawerState` 中保存展开状态，并通过 `Settings` 持久化。
- 通过 `DrawerState.updateExpand*` 触发切换，以保证状态写入本地设置。
- 折叠时隐藏内容（例如提前 return 或对内容块加条件判断）。
- 使用展开/收起图标对（`Icons.Default.ExpandLess` / `Icons.Default.ExpandMore`）。

新增 Drawer 模块时，请在 `DrawerState` 添加对应状态与设置键（`SettingsUtils`），并遵循以上规则。
