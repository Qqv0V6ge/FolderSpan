package com.folderspan.pro.presentation.screen.auth

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthValidationTest {
    @Test
    fun registrationDoesNotRequireNickname() {
        val result = validateRegistration(
            email = "user@example.com",
            password = "password",
            confirmPassword = "password",
            agreed = true,
        )

        assertNull(result)
    }

    @Test
    fun registrationStillRequiresEmail() {
        val result = validateRegistration(
            email = "",
            password = "password",
            confirmPassword = "password",
            agreed = true,
        )

        assertNotNull(result)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelSessionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun initialStateShowsRememberedEmailAfterSessionIsCleared() {
        val store = InMemoryAuthSessionStore()
        store.save(
            com.folderspan.pro.core.datastore.AuthSession(
                accessToken = "token-value",
                userEmail = "remembered@example.com",
            ),
        )
        store.clear()
        SessionManager.initialize(store)

        val viewModel = LoginViewModel(
            repository = FakeAuthRepository(
                loginResult = ApiResult.Failure("not used"),
            ),
        )

        assertEquals("remembered@example.com", viewModel.state.value.email)
    }

    @Test
    fun loginSuccessStoresInputEmailWhenResponseDoesNotIncludeEmail() = runTest {
        SessionManager.initialize(InMemoryAuthSessionStore())
        val viewModel = LoginViewModel(
            repository = FakeAuthRepository(
                loginResult = ApiResult.Success(
                    Json.parseToJsonElement(
                        """
                        {
                          "data": {
                            "accessToken": "token-value",
                            "refreshToken": "refresh-value"
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        viewModel.onEvent(LoginEvent.EmailChanged(" user@example.com "))
        viewModel.onEvent(LoginEvent.PasswordChanged("password-value"))
        viewModel.onEvent(LoginEvent.AgreementChanged(true))
        viewModel.onEvent(LoginEvent.Submit)
        advanceUntilIdle()

        assertEquals("user@example.com", SessionManager.currentSession()?.userEmail)
        assertEquals("token-value", SessionManager.currentSession()?.loginData?.get("accessToken")?.jsonPrimitive?.content)
    }

    private class FakeAuthRepository(
        private val loginResult: JsonResult,
    ) : AuthRepository {
        override suspend fun login(email: String, password: String): JsonResult = loginResult

        override suspend fun register(name: String, email: String, password: String): JsonResult =
            error("register is not used in this test")

        override suspend fun requestPasswordReset(email: String): JsonResult =
            error("requestPasswordReset is not used in this test")

        override suspend fun resetPassword(email: String, code: String, newPassword: String): JsonResult =
            error("resetPassword is not used in this test")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
