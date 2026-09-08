package com.folderspan.pro.presentation.screen.feedback

import strings.AppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.folderspan.pro.domain.model.FeedbackType

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedbackHomePage(
    state: FeedbackFormUiState,
    onNavigateBack: () -> Unit,
    onOpenTickets: () -> Unit,
    onTypeChange: (FeedbackType) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onContentChange: (String) -> Unit,
    onContactChange: (String) -> Unit,
    onRetryCategories: () -> Unit,
    onSubmit: () -> Unit,
    onSubmitAnother: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.ui_feedback_and_suggestions) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppStrings.ui_return)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTickets) {
                        Icon(Icons.Filled.ConfirmationNumber, contentDescription = AppStrings.ui_feedback_my_tickets)
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val horizontalPadding = if (maxWidth >= 720.dp) 32.dp else 16.dp
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = 20.dp,
                    )
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(AppStrings.ui_feedback_intro, style = MaterialTheme.typography.bodyLarge)

                if (state.submittedReference != null) {
                    Column {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(AppStrings.ui_feedback_submitted, style = MaterialTheme.typography.titleMedium)
                            Text(AppStrings.ui_feedback_reference_arg0.format(arg0 = state.submittedReference))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledTonalButton(onClick = onSubmitAnother) { Text(AppStrings.ui_feedback_submit_another) }
                                TextButton(onClick = onOpenTickets) { Text(AppStrings.ui_feedback_my_tickets) }
                            }
                        }
                    }
                } else {
                    Text(AppStrings.ui_feedback_type, style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        FeedbackType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = state.type == type,
                                onClick = { onTypeChange(type) },
                                shape = SegmentedButtonDefaults.itemShape(index, FeedbackType.entries.size),
                            ) {
                                Text(if (type == FeedbackType.Feedback) AppStrings.ui_feedback else AppStrings.ui_suggestion)
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppStrings.ui_feedback_category_optional, style = MaterialTheme.typography.titleSmall)
                        when {
                            state.isLoadingCategories -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.categoryErrorMessage != null -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        AppStrings.ui_feedback_category_load_failed,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = onRetryCategories) { Text(AppStrings.ui_try_again) }
                                }
                            }
                            else -> FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.selectedCategory == null,
                                    onClick = { onCategoryChange(null) },
                                    label = { Text(AppStrings.ui_feedback_filter_all) },
                                )
                                state.categories.forEach { category ->
                                    FilterChip(
                                        selected = state.selectedCategory == category.key,
                                        onClick = { onCategoryChange(category.key) },
                                        label = { Text(category.label.ifBlank { feedbackCategoryLabel(category.key) }) },
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.content,
                        onValueChange = onContentChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        label = { Text(AppStrings.ui_feedback_content) },
                        placeholder = { Text(AppStrings.ui_feedback_content_hint) },
                        supportingText = state.contentErrorMessage?.let { message -> ({ Text(message) }) },
                        isError = state.contentErrorMessage != null,
                        minLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    )
                    OutlinedTextField(
                        value = state.contact,
                        onValueChange = onContactChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(AppStrings.ui_feedback_contact_optional) },
                        placeholder = { Text(AppStrings.ui_feedback_contact_hint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); onSubmit() }),
                    )

                    state.formErrorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(AppStrings.ui_feedback_submit)
                    }
                }
            }
        }
    }
}
