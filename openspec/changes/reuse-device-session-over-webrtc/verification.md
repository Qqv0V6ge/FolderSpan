# Session over WebRTC 验证记录

验证日期：2026-09-05。本文记录实际执行的检查及其边界；仍在执行的项目不作为通过证据。

## 回归用例

旧协议基准版本为迁移提交 `6f906875e62f3a7736eea5e9dc2daa12807f4ee7` 的父提交 `b73bddf8`，在隔离 worktree 中运行。新协议使用当前变更后的生产 Session、WebRTC 平台实现与字节通道适配器。

| 场景 | 输入与操作 | 必须观察到的结果 | 自动化入口 |
| --- | --- | --- | --- |
| 单文件与连续多文件 | 同一连接连续发送 3 个 196,731 字节文件，每个文件后发 RPC | 文件全部字节正确；后续 RPC 成功；无需重建连接 | `verifyRealWebRtcSequentialTransfers` |
| 并发文件与 RPC | 4 个各 2 MiB、内容不同的文件并发发送，同时发 10 个 RPC | 每条流按独立偏移核对内容和长度；活动流确实重叠；RPC 成功 | `verifyRealWebRtcConcurrentTransfersAndRpc` |
| 暂停与恢复 | 128 KiB 文件发送第一块后暂停 producer，执行 RPC，再继续剩余数据 | 暂停期间接收量保持 64 KiB；Session 可用；恢复后内容完整 | `verifyRealWebRtcPauseAndResume` |
| 暂停后取消 | 第一块到达后取消暂停的写任务 | 远端收到 RST；取消前后 RPC 均成功 | `verifyRealWebRtcPausedStreamCancellation` |
| 连接中断 | RPC 已到远端但响应尚未完成时关闭 DataChannel | 挂起 RPC 在 2 秒期限内失败，且失败原因不是测试自身超时 | `verifyRealWebRtcDisconnectAndFreshSession` |
| 断开后新建连接 | 关闭并释放旧 pair，然后创建新 PeerConnection/Session | 新连接可完成连续文件和 RPC | 同上；不等同于产品连接管理器自动重试 |
| 多 peer 隔离 | 同时建立两组真实 PeerConnection，关闭其中一组 | 另一组 RPC 继续成功 | `verifyRealWebRtcPeerIsolation` |
| 浏览器与原生互通 | JVM 与 Chrome 双向各发送 3 个 512 KiB 文件和 10 个 RPC | 内容、状态、请求数正确；并发流重叠；RPC 在文件活动期间执行 | `python3 scripts/verify_webrtc_interop.py` |
| 背压与无界队列防护 | 超过消息、队列元素、累计字节限制；模拟高水位不消退、发送失败和关闭 | 受限等待或关闭；唤醒挂起操作；不无限累计内存 | `WebRtcDeviceSessionByteChannelTest` |
| 版本不兼容 | 原生对连接收旧 control 标签或不同主版本 v2 | 明确拒绝，释放 PeerConnection，保留本地化错误，不安装业务客户端 | `WebRtcControllerFailureJvmTest`；真实 DataChannel 在私有信令接入边界送入控制器，不包含审批或整套旧应用启动 |
| 分享审批和保存到本地 | 心跳审批→一次性授权→WebRTC→只读 Share Session→公共任务队列复制到本地→进度→取消 | 不二次审批；使用公共任务队列进度、TaskState 取消和 Session 流关闭；取消不误报失败 | `WebRtcShareWorkflowJvmTest`：两个隔离 JVM，分别验证 128 MiB 保存的暂停/恢复与暂停/取消 |

上表传输层暂停/恢复测试控制文件 producer；分享流程测试另外经过真实公共任务调度器，用目标文件大小、SHA-256 和任务字节进度验证暂停稳定，再验证恢复或取消。屏幕 UI 点击不属于这些自动化测试的覆盖范围。

## 当前已执行证据

