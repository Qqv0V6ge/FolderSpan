package com.folderspan.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.combinedClickableWithContextClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val pointerModifier = if (onLongClick == null) {
        Modifier
    } else {
        Modifier.pointerInput(onLongClick) {
            while (true) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isSecondaryMousePress =
                            event.type == PointerEventType.Press &&
                                    event.buttons.isSecondaryPressed &&
                                    event.changes.any { change ->
                                        change.type == PointerType.Mouse && change.changedToDownIgnoreConsumed()
                                    }

                        if (isSecondaryMousePress) {
                            event.changes.forEach { change -> change.consume() }
                            onLongClick()
                            while (true) {
                                val nextEvent = awaitPointerEvent()
                                if (!nextEvent.buttons.isSecondaryPressed) {
                                    break
                                }
                            }
                            break
                        }

                        if (event.changes.all { change -> !change.pressed }) {
                            break
                        }
                    }
                }
            }
        }
    }

    val clickableModifier = if (onLongClick == null) {
        Modifier.combinedClickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }

    return this.then(pointerModifier).then(clickableModifier)
}
