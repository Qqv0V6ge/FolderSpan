## 1. Implementation
- [x] 1.1 新增跨平台 `FileIoWakeLock` 抽象和公共包装器。
- [x] 1.2 为 Android、iOS、JVM Desktop、JS、WasmJs 提供 best-effort 平台实现。
- [x] 1.3 将防休眠接入 `FileUtils` 的实际读写入口与集中式复制/传输链路。
- [x] 1.4 增加包装器释放语义测试并更新相关文档索引。

## 2. Validation
- [x] 2.1 运行 OpenSpec 校验。
- [x] 2.2 运行与改动相关的测试/编译验证。