- `78227e05`（含帧复制优化及信用唤醒修复）回归：Core JVM **1,454** 项、共享 UI JVM **383** 项、Desktop **2** 项，均为零失败、零错误、零跳过。Android debug APK 和独立测试 APK、Web JS/Wasm 编译通过。
- Chrome JS WebRTC、帧读取与信用唤醒组 **72** 项、Wasm **71** 项全部通过，已核对 XML 的实际运行数，包含真实 SCTP/DTLS Session 场景。
- 最终 Xcode 模拟器构建 `ios-credit-xcode` 成功，使用 `iosApp.xcworkspace`、`iosApp` scheme、Debug、`CODE_SIGNING_ALLOWED=NO`；已包含控制器释放、分享取消、帧读取性能和信用唤醒修复。
- JVM↔Chrome 两个进程各运行 **1** 项互通检查，双向各 3 个 512 KiB 文件、10 个 RPC、完整内容和活动重叠均通过。它不包含完整分享审批/UI。
- 新接入 Android 真机 **Redmi K30 Pro / Android 12 / arm64-v8a / serial 1a094bab**：新版独立测试包运行 **13** 项功能测试全部通过（6 项 Session 场景、1 项原生消息迟订阅、3 项帧读取边界、3 项信用及关闭唤醒），`OK (13 tests)`；另一次独立性能测试 `OK (1 test)`，包含6个样本，合计14项。旧 F50 曾运行 7 项通过；两台设备的性能不横向对比。
- iOS 模拟器 WebRTC、帧读取与信用唤醒组 **74** 项全部通过，显式使用本地 TURN。该主机未配置 TURN 时曾 ICE Failed，不能称为无 TURN 直连成功。
- `WebRtcShareSaveJvmTest` 两项已通过：生产心跳 dispatcher、一次性授权验证、原生 WebRTC、Session 认证、Share→Local 任务处理器、正字节进度、实际暂停、恢复完整内容和取消终态。UI 审批、授权签发、信令交付和公共保存入队仍是该夹具的边界，不能用它单独完成 6.5。
- 两进程 `WebRtcShareWorkflowJvmTest` 的暂停/恢复与暂停/取消场景已在完整 JVM 回归中通过；代码经独立复核。其夹具边界见下节。
- 上述已结束的 Gradle、浏览器、真机 instrumentation、Xcode 和 TURN 运行都已确认本次进程退出；未清理其他任务的进程。

### 本次修复的实际问题

1. 设备分享目录经过中间符号链接可列出授权根以外的目录。目录列表现在复用既有 canonical path 与祖先符号链接权限检查；先复现失败，再验证修复。
2. WebDAV PROPFIND 混入非法 href 时整次目录列表失败。解析现在逐项拒绝非法条目并保留安全条目；请求端非法路径仍在发请求前被拒绝。
3. 独立分享审批端口可能先被内部随机端口分配占用。JVM/Android 先绑定审批端口，再分配内部端口；确定性测试在内部端口分配回调中验证审批端口已经占用，并验证启动失败后的释放和重启。

4. Android 依赖库将借用的原生 `ByteBuffer` 交给异步 Flow，回调返回后文件内容可能损坏；晚订阅还会漏掉首包。Android 现在直接在原生 PeerConnection 回调内接管 DataChannel，并在消息回调返回前复制字节，用有界队列保存订阅前的消息与 ICE。关闭与原生回调采用非阻塞读锁和异步释放，防止读已释放对象或形成锁反转。先复现内容损坏及迟订阅超时，再验证全部 4 个消息的内容和顺序。

5. 控制器拒绝协议版本或认证超时后原本只发布 Error，不释放 PeerConnection，且优先显示底层英文错误。现在先废止旧 generation、关闭 Session/PeerConnection，再发布本地化错误。SDP 与 ICE 的排队任务绑定原 PeerConnection 和 generation，避免旧 answer 关闭替换后的新连接；已先复现该竞态。
6. 分享保存取消曾落入通用异常处理并发布“分享失败”。实际异常边界已抽取为 `runDeviceShareConnectionAttempt`，取消专门断开并透传，普通错误仍走失败处理；独立回归先复现取消被吞。

7. JVM 性能采样发现 Session 接收端先分配 payload，再拼接完整帧并重新解析，造成两次额外全量复制；现在共用帧头校验，直接接收已知长度的 payload。JVM DataChannel 状态由原生 observer 单调更新，避免每个分片同步跨 JNI 查询。非法头、截断、最大 payload、连续帧及高低水位竞争写入边界均有回归。

