# AGENTS 指南（service/operation）

本文件适用于 `com.folderspan.service.operation` 包。

## 目录职责
- 本目录只放服务层长耗时操作的通用执行基础设施，例如遍历并发、文件执行并发、端点并发策略、运行状态反馈。
- 这里不是 UI state 层；不得引入 Compose、`ui.state.*` 单向状态管理或界面展示逻辑。
- 调用方可以来自文件操作、网络同步、索引扫描、分享、HTTP/WebRTC 客户端等模块，但本目录应保持可复用。

## 依赖边界
- 允许依赖通用数据模型、HTTP 路由常量与 Kotlin 协程基础库；transfer status/runtime tuning 自身归属于本目录。
- 不要依赖 `TaskState`、`FileState`、Compose runtime 或具体 UI 组件。
- 不要在本目录持有数据库、Koin 注入对象、桌面状态对象或长期生命周期 scope。
- 需要端点状态时由调用方传入，或只读取通用 runtime tuning；不要在这里解析设备列表、共享会话或网络账号。

## 并发策略规则
- Local、Share、Network 只根据本机运行状态和本次请求耗时动态调整并发。
- Device 需要同时考虑本机状态和远端设备状态，并取更保守的并发值。
- 遍历阶段只负责发现条目和生成清单，不改变调用方的实际消费顺序。
- 执行阶段可对文件队列动态调整 worker 同时执行数量，但不得破坏删除父子顺序、manifest checkpoint、继续任务和失败重试语义。
- 失败应 fail-fast 并保留原始错误；取消/暂停必须停止继续派发新的列表或操作请求。

## 命名与结构
- 端点分类放在 `OperationEndpointKind.kt`。
- 遍历/扫描逻辑放在 `OperationParallelTraversal.kt`。
- 自适应执行并发计算与限流放在 `OperationAdaptiveParallelism.kt`。
- 新增通用函数优先用 `internal`，除非确实需要跨模块公开。
- 新增方法必须写清楚状态来源、并发上限、取消和失败语义。

## 测试
- 纯算法测试放在 `core/src/commonTest/kotlin/com/folderspan/service/operation`。
- 至少覆盖：端点状态来源、动态升降、失败 fail-fast、取消停止派发、清单排序不变。
- 修改并发策略后至少运行 `./gradlew :core:jvmTest`。
