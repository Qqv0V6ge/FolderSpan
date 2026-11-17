package com.folderspan.ui.state

import androidx.compose.runtime.snapshots.Snapshot

// 独立提交初始记录，让既有快照也可读取共享状态，包括从只读快照触发的初始化。
internal inline fun <T> sharedSnapshotState(create: () -> T): T =
    Snapshot.global { Snapshot.withMutableSnapshot(create) }