8. Session 信用等待者曾共用单消费者通知，其他 stream 可能取走唯一唤醒，导致有信用的流无法推进；另外，零自身 in-flight 的 writer 在 RST 或连接关闭后可能仍等到信用超时。两类问题均先由定序测试复现。信用变化现在发布 StateFlow 版本广播；等待者在同一锁内检查额度并获取版本，在订阅前复查连接/流状态；关闭通过非挂起通知唤醒全部等待者。没有增加关闭流 id 的累积集合。

### 测试运行环境修复

- macOS 临时目录 `/var` 的别名会影响符号链接安全断言；测试根目录改为真实规范路径，保留原安全要求。
- 分享审批测试改用非系统临时出站端口范围，隔离预期的连接拒绝异常，修正 JUnit 测试返回类型。
- 更新随剪贴板统一入口变更而过时的 Android 源码断言；公共异步测试使用各平台 `runSuspendTest`。
- Karma 启用真实 Chrome、60 秒异步测试超时和 `failOnEmptyTestSuite`。筛选 Kotlin/JS 测试时使用包含方法的模式，防止“0 项测试但构建成功”。
- 本机代理虚拟网卡会令无媒体权限的 Chrome 仅公布 `198.18.0.1`，纯 JS 最小复现也无法本机对连。测试 Chrome 使用模拟媒体设备与模拟授权，setup 获取并立即停止模拟音轨，使本机网卡候选可用。没有访问真实麦克风或修改系统代理。
- iOS 命令行测试原先阻塞主线程，WebRTC 的 `MainScope` 事件无法执行。测试 helper 改为运行 Darwin 主循环并等待有超时的 suspend 测试。修复后可看到 `10.0.0.107` 和代理虚拟网卡候选，但本机直连仍失败；测试支持显式 `FOLDERSPAN_TEST_TURN_URL`，配置 `turn:127.0.0.1:18480?transport=tcp` 后通过。该参数仅作用于测试，不修改产品 ICE 配置。
- Android 独立 instrumentation 测试复用 commonTest；兼容最低 Android 版本的 DEX 限制，公共测试方法使用不含空格的名称。

## 分享保存的验收口径

主规范 `share-to-device-device-copy` 要求接收端从远端 Device/Share 源拉取到本地，当前只读 Share Session 接入公共 `FileProtocol.Share → Local` 任务队列符合此定义。服务器侧 `CopyPath/CopyControl` RPC 是另一类远端到远端复制，分享 token 不允许调用，不能将该 RPC 的 requestId 进度测试作为分享保存通过证据。

本次验证不额外声称 Share→Local 取消会删除不完整目标：它沿用现有公共 Device→Local 的目标文件与恢复策略。Session 入站写流中断删除未提交目标的测试，只证明对应入站写流路径。两进程 `WebRtcShareWorkflowJvmTest` 已串联生产 `DeviceState.share`、TLS 心跳自动审批、一次性授权签发与消费、LAN 失败后回退 WebRTC、控制器协商与认证，以及 `connectShare → copyShareEntriesToLocal → pasteCopyFilesWithReplace` 公共保存队列。两个场景各复制 128 MiB：观察真实正字节进度后暂停，同时核对文件尺寸、SHA-256 和字节进度停止；恢复场景验证完整 SHA-256 与成功任务移除；取消场景验证 FAILURE、取消原因、部分目标不再变化、连接收尾且没有分享失败通知。

该测试仅以夹具提供设备发现描述和本机 TCP 信令交付，并在控制器私有信令接入点送入 SDP/ICE；没有驱动屏幕 UI、真实房间信令服务或跨网设备发现。所有应用状态、数据库、偏好设置与文件位于独立子进程和临时目录；失败保留日志到异常，最终有界退出并删除临时目录。

## 权限验证边界

`DeviceSessionPermissionTransportJvmTest` 使用真实 Session Peer、Transport、Protobuf、路径/文件服务和临时权限数据库，分别经过分片连续字节通道及模拟 DataChannel + 生产 WebRTC 适配器，检查敏感路径、角色、分享只读、符号链接、混合批量结果顺序和根外无写入。

