package com.folderspan.ui.state.main

import kotlinx.coroutines.CoroutineScope

internal actual fun createSyncSystemScheduler(state: SyncState, scope: CoroutineScope): SyncSystemScheduler = NoSyncSystemScheduler
