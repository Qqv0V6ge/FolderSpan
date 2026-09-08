package com.folderspan.pro.data.mapper

import com.folderspan.service.account.ACCOUNT_DEVICE_IDENTITY_ALGORITHM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AccountDeviceTrustMapperTest {
    @Test
    fun mapperKeepsOnlyDevicesWithCompleteVerifiableIdentity() {
        val payload = Json.parseToJsonElement(
            """
            {
              "data": {
                "devices": [
                  {
                    "device_key": "trusted",
                    "device_name": "Laptop",
                    "device_type": "Desktop",
                    "identity_public_key": "public-key",
                    "identity_algorithm": "$ACCOUNT_DEVICE_IDENTITY_ALGORITHM",
                    "identity_key_id": "key-1"
                  },
                  {
                    "device_key": "missing-identity",
                    "device_name": "Missing identity"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val identities = payload.toAccountDeviceTrustedIdentities()

        assertEquals(listOf("trusted"), identities.map { it.deviceKey })
        assertEquals("key-1", identities.single().identityKeyId)
    }

    @Test
    fun mapperRejectsFieldsOutsideTheFinalSnakeCaseContract() {
        val payload = Json.parseToJsonElement(
            """
            {
              "devices": [
                {"deviceKey":"camel","identityPublicKey":"pk","identityAlgorithm":"$ACCOUNT_DEVICE_IDENTITY_ALGORITHM","identityKeyId":"kid"},
                {"device_key":"nested","identity":{"public_key":"pk","algorithm":"$ACCOUNT_DEVICE_IDENTITY_ALGORITHM","key_id":"kid"}}
              ]
            }
            """.trimIndent()
        )

        val identities = payload.toAccountDeviceTrustedIdentities()

        assertEquals(emptyList(), identities)
    }
}