该夹具预绑定测试身份，不包含真实 TLS 握手、WebRTC ICE 或完整审批流程。因此它是公共业务权限的载体契约证据，不能单独写成真实跨设备权限端到端通过。

当前 HTTP dispatcher 已退休旧设备文件/路径入口，仅保留独立 `/api/share/heartbeat` 审批入口。HTTP 的预期行为是旧业务路由返回 404；不再将内部 handler 直调描述成 HTTP 业务等价验证。

## 性能测量口径

- JVM、Chrome JS 和至少一个移动端分别对比旧协议与新 Session 协议；在同一平台、同一设备上比较。主机为 macOS arm64，Chrome 152.0.7977.83；最终移动端为 Redmi K30 Pro、Android 12、arm64-v8a。使用 webrtc-java 0.16.0 和 webrtc-kmp 0.125.11。
- 两个真实 PeerConnection 使用本机 SDP/ICE 交换，传输字节实际经过 SCTP/DTLS。基准为随机数据发送和接收计数，不含磁盘 I/O、公网信令、STUN/TURN 或跨网延迟。
- 新 Session 使用产品 `DeviceSessionWindowPlan.fromMemory()`；功能测试的小窗口不用于性能比较。新旧 JVM/Android 基准都在 `Dispatchers.Default` 执行，避免把测试线程串行执行与产品后台执行直接比较。
- 先发送 256 KiB 预热，再测单流 4 MiB 与 4 流共 16 MiB，各重复 3 次。记录耗时并使用中位数比较吞吐。
- `bufferedAmount` 与内存每 2 毫秒采样。日志中的 `sampledPeakBuffered` 是采样峰值，可能漏掉瞬时尖峰；零表示采样未看到排队，不表示底层从不排队。
- JVM/Android 的内存指标为 Java 堆使用量；Chrome 为 JS 堆；iOS 为 resident size。各平台数值不能横向等同，Java/JS 堆不包含全部原生 WebRTC 内存。
- 最终性能测量在本任务其他构建及测试进程退出后独立执行。完整样本、初始基线、优化前结果、信用修复前后的重复样本及失败记录均保存在 [benchmark-results.json](./benchmark-results.json)。

### 性能结果与验收判断

时间单位为毫秒；单流为4 MiB，并发为4流共16 MiB。表中吞吐变化按中位耗时计算，所有对应样本均纳入，未删除慢样本。

| 平台与样本范围 | 单流：旧 → 新 | 4流：旧 → 新 | 吞吐变化：单流 / 4流 |
| --- | ---: | ---: | --- |
| JVM，单轮各3个样本 | 29 → 37 | 107 → 130 | -21.6% / -17.7% |
| JVM，20轮各60个样本 | 28 → 29 | 107 → 117 | -3.4% / -8.5% |
| Chrome，各3个样本 | 134 → 69 | 522 → 265 | +94.2% / +97.0% |
| Redmi，各3个新协议样本 | 旧仅1个样本156；新中位数 179 | 旧未完成；新中位数 563 | 不计算；新并发 28.4 MiB/s |

JVM 单轮结果受启动与 JIT 预热影响，原始结果仍保留；另外以相同基准各运行20轮新建连接作对照。新协议60个并发样本的中位数为117 ms，P95为145 ms（nearest-rank，即排序后第 `ceil(0.95×60)=57` 项），最大1038 ms；旧协议对应中位数107 ms、最大116 ms。新协议120个样本全部完成，但不能宣称长尾已经消失。

本次保留统一 Session 的流隔离、正确终态和有限信用窗口，以额外约10 ms/16 MiB的连续并发耗时作为可接受的已知成本；浏览器中位吞吐约翻倍，移动端新协议完整完成测试。没有既定的跨网吞吐或P95 SLA，这些本机样本不构成公网体验保证。8.4 的完成表示已完成对比、修复确定性阻塞缺陷并明确接受和记录当前取舍，不表示所有平台、所有指标都优于旧版。

