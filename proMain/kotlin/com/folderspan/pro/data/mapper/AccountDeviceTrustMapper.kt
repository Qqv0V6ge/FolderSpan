package com.folderspan.pro.data.mapper

import com.folderspan.pro.core.common.apiDataOrSelf
import com.folderspan.service.account.AccountDeviceTrustedIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun JsonElement.toAccountDeviceTrustedIdentities(): List<AccountDeviceTrustedIdentity> {
    val data = apiDataOrSelf() as? JsonObject ?: return emptyList()
    return (data["devices"] as? JsonArray)
        ?.mapNotNull(JsonElement::toAccountDeviceTrustedIdentity)
        .orEmpty()
}

private fun JsonElement.toAccountDeviceTrustedIdentity(): AccountDeviceTrustedIdentity? {
    val obj = this as? JsonObject ?: return null
    val deviceKey = obj.stringValue("device_key") ?: return null
    val publicKey = obj.stringValue("identity_public_key") ?: return null
    val algorithm = obj.stringValue("identity_algorithm") ?: return null
    val keyId = obj.stringValue("identity_key_id") ?: return null
    return AccountDeviceTrustedIdentity(
        deviceKey = deviceKey,
        name = obj.stringValue("device_name").orEmpty(),
        type = obj.stringValue("device_type").orEmpty(),
        identityPublicKey = publicKey,
        identityAlgorithm = algorithm,
        identityKeyId = keyId,
    ).normalized().takeIf(AccountDeviceTrustedIdentity::isVerifiable)
}
