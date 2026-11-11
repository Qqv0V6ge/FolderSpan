package com.folderspan.ui.state.file

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

/** Reservations cover pending output roots across all copy windows in one runtime. */
internal class CopyTargetReservations(private val isActive: (String) -> Boolean) {
    private val mutex = Mutex()
    private val reservations = mutableListOf<Pair<String, CopyFileSnapshot>>()

    suspend fun claim(requestId: String, directories: List<CopyFileSnapshot>) = mutex.withLock {
        reservations.removeAll { !isActive(it.first) }
        val roots = directories.map { snapshot ->
            CopyFileSnapshot(snapshot.endpoint.copy(connectionBinding = "copy-reservation"), snapshot.file, snapshot.pathKey)
        }
        require(roots.none { root -> reservations.any { (owner, reserved) ->
            owner != requestId && (reserved.contains(root) || root.contains(reserved))
        } }) { AppStrings.ui_target_exists }
        roots.forEach { root ->
            if (reservations.none { it.first == requestId && it.second.sameLocation(root) }) reservations += requestId to root
        }
    }
}
