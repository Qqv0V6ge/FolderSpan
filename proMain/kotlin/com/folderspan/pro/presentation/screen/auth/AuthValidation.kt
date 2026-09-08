package com.folderspan.pro.presentation.screen.auth

import strings.AppStrings

fun validateLogin(email: String, password: String, agreed: Boolean): String? =
    when {
        email.isBlank() -> AppStrings.ui_please_enter_your_email_address
        "@" !in email -> AppStrings.ui_please_enter_valid_email_address
        password.length < 6 -> AppStrings.ui_password_requires_least_6_characters
        !agreed -> AppStrings.ui_please_agree_terms_service_first
        else -> null
    }

fun validateRegistration(
    email: String,
    password: String,
    confirmPassword: String,
    agreed: Boolean,
): String? =
    when {
        email.isBlank() -> AppStrings.ui_please_enter_your_email_address
        "@" !in email -> AppStrings.ui_please_enter_valid_email_address
        password.length < 6 -> AppStrings.ui_password_requires_least_6_characters
        password != confirmPassword -> AppStrings.validation_passwords_do_not_match
        !agreed -> AppStrings.ui_please_agree_terms_service_first
        else -> null
    }

fun validateEmail(email: String): String? =
    when {
        email.isBlank() -> AppStrings.ui_please_enter_your_email_address
        "@" !in email -> AppStrings.ui_please_enter_valid_email_address
        else -> null
    }

data class RecoveryFieldErrors(
    val email: String? = null,
    val verificationCode: String? = null,
    val newPassword: String? = null,
    val confirmPassword: String? = null,
) {
    fun hasError(): Boolean =
        email != null || verificationCode != null || newPassword != null || confirmPassword != null
}

fun validateRecoveryFields(
    email: String,
    verificationCode: String,
    newPassword: String,
    confirmPassword: String,
): RecoveryFieldErrors =
    RecoveryFieldErrors(
        email = validateEmail(email),
        verificationCode = if (verificationCode.isBlank()) AppStrings.ui_please_enter_verification_code else null,
        newPassword = if (newPassword.length < 6) AppStrings.ui_new_password_needs_least_6_characters_long else null,
        confirmPassword = when {
            confirmPassword.isBlank() -> AppStrings.ui_please_enter_your_new_password_again
            newPassword != confirmPassword -> AppStrings.validation_passwords_do_not_match
            else -> null
        },
    )
