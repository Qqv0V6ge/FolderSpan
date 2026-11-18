package com.folderspan.ui.screen.webrtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folderspan.data.main.webrtc.WebRtcOfficialGateway
import com.folderspan.data.main.webrtc.WebRtcOfficialRooms
import com.folderspan.data.main.webrtc.WebRtcRoomMembership
import com.folderspan.data.main.webrtc.WebRtcRoomMembershipAction
import com.folderspan.data.main.webrtc.WebRtcRoomMembershipState
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.data.main.webrtc.WebRtcShareCode
import com.folderspan.data.main.webrtc.WebRtcShareCodeException
import kotlinx.coroutines.launch
import strings.AppStrings

@Composable
internal fun OfficialRoomImportDialog(onUpdated: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = WebRtcOfficialRooms.client
    var code by remember { mutableStateOf("") }
    var roomId by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }
    var membership by remember { mutableStateOf<WebRtcRoomMembership?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    fun refresh(id: String) {
        busy = true
        feedback = ""
        scope.launch {
            try {
                client.getMembership(id).fold(
                    onSuccess = {
                        membership = it
                        if (it?.state == WebRtcRoomMembershipState.Approved) onUpdated()
                    },
                    onFailure = { feedback = AppStrings.official_room_unavailable },
                )
            } finally {
                busy = false
            }
        }
    }

    val mayApply = membership?.state != WebRtcRoomMembershipState.Pending &&
        membership?.state != WebRtcRoomMembershipState.Approved
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.official_room_import) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (roomId.isEmpty()) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(8193); feedback = "" },
                        label = { Text(AppStrings.official_room_code) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                } else {
                    Text(roomName)
                    membership?.let { Text(it.state.label) }
                    TextButton(enabled = !busy, onClick = { refresh(roomId) }) { Text(AppStrings.official_room_check_status) }
                }
                if (feedback.isNotEmpty()) Text(feedback)
                if (busy) Text(AppStrings.ui_loading)
            }
        },
        confirmButton = {
            if (roomId.isEmpty()) {
                TextButton(enabled = code.isNotBlank(), onClick = {
                    val imported = try {
                        WebRtcShareCode.decodeOfficialRoom(code, WebRtcOfficialGateway.websocketUrl())
                    } catch (_: WebRtcShareCodeException) {
                        feedback = AppStrings.official_room_code_invalid
                        return@TextButton
                    }
                    roomId = imported.roomId
                    roomName = imported.name
                    code = ""
                    refresh(imported.roomId)
                }) { Text(AppStrings.ui_confirm) }
            } else if (mayApply) {
                TextButton(enabled = !busy && feedback.isEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            client.changeMembership(
                                roomId, membership?.requestVersion ?: 0, WebRtcRoomMembershipAction.Apply,
                            ).fold(
                                onSuccess = {
                                    membership = it
                                    if (it.state == WebRtcRoomMembershipState.Approved) onUpdated()
                                },
                                onFailure = { feedback = AppStrings.official_room_unavailable },
                            )
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(AppStrings.official_room_apply) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppStrings.ui_close) } },
    )
}

@Composable
internal fun OfficialRoomMembersDialog(room: WebRtcRoomProfile, onDismiss: () -> Unit) {
    val client = WebRtcOfficialRooms.client
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(WebRtcRoomMembershipState.Pending) }
    var page by remember { mutableIntStateOf(1) }
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<WebRtcRoomMembership>()) }
    var loading by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var decisionError by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<WebRtcRoomMembership?>(null) }

    LaunchedEffect(room.roomId, filter, page, reload) {
        loading = true
        try {
            val result = client.listMemberships(room.roomId, filter, page)
            rows = result.getOrDefault(emptyList())
            loadError = result.isFailure
        } finally {
            loading = false
        }
    }

    fun change(member: WebRtcRoomMembership, action: WebRtcRoomMembershipAction) {
        changing = true
        scope.launch {
            try {
                decisionError = client.changeMembership(room.roomId, member.requestVersion, action, member.applicantUserUuid).isFailure
                reload++
            } finally {
                changing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.official_room_members) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (state in listOf(WebRtcRoomMembershipState.Pending, WebRtcRoomMembershipState.Approved)) {
                        FilterChip(selected = filter == state, enabled = !changing, onClick = {
                            if (filter != state) {
                                rows = emptyList()
                                filter = state
                                page = 1
                                decisionError = false
                            }
                        }, label = { Text(state.label) })
                    }
                }
                if (loading) Text(AppStrings.ui_loading)
                if (loadError || decisionError) Text(AppStrings.official_room_unavailable)
                if (!loading && !loadError && rows.isEmpty()) Text(AppStrings.official_room_no_members)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(rows, key = { it.applicantUserUuid }) { member ->
                        Column {
                            Text(member.applicantUserUuid)
                            val enabled = !loading && !changing
                            Row {
                                if (member.state == WebRtcRoomMembershipState.Pending) {
                                    TextButton(enabled = enabled, onClick = { change(member, WebRtcRoomMembershipAction.Approve) }) {
                                        Text(AppStrings.official_room_approve)
                                    }
                                    TextButton(enabled = enabled, onClick = { change(member, WebRtcRoomMembershipAction.Reject) }) {
                                        Text(AppStrings.official_room_reject)
                                    }
                                } else {
                                    TextButton(enabled = enabled, onClick = { remove = member }) { Text(AppStrings.official_room_remove) }
                                }
                            }
                        }
                    }
                }
                Row {
                    TextButton(enabled = page > 1 && !loading && !changing, onClick = { page-- }) { Text(AppStrings.official_room_previous) }
                    TextButton(enabled = rows.size >= 20 && !loading && !changing, onClick = { page++ }) { Text(AppStrings.official_room_next) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(AppStrings.ui_close) } },
    )
    remove?.let { member ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(AppStrings.official_room_remove) },
            text = { Text(AppStrings.official_room_remove_warning) },
            confirmButton = {
                TextButton(onClick = {
                    remove = null
                    change(member, WebRtcRoomMembershipAction.Remove)
                }) { Text(AppStrings.official_room_remove) }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(AppStrings.ui_cancel) } },
        )
    }
}

private val WebRtcRoomMembershipState.label: String
    get() = when (this) {
        WebRtcRoomMembershipState.Pending -> AppStrings.official_room_pending
        WebRtcRoomMembershipState.Approved -> AppStrings.official_room_approved
        WebRtcRoomMembershipState.Rejected -> AppStrings.official_room_rejected
        WebRtcRoomMembershipState.Removed -> AppStrings.official_room_removed
        WebRtcRoomMembershipState.Left -> AppStrings.official_room_left
    }
