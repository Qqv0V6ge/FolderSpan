package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CryptoKeyDialog(
    initText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable(initText) { mutableStateOf(initText) }

    fun validate(input: String): Pair<Boolean, String> {
        return when {
            input.isBlank() -> Pair(true, AppStrings.ui_key_cannot_empty)
            input.length < 8 -> Pair(true, AppStrings.ui_key_length_must_least_8_bits)
            else -> Pair(false, "")
        }
    }

    fun generateRandomKey(length: Int = 32): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { charset.random() }
            .joinToString("")
    }

    val (isError, errorMessage) = validate(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.ui_modify_encryption_key) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newText -> text = newText },
                    label = { Text(AppStrings.ui_key) },
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        text = generateRandomKey(32)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(AppStrings.ui_generate_random_keys)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isError) {
                        onConfirm(text)
                    }
                },
                enabled = !isError && text.isNotBlank()
            ) {
                Text(AppStrings.ui_ok)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppStrings.ui_cancel)
            }
        }
    )
}
