package com.folderspan.pro.data.mapper

import com.folderspan.pro.presentation.screen.profile.requestHeaderDeviceKey
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingTargetMappersTest {
    @Test
    fun toSettingTargetsPayloadKeepsDeviceSubType() {
        val payload = Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "records": [
                  {
                    "targetId": "target-id",
                    "name": "Workstation",
                    "deviceKey": "device-key",
                    "type": "device",
                    "subType": "JVM"
                  }
                ],
                "total": 1
              }
            }
            """.trimIndent(),
        ).toSettingTargetsPayload()

        assertEquals("JVM", payload?.targets?.single()?.subType)
    }

    @Test
    fun toSettingTargetsPayloadKeepsSnakeCaseDeviceSubType() {
        val payload = Json.parseToJsonElement(
            """
            {
              "records": [
                {
                  "targetId": "target-id",
                  "name": "Phone",
                  "deviceKey": "device-key",
                  "type": "device",
                  "sub_type": "Android"
                }
              ]
            }
            """.trimIndent(),
        ).toSettingTargetsPayload()

        assertEquals("Android", payload?.targets?.single()?.subType)
    }

    @Test
    fun toSettingTargetsPayloadKeepsTargetIdAvailableForRequestHeaderFallback() {
        val payload = Json.parseToJsonElement(
            """
            {
              "records": [
                {
                  "targetId": "target-id",
                  "name": "Phone",
                  "type": "device",
                  "subType": "Android"
                }
              ]
            }
            """.trimIndent(),
        ).toSettingTargetsPayload()

        val target = payload?.targets?.single()
        assertNull(target?.deviceKey)
        assertEquals("target-id", target?.requestHeaderDeviceKey())
    }
}
