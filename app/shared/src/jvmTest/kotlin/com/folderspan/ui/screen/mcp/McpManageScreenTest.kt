package com.folderspan.ui.screen.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.folderspan.service.mcp.auth.McpTokenRecord
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.http.McpAdvertisedEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class McpManageScreenTest {
    @Test
    fun responsiveLayoutUsesOnlySingleOrTwoPaneModes() {
        assertEquals(1, mcpManageColumnCount(480.dp))
        assertEquals(1, mcpManageColumnCount(839.dp))
        assertEquals(2, mcpManageColumnCount(840.dp))
        assertEquals(2, mcpManageColumnCount(900.dp))
        assertEquals(2, mcpManageColumnCount(2000.dp))
    }

    @Test
    fun tokenGridColumnCountFollowsAvailableTokenPaneWidth() {
        assertEquals(1, mcpTokenGridColumnCount(320.dp))
        assertEquals(1, mcpTokenGridColumnCount(651.dp))
        assertEquals(2, mcpTokenGridColumnCount(652.dp))
        assertEquals(3, mcpTokenGridColumnCount(984.dp))
        assertEquals(4, mcpTokenGridColumnCount(1600.dp))
    }

    @Test
    fun permissionEditorUsesOneColumnOnPhonesAndTwoInWideDialogs() {
        assertEquals(1, mcpPermissionEditorColumnCount(480.dp))
        assertEquals(2, mcpPermissionEditorColumnCount(560.dp))
        assertEquals(2, mcpPermissionEditorColumnCount(712.dp))
    }

    @Test
    fun tokenPresetsIncreasePrivilegesWithoutDroppingReadScopes() {
        val readOnly = mcpPresetScopes(McpTokenPreset.ReadOnly)
        val operator = mcpPresetScopes(McpTokenPreset.Operator)
        val administrator = mcpPresetScopes(McpTokenPreset.Administrator)

        assertTrue(readOnly.all { it.value.endsWith(".read") })
        assertTrue(operator.containsAll(readOnly))
        assertTrue(McpTokenScope.FilesWrite in operator)
        assertEquals(McpTokenScope.entries.toSet(), administrator)
    }

    @Test
    fun selectingFavoritesWriteImpliesFilesRead() {
        assertEquals(
            setOf(McpTokenScope.FavoritesWrite, McpTokenScope.FilesRead),
            withImpliedMcpScopes(setOf(McpTokenScope.FavoritesWrite)),
        )
        assertEquals(
            setOf(McpTokenScope.FilesRead),
            withImpliedMcpScopes(setOf(McpTokenScope.FilesRead)),
        )
        assertEquals(
            setOf(McpTokenScope.FavoritesWrite, McpTokenScope.FilesRead, McpTokenScope.FavoritesRead),
            withImpliedMcpScopes(setOf(McpTokenScope.FavoritesWrite, McpTokenScope.FavoritesRead)),
        )
    }

    @Test
    fun selectingFilesShareImpliesFilesRead() {
        assertEquals(
            setOf(McpTokenScope.FilesShare, McpTokenScope.FilesRead),
            withImpliedMcpScopes(setOf(McpTokenScope.FilesShare)),
        )
        assertEquals(
            setOf(McpTokenScope.FilesShare, McpTokenScope.FilesRead, McpTokenScope.FavoritesWrite),
            withImpliedMcpScopes(setOf(McpTokenScope.FilesShare, McpTokenScope.FavoritesWrite)),
        )
    }

    @Test
    fun permissionSummaryRecognizesLevelsAndGroupsScopesByUserFacingArea() {
        val readOnly = mcpPresetScopes(McpTokenPreset.ReadOnly)
        val operator = mcpPresetScopes(McpTokenPreset.Operator)
        val custom = setOf(McpTokenScope.FilesRead, McpTokenScope.FilesShare)

        assertEquals(McpPermissionLevel.ReadOnly, mcpPermissionLevel(readOnly))
        assertEquals(McpPermissionLevel.Operator, mcpPermissionLevel(operator))
        assertEquals(McpPermissionLevel.Custom, mcpPermissionLevel(custom))
        assertEquals(
            listOf(
                McpPermissionGroup(
                    area = McpPermissionArea.Files,
                    scopes = listOf(McpTokenScope.FilesRead, McpTokenScope.FilesShare),
                )
            ),
            mcpPermissionGroups(custom),
        )
    }

    @Test
    fun endpointPickerShowsEachAddressOnceAndCanSwitchHttpHttps() {
        val http = endpoint(scheme = "http", host = "192.168.1.25")
        val https = endpoint(scheme = "https", host = "192.168.1.25")
        val other = endpoint(scheme = "https", host = "10.0.0.8")
        val endpoints = listOf(http, other, https)

        assertEquals(listOf(other, https), preferredMcpEndpoints(endpoints))
        assertEquals(listOf(other), filterMcpEndpoints(endpoints, "10.0.0.8"))
        assertEquals(listOf(https), filterMcpEndpoints(endpoints, "192.168"))
        assertEquals(emptyList(), filterMcpEndpoints(endpoints, "https"))
        assertEquals(emptyList(), filterMcpEndpoints(endpoints, "8042"))
        assertEquals("http", resolveMcpEndpoint(endpoints, https.selectionKey(), "http")?.scheme)
        assertEquals("https", resolveMcpEndpoint(endpoints, https.selectionKey(), "https")?.scheme)
        assertEquals(setOf("http", "https"), mcpSchemesForAddress(endpoints, https.selectionKey()))
        assertEquals(listOf(http), preferredMcpEndpoints(listOf(http)))
    }

    @Test
    fun narrowLayoutStacksPrimarySectionsBeforeTokens() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                TestMcpLayout(width = 480)
            }
        }

        val service = onNodeWithTag("mcp-test-service").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val endpoints = onNodeWithTag("mcp-test-endpoints").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tokens = onNodeWithTag("mcp-test-tokens").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertEquals(service.left, endpoints.left)
        assertEquals(service.left, tokens.left)
        assertTrue(endpoints.top > service.top)
        assertTrue(tokens.top > endpoints.top)
    }

    @Test
    fun wideLayoutKeepsServiceAndEndpointsLeftAndTokensRight() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TestMcpLayout(width = 900)
            }
        }

        val service = onNodeWithTag("mcp-test-service").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val endpoints = onNodeWithTag("mcp-test-endpoints").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tokens = onNodeWithTag("mcp-test-tokens").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertEquals(service.left, endpoints.left)
        assertTrue(endpoints.top > service.top)
        assertEquals(service.top, tokens.top)
        assertTrue(tokens.left > service.left)
    }

    @Test
    fun extraWideLayoutKeepsFixedLeftPaneAndGivesRemainingWidthToTokens() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                TestMcpLayout(width = 1600)
            }
        }

        val service = onNodeWithTag("mcp-test-service").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val endpoints = onNodeWithTag("mcp-test-endpoints").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tokens = onNodeWithTag("mcp-test-tokens").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertEquals(service.left, endpoints.left)
        assertTrue(endpoints.top > service.top)
        assertEquals(service.top, tokens.top)
        assertTrue(tokens.left > service.left)
        assertTrue(tokens.width > service.width)
    }

    @Test
    fun responsiveLayoutUsesSuppliedDynamicLikeColorScheme() = runComposeUiTest {
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xff6750a4),
                    surface = Color(0xfffffbfe),
                )
            ) {
                TestMcpLayout(width = 900)
            }
        }

        onNodeWithTag("mcp_manage_layout").assertIsDisplayed()
        onNodeWithTag("mcp-test-service").assertIsDisplayed()
        onNodeWithTag("mcp-test-tokens").assertIsDisplayed()
    }

    @Test
    fun tokenDetailsAreCollapsedByDefaultAndCanBeExpandedIndependently() = runComposeUiTest {
        val token = McpTokenRecord(
            lookupId = "token-1",
            name = "Test token",
            enabled = true,
            createdAt = 0L,
            updatedAt = 0L,
            lastUsedAt = null,
            scopes = mcpPresetScopes(McpTokenPreset.Operator),
        )
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column {
                    McpTokenItem(
                        token = token,
                        onEdit = {},
                        onEnabledChange = {},
                        onRotate = {},
                        onDelete = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    McpTokenItem(
                        token = token.copy(lookupId = "token-2", name = "Another token"),
                        onEdit = {},
                        onEnabledChange = {},
                        onRotate = {},
                        onDelete = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        onAllNodesWithTag("mcp_token_details_token-1").assertCountEquals(0)
        onAllNodesWithTag("mcp_token_details_token-2").assertCountEquals(0)
        onNodeWithTag("mcp_token_details_toggle_token-1").performClick()
        onNodeWithTag("mcp_token_details_token-1").assertIsDisplayed()
        onAllNodesWithTag("mcp_token_details_token-2").assertCountEquals(0)
        onAllNodesWithText("token-1").assertCountEquals(0)

        onNodeWithTag("mcp_token_details_toggle_token-1").performClick()
        waitForIdle()
        onAllNodesWithTag("mcp_token_details_token-1").assertCountEquals(0)
    }

    @Test
    fun oneTimeSecretCopiesTokenAndAuthorizationHeadersFromTheirRegionsAndAction() = runComposeUiTest {
        val token = "fmcp_0011223344556677_8899aabbccddeeff"
        val copiedValues = mutableListOf<String>()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                McpOneTimeSecretDialog(
                    secret = token,
                    onCopy = copiedValues::add,
                    onClose = {},
                )
            }
        }

        onNodeWithTag("mcp_secret_token_copy_region").performClick()
        assertEquals(token, copiedValues.last())

        onNodeWithTag("mcp_secret_headers_copy_region").performClick()
        assertEquals(
            "Authorization: Bearer $token\nContent-Type: application/json",
            copiedValues.last(),
        )

        onNodeWithTag("mcp_secret_copy_action").performClick()
        assertEquals(token, copiedValues.last())
    }
}

private fun endpoint(scheme: String, host: String): McpAdvertisedEndpoint = McpAdvertisedEndpoint(
    scheme = scheme,
    host = host,
    port = 8042,
    statefulUrl = "$scheme://$host:8042/mcp",
    statelessUrl = "$scheme://$host:8042/mcp/stateless",
)

@Composable
private fun TestMcpLayout(width: Int) {
    McpManageResponsiveLayout(
        modifier = Modifier.requiredSize(width.dp, 560.dp),
        serviceSection = {
            TestSectionCard("mcp-test-service")
        },
        endpointSection = {
            TestSectionCard("mcp-test-endpoints")
        },
        tokenSection = {
            TestSectionCard("mcp-test-tokens")
        },
    )
}

@Composable
private fun TestSectionCard(tag: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .testTag(tag),
    ) {}
}