下表为单轮样本中的采样峰值（MiB），Java/JS堆不是全部原生内存；Redmi旧数据只有1个样本，与新数据6个样本的最大值不能作对等优劣判断。

| 平台 | 受管堆峰值：旧 → 新 | DataChannel缓冲峰值：旧 → 新 |
| --- | ---: | ---: |
| JVM | 166.0 → 195.2 | 5.90 → 0.51 |
| Chrome | 262.9 → 299.1 | 1.97 → 3.62 |
| Redmi | 20.3 → 56.5 | 2.99 → 0.90 |

受管堆受GC时机影响，未将这些数值解释为内存泄漏或全面内存改善。有限队列由实现和边界测试共同验证：Session帧队列最多64帧；载体入口最多512条/8 MiB；出站高/低水位为4/1 MiB，按16 KiB分片检查，并在超时、超限、关闭和取消时结束等待。浏览器并发缓冲较旧串行计划增大，但保持在配置上限范围内。

修复前曾记录到9426 ms的并发样本。诊断发现信用通知被错误stream取走，以及关闭后信用等待不退出的确定性缺陷，均已先复现再修复。正常轮的JFR没有长GC，UDP全局统计在重复测试期间观察到接收缓冲满丢包；这些不是该9426 ms现场的逐连接证据，不能认定唯一根因。修后1038 ms样本也完整保留。

旧JVM基准有20轮成功对照，但最终复现又在预热接收阶段120秒超时：sender=Completed、receiver=InProgress，两端通道仍Open，清理后没有残留进程。Android旧协议同样未完成全部样本。它们属于旧协议失败基线，未被计入当前版本回归通过数，也未将部分旧样本当作完整性能提升证明。


## 重复运行

所有 Gradle 命令使用 `--no-daemon`。运行结束需确认本次启动的 wrapper、Gradle 子进程、测试浏览器和应用进程退出。

```bash
./gradlew --no-daemon :core:jvmTest :app:shared:jvmTest
./gradlew --no-daemon :core:jsBrowserTest --tests 'com.folderspan.service.webrtc.*' --tests 'com.folderspan.service.session.DeviceSessionIoTest.*' --tests 'com.folderspan.service.session.DeviceSessionCreditWakeTest.*'
./gradlew --no-daemon :core:wasmJsBrowserTest --tests 'com.folderspan.service.webrtc.*' --tests 'com.folderspan.service.session.DeviceSessionIoTest.*' --tests 'com.folderspan.service.session.DeviceSessionCreditWakeTest.*'
SIMCTL_CHILD_FOLDERSPAN_TEST_TURN_URL='turn:127.0.0.1:18480?transport=tcp' ./gradlew --no-daemon :core:iosSimulatorArm64Test --tests 'com.folderspan.service.webrtc.*' --tests 'com.folderspan.service.session.DeviceSessionIoTest.*' --tests 'com.folderspan.service.session.DeviceSessionCreditWakeTest.*'
./gradlew --no-daemon :core:assembleAndroidDeviceTest
python3 scripts/verify_webrtc_interop.py
openspec validate reuse-device-session-over-webrtc --strict
```

互通脚本的每个 Gradle 使用 1 个 worker、最多 4 GiB Java 堆；两个端点同时运行，验证主机有 16 GiB 内存。互通脚本只监听 `127.0.0.1`，用独立 JVM/Chrome 测试端点交换 SDP/ICE。日志保存在 `build/webrtc-interop/`，脚本检查两端各实际运行 1 项测试并收尾本次进程树。两个端点源码只在 `-PwebrtcInterop` 下加入，普通测试任务不等待外部端点。

Android 测试 APK 使用独立测试包，不能覆盖或清空用户安装的 `com.folderspan` 应用。最终真机验证分开运行功能与性能：

```bash
adb -s 1a094bab install -r -t core/build/outputs/apk/androidTest/core-androidTest.apk
adb -s 1a094bab shell am instrument -w -r -e class com.folderspan.service.webrtc.WebRtcAndroidSessionTest,com.folderspan.service.webrtc.WebRtcAndroidIngressTest,com.folderspan.service.session.DeviceSessionIoTest,com.folderspan.service.session.DeviceSessionCreditWakeTest com.folderspan.core.test/androidx.test.runner.AndroidJUnitRunner
adb -s 1a094bab shell am instrument -w -r -e class com.folderspan.service.webrtc.WebRtcSessionBenchmarkAndroidTest com.folderspan.core.test/androidx.test.runner.AndroidJUnitRunner
```

