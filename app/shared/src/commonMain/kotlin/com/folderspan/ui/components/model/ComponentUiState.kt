package com.folderspan.ui.components.model

import androidx.compose.runtime.Immutable
import com.folderspan.data.device.DeviceRole
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.ui.components.drawer.ClipboardPathCandidate

@Immutable
data class StringListUiState(
    val items: List<String> = emptyList()
)

@Immutable
data class FileSelectionUiState(
    val files: List<FileSimpleInfo> = emptyList()
)

@Immutable
data class FileListUiState(
    val files: List<FileSimpleInfo> = emptyList()
)

@Immutable
data class FileFilterTypeListUiState(
    val items: List<FileFilterType> = emptyList()
)

@Immutable
data class StringMapUiState(
    val items: Map<String, String> = emptyMap()
) {
    operator fun get(key: String): String? {
        return items[key]
    }
}

@Immutable
data class DeviceConnectOptionUiState(
    val type: DeviceConnectType,
    val label: String,
)

@Immutable
data class DeviceConnectOptionsUiState(
    val options: List<DeviceConnectOptionUiState>
) {
    val labels: List<String>
        get() = options.map { option -> option.label }

    fun labelFor(type: DeviceConnectType): String? {
        return options.firstOrNull { option -> option.type == type }?.label
    }

    fun typeFor(label: String): DeviceConnectType? {
        return options.firstOrNull { option -> option.label == label }?.type
    }
}

fun buildDeviceConnectOptionsUiState(
    options: Map<DeviceConnectType, String>
): DeviceConnectOptionsUiState {
    return DeviceConnectOptionsUiState(
        options = options.map { (type, label) ->
            DeviceConnectOptionUiState(type = type, label = label)
        }
    )
}

@Immutable
data class DeviceRoleOptionUiState(
    val id: Long,
    val name: String,
)

@Immutable
data class DeviceRoleOptionsUiState(
    val roles: List<DeviceRoleOptionUiState>
) {
    val names: List<String>
        get() = roles.map { role -> role.name }

    fun idForName(name: String): Long? {
        return roles.firstOrNull { role -> role.name == name }?.id
    }
}

fun buildDeviceRoleOptionsUiState(
    roles: List<DeviceRole>
): DeviceRoleOptionsUiState {
    return DeviceRoleOptionsUiState(
        roles = roles.map { role ->
            DeviceRoleOptionUiState(
                id = role.id,
                name = role.name,
            )
        }
    )
}

@Immutable
data class ClipboardPathCandidateUiState(
    val index: Int,
    val displayPath: String,
)

@Immutable
data class ClipboardPathDialogUiState(
    val candidates: List<ClipboardPathCandidateUiState>
)

internal fun buildClipboardPathDialogUiState(
    candidates: List<ClipboardPathCandidate>
): ClipboardPathDialogUiState {
    return ClipboardPathDialogUiState(
        candidates = candidates.mapIndexed { index, candidate ->
            ClipboardPathCandidateUiState(
                index = index,
                displayPath = candidate.displayPath,
            )
        }
    )
}
