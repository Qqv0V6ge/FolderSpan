package com.folderspan.service.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal actual val platformSupportsSyncConditions = false

// Address changes come from the existing DevicePresenceMonitor; desktop does not infer mobile run conditions.
internal actual fun observeSyncPlatformConditions(): Flow<SyncPlatformConditions> = flowOf(SyncPlatformConditions(false))