必须核对 instrumentation 输出中的实际测试数与 `OK`，并从该次测试进程的 logcat 提取 `WEBRTC_BENCH`，避免混入旧协议或先前运行的数据。

主机性能测试在其他构建和浏览器退出后顺序运行：

```bash
./gradlew --no-daemon :core:jvmTest --tests 'com.folderspan.service.webrtc.WebRtcSessionBenchmarkJvmTest.*'
./gradlew --no-daemon :core:jsBrowserTest --tests 'com.folderspan.service.webrtc.WebRtcSessionBenchmarkBrowserTest.*'
```

JVM 新旧基准都支持可重复的多轮运行：`FOLDERSPAN_WEBRTC_BENCHMARK_RUNS=20 ./gradlew --no-daemon :core:jvmTest --rerun --tests 'com.folderspan.service.webrtc.WebRtcSessionBenchmarkJvmTest.*'`；旧协议 worktree 使用 `com.folderspan.service.webrtc.LegacyWebRtcBenchmarkTest.*` 类名。默认 1 轮，允许 1～20 轮；每轮重新创建真实 PeerConnection 并按相同的 256 KiB 预热和 6 个样本测量。`--rerun` 必须保留，环境变量本身不参与 Gradle 的增量输入判定。日志用 `WEBRTC_BENCH_RUN iteration=` 区分轮次，任一轮失败会使测试失败。

Xcode 构建使用 `xcodebuild -workspace app/iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`。

iOS 测试使用本地 TURN 时，先在另一个前台终端运行（结束验证后关闭）：

```bash
turnserver -n -L 127.0.0.1 -E 127.0.0.1 -p 18480 --min-port=18490 --max-port=18530 -m 1 --no-tls --no-dtls --no-cli --no-multicast-peers --allow-loopback-peers --lt-cred-mech --realm=webrtc-test --user=webrtc-test:local-test-only
```

此 TURN 仅监听本机，用户名和密码为公开的测试常量。

旧协议基准复现脚本代码保存在 `scripts/webrtc_legacy_benchmark.patch`，仅用于基线提交的隔离 worktree：

```bash
git worktree add --detach /tmp/folderspan-webrtc-baseline b73bddf8
git -C /tmp/folderspan-webrtc-baseline apply /absolute/path/to/FileManager/scripts/webrtc_legacy_benchmark.patch
cd /tmp/folderspan-webrtc-baseline
./gradlew --no-daemon :core:jvmTest --tests 'com.folderspan.service.webrtc.LegacyWebRtcBenchmarkTest.*'
./gradlew --no-daemon :core:jsBrowserTest --tests 'com.folderspan.service.webrtc.LegacyWebRtcBenchmarkBrowserTest.*'
./gradlew --no-daemon :core:assembleAndroidDeviceTest
```

旧协议 Android APK 仍使用独立测试包；在该 worktree 执行 `adb -s 1a094bab install -r -t core/build/outputs/apk/androidTest/core-androidTest.apk` 后，运行 `adb -s 1a094bab shell am instrument -w -r -e class com.folderspan.service.webrtc.LegacyWebRtcBenchmarkAndroidTest com.folderspan.core.test/androidx.test.runner.AndroidJUnitRunner`。只安装基准测试 APK，不安装旧版用户应用；之后可重新安装主工作区测试 APK。

该补丁只提供基准和编译所需的测试兼容调整，不改变旧 WebRTC 生产业务协议。测试有 120 秒业务期限和逐项 5 秒挂起清理；同步原生调用不能被协程超时抢占，本次执行另由外层进程监控提供硬期限并回收本次进程树。Android 旧协议在 F50 完成单流样本后、在新 Redmi 只完成第一个单流样本后，均曾超过 120 秒；旧 Redmi 未完成并发样本。失败保留，不把 instrumentation 的 ADB 退出码 0 当作测试通过。
