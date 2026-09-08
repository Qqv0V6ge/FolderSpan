## 1. Implementation
- [x] 1.1 新增任务快照与 manifest/checkpoint 持久化存储。
- [x] 1.2 将 `TaskState` 接入任务快照恢复、运行时文件清理与继续能力判定。
- [x] 1.3 将复制、移动、删除任务改为 manifest 驱动执行，并支持从 checkpoint 继续。
- [x] 1.4 在任务列表、任务弹窗、任务结果页增加“继续任务”入口与恢复提示。
- [x] 1.5 补充任务恢复相关测试并完成 JVM 编译/测试验证。
- [x] 1.6 为 `MAX_LENGTH * 30` 及以上的本地/设备大文件复制增加分片级恢复 checkpoint。
