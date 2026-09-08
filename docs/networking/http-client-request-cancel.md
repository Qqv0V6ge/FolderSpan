# HTTP 客户端请求取消说明

本文说明如何使用 `requestId` / `batchId` 取消正在进行的 HTTP 请求，以及后续新增 HTTP 请求时如何接入取消能力。

## 适用范围
- `HttpRouteClientManager` 下的路由客户端：`DeviceRouteClient` / `BookmarkRouteClient` / `FileRouteClient` / `PathRouteClient`

Remote Share 的列表和读取使用 Session，不属于本文所述的 HTTP 请求取消机制。

## 概念
- `requestId`：单个请求的标识。用于取消“这一条”请求；也可用于“同 ID 新请求替换旧请求”（旧请求会被自动取消）。
- `batchId`：请求批次标识。用于一次性取消一组请求（例如一次 UI 操作触发的多个 HTTP 调用）。

两者都是可选参数；如果你需要“按 ID 精准取消”，请务必由调用方传入可预测/可复用的 ID。

## 快速开始

### 1) 发起请求时传入 `requestId` / `batchId`
```kotlin
val requestId = "paths:list:/Movies"
val batchId = "browse:${deviceId}"

val result = httpClientManager.pathRouteClient.listPath(
    request = ListRequest("/Movies"),
    requestId = requestId,
    batchId = batchId,
)
```

### 2) 在需要时取消
```kotlin
// 取消单个请求
httpClientManager.cancelRequest(requestId)

// 取消一批请求
httpClientManager.cancelBatch(batchId)

// 取消该 manager 下所有正在进行的请求
httpClientManager.cancelAllRequests()
```

## 取消 API（调用方入口）

### HttpRouteClientManager
- `suspend fun cancelRequest(requestId: String, reason: String = "取消请求"): Boolean`
- `suspend fun cancelBatch(batchId: String, reason: String = "取消请求批次"): Int`
- `suspend fun cancelAllRequests(reason: String = "取消所有请求"): Int`

注意：取消只对“同一个 manager 实例”里发起并登记的请求生效；不同 manager 之间不共享请求 ID 空间。

## 行为约定（你在业务层需要知道的）
- 对于返回 `Result<T>` 的 API：被取消时会返回 `Result.failure(CancellationException)`（可用 `exceptionOrNull()` 判断）。
- 对于返回 `Flow<Result<T>>` 的 API：被取消时会“额外发送一次 `Result.failure(CancellationException)`”，然后结束流。
- 同一个 `requestId` 在发起新请求时会自动取消旧请求（“latest wins”），适用于搜索/刷新等只需要最新结果的场景。
- 如果不传 `requestId`，内部会生成自动 ID（`__auto__...`）；这种请求你无法在外部按 ID 精准取消，只能用 `cancelAllRequests()` 或用 `batchId` 批量取消。

如果你需要区分“主动取消”与“其他异常”，可以判断异常类型：
- `exceptionOrNull() is HttpClientRequestCancelledException`（其中包含 `requestId` / `batchId`）

## 常见用法建议
- “输入框实时搜索”：固定使用同一个 `requestId`（例如 `"search:files"`），每次输入触发的新请求会自动取消上一次。
- “一次操作触发多次请求”：所有请求共用一个 `batchId`（例如 `"task:${taskId}"`），在用户点击取消时调用 `cancelBatch(batchId)`。

## 后续新增请求怎么接入取消能力

### 1) `Result<T>` 风格（推荐）
1. 在函数参数末尾增加：
   - `requestId: String? = null`
   - `batchId: String? = null`
2. 用对应 manager 的 `requestRegistry.trackResult(requestId, batchId) { ... }` 包裹请求实现。

模板（以 `HttpRouteClientManager` 下的路由客户端为例）：
```kotlin
suspend fun foo(
    arg: FooRequest,
    requestId: String? = null,
    batchId: String? = null,
): Result<FooResponse> {
    return manager.requestRegistry.trackResult(requestId, batchId) {
        runCatching {
            // httpClient.post(...)
        }
    }
}
```

### 2) `Flow<Result<T>>` 风格（流式/分片返回）
1. 在函数参数末尾增加 `requestId` / `batchId`（同上）。
2. 外层用 `channelFlow { ... }`，并把实际执行逻辑放进 `requestRegistry.track(requestId, batchId) { ... }`。
3. 捕获 `CancellationException` 时，使用 `NonCancellable` 尝试发送一次 `Result.failure(e)` 后结束。

模板：
```kotlin
fun fooStream(
    arg: FooRequest,
    requestId: String? = null,
    batchId: String? = null,
): Flow<Result<FooChunk>> = channelFlow {
    manager.requestRegistry.track(requestId, batchId) {
        try {
            // 循环读取/分片处理，并持续 send(Result.success(...))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { send(Result.failure(e)) } }
        } catch (e: Exception) {
            send(Result.failure(e))
        }
    }
}
```

### 3) 带回调（trailing lambda）的函数
如果函数最后一个参数是回调（例如 `replyCallback: (Result<T>) -> Unit`），为了保持调用端可用 trailing lambda，
请把 `requestId` / `batchId` 放在回调参数之前：

```kotlin
suspend fun getList(
    path: String,
    requestId: String? = null,
    batchId: String? = null,
    replyCallback: (Result<List<FileSimpleInfo>>) -> Unit,
)
```

## 排错清单
- `cancelRequest(...)` 没生效：确认你取消的是“发起请求的同一个 manager 实例”，且 `requestId` 完全一致（会 `trim()`，空字符串会被忽略）。
- 想按 ID 取消但找不到 ID：确认发起请求时是否显式传入了 `requestId`（未传会使用自动 ID）。
