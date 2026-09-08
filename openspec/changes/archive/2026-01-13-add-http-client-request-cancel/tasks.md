## 1. Implementation
- [x] 1.1 Add a cancellable request registry and handle types in `shared/src/commonMain/kotlin/com/folderspan/service/http/client`, including tracking by request ID and batch ID with cleanup on completion.
- [x] 1.2 Add cancellation APIs to `HttpRouteClientManager` and `HttpShareRouteClientManager` (cancel by ID, cancel by batch, cancel all) and wire them to the registry.
- [x] 1.3 Update all client request methods to accept optional requestId/batchId inputs, register requests, and return `Result.failure` on cancellation (including flow-based operations and long-running loops).
- [x] 1.4 Add shared tests for the request registry cancellation behavior in `shared/src/commonTest`.
- [x] 1.5 Run `./gradlew :shared:jvmTest`.
