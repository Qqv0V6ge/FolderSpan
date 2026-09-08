# FSAR 流式小文件传输

FSAR 是 FolderSpan 在设备复制与链接分享中使用的内部小文件装帧格式。它不是 ZIP/TAR，也不会先生成完整归档；发送端逐条目读取，接收端逐条目写盘。

## 版本与字节布局

FSAR2 是当前唯一格式，在条目帧外使用未压缩的自描述 prelude：

```text
"FSAR" + version(2)
u32 streamHeaderLength
protobuf FolderSpanArchiveStreamHeader {
    codec
    entryCount
    declaredFileBytes
    declaredFrameBytes
}
codec stream {
    FSAR entry frames
    u32(0)
}
```

首版 codec 是 `NONE=0` 与 `ZSTD=1`。zstd 使用 level 1，一个批次共享一个连续压缩上下文；压缩级别不进入 wire format。未知 version、codec 或条目类型必须关闭式失败。

## 协商与传输映射

`FolderSpanArchiveStreamCapabilities` 声明 codecs、最大条目数、单文件大小和批次大小。发送字节前必须取双方 codec 能力交集：

- Session/LAN 默认 FSAR2 + `NONE`，收益来自用一条流承载一批文件；
- WebRTC/WAN 在双方支持时默认 FSAR2 + `ZSTD`；
- 不存在共同 codec 或对端缺失当前能力时直接报告协议错误；
- JS/Wasm 只广告 `NONE`；
- 链接分享请求必须携带 codec 能力，缺失或没有交集时直接拒绝；
- 设备分享不提供 HTTP FSAR 路由，文件列表与读取统一通过只读 Session 完成。

Device→Local、Local→Device 与不同设备间 Device→Device 共用 `SmallFileArchiveTransferCoordinator`。D→D 使用容量为 2 的 `Channel<ByteArray>`，本机只校验 FSAR prelude 并转发压缩区，不解压、不落临时树；目标 trailer 的完成路径是最终提交依据。同设备复制继续走 `copyPath`。

## 边界、安全与任务语义

当前硬边界为：单个小文件不超过 1 MiB、批次不超过 512 条和 32 MiB 未压缩 payload、默认目标批次 256 条、归档读写 buffer 256 KiB。zstd 自身每次输出不超过 64 KiB。

发送端与接收端都规范化相对路径并拒绝绝对路径、反斜杠、空路径、`.`、`..` 和重复路径。接收端在写入前再次执行现有目标授权；文件只有在完整写入并 flush/close 成功后才加入 `completedRelativePaths`。当前条目失败或取消会删除部分文件，之前已经提交的文件保留。

进度只按未压缩文件 payload 统计。D→D 只在目标提交路径后推进一次。批次失败时，任务使用“批次条目减去完成路径”的差集回退到逐文件传输；暂停、取消和 RST 会取消 producer、codec、transport 与 extractor。持久化状态只保存队列条目和已提交结果，不保存活动 zstd 上下文。

## 平台与回滚

Android、Desktop/JVM 与 iOS 通过独立 `zstdMain` 使用 `zstd-kmp`/`zstd-kmp-okio`；JS/Wasm source set 不解析原生 zstd artifact，并提供 `NONE` actual。

回滚不需要迁移数据：在协调器入口关闭小文件批次选择即可让当前设备复制回到逐文件路径；不提供 FSAR1，也不以恢复设备分享 HTTP archive 路由作为回滚方式。

性能验收命令、fixture、测量口径和最近一次结果见活跃 OpenSpec change 的 `benchmark-results.md`。
