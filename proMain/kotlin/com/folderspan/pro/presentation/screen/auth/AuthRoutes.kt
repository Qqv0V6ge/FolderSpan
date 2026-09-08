package com.folderspan.pro.presentation.screen.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folderspan.pro.domain.repository.AuthRepository

@Composable
fun LoginRoute(
    viewModelKey: String? = null,
    repository: AuthRepository,
    prompt: String? = null,
    onAuthorized: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToRegistration: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onSessionAuthorized: suspend (String) -> Unit = {},
) {
    val viewModel = viewModel(key = viewModelKey) { LoginViewModel(repository, onSessionAuthorized) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                AuthEffect.Authorized -> onAuthorized()
            }
        }
    }

    LoginPage(
        state = state,
        prompt = prompt,
        onEvent = viewModel::onEvent,
        onNavigateBack = onNavigateBack,
        onNavigateToRegistration = onNavigateToRegistration,
        onNavigateToRecovery = onNavigateToRecovery,
    )
}

@Composable
fun RegistrationRoute(
    viewModelKey: String? = null,
    repository: AuthRepository,
    onAuthorized: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSessionAuthorized: suspend (String) -> Unit = {},
) {
    val viewModel = viewModel(key = viewModelKey) { RegistrationViewModel(repository, onSessionAuthorized) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                AuthEffect.Authorized -> onAuthorized()
            }
        }
    }

    RegistrationPage(
        state = state,
        onEvent = viewModel::onEvent,
        onNavigateBack = onNavigateBack,
        onNavigateToLogin = onNavigateToLogin,
    )
}

@Composable
fun RecoveryRoute(
    viewModelKey: String? = null,
    repository: AuthRepository,
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val viewModel = viewModel(key = viewModelKey) { RecoveryViewModel(repository) }
    val state by viewModel.state.collectAsState()

    RecoveryPage(
        state = state,
        onEvent = viewModel::onEvent,
        onNavigateBack = onNavigateBack,
        onNavigateToLogin = onNavigateToLogin,
    )
}
