package com.folderspan.service.mcp

import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpServerSettingsTest {
    @Test
    fun defaultsAreDisabledAndUseIndependentPort() {
        SettingsUtils.init(MapSettings())

        val defaults = McpServerSettingsStore().read()
        assertEquals(McpServerSettings(), defaults)
        assertEquals(52137, defaults.port)
        assertFalse(defaults.lanAccess)
    }

    @Test
    fun valuesPersistWithoutEnteringSyncWhitelistAndClearLegacyOrigins() {
        val settings = MapSettings().apply {
            putString(SettingsUtils.KEY_MCP_ALLOWED_ORIGINS, """["https://lan.example"]""")
        }
        SettingsUtils.init(settings)
        val stored = McpServerSettingsStore().write(
            McpServerSettings(
                enabled = true,
                port = 13041,
                lanAccess = true,
            ),
        )

        assertTrue(stored.enabled)
        assertEquals(13041, stored.port)
        assertTrue(stored.lanAccess)
        assertEquals(stored, McpServerSettingsStore().read())
        assertFalse(settings.keys.contains(SettingsUtils.KEY_MCP_ALLOWED_ORIGINS))

        val syncableKeys = SettingsUtils.syncableSettings.map { item -> item.key }.toSet()
        assertFalse(SettingsUtils.KEY_MCP_ENABLED in syncableKeys)
        assertFalse(SettingsUtils.KEY_MCP_PORT in syncableKeys)
        assertFalse(SettingsUtils.KEY_MCP_LAN_ACCESS in syncableKeys)
        assertFalse(SettingsUtils.KEY_MCP_ALLOWED_ORIGINS in syncableKeys)
        assertTrue(settings.keys.none { key -> key.contains("token", ignoreCase = true) || key.contains("secret", ignoreCase = true) })
    }

    @Test
    fun invalidPortIsRejected() {
        SettingsUtils.init(MapSettings())

        assertFailsWith<IllegalArgumentException> {
            McpServerSettingsStore().write(McpServerSettings(port = 0))
        }
    }
}
