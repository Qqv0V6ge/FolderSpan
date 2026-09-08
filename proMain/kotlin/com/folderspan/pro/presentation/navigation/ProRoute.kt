package com.folderspan.pro.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ProRoute : NavKey

@Serializable
@SerialName("login")
data object ProLoginRoute : ProRoute

@Serializable
@SerialName("registration")
data object ProRegistrationRoute : ProRoute

@Serializable
@SerialName("recovery")
data object ProRecoveryRoute : ProRoute

@Serializable
@SerialName("marketplace")
data object ProMarketplaceRoute : ProRoute

@Serializable
@SerialName("user_profile")
data object ProUserProfileRoute : ProRoute

@Serializable
@SerialName("login_devices")
data object ProLoginDevicesRoute : ProRoute

@Serializable
@SerialName("edit_profile")
data object ProEditProfileRoute : ProRoute

@Serializable
@SerialName("change_password")
data object ProChangePasswordRoute : ProRoute

@Serializable
@SerialName("personal_settings")
data object ProPersonalSettingsRoute : ProRoute

@Serializable
@SerialName("manual_data_sync")
data object ProManualDataSyncRoute : ProRoute

@Serializable
@SerialName("feedback")
data object ProFeedbackRoute : ProRoute

@Serializable
@SerialName("feedback_tickets")
data class ProFeedbackTicketsRoute(val initialTicketUuid: String? = null) : ProRoute

@Serializable
@SerialName("pending_login")
data class ProPendingLoginRoute(val destination: ProRoute) : ProRoute
