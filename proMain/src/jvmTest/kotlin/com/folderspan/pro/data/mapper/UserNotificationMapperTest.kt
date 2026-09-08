package com.folderspan.pro.data.mapper

import strings.AppStrings

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.data.remote.dto.UserNotificationActionDto
import com.folderspan.pro.data.remote.dto.UserNotificationDto
import com.folderspan.pro.data.remote.dto.UserNotificationEnvelopeDto
import com.folderspan.pro.data.remote.dto.UserNotificationListDataDto
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.pro.domain.model.UserNotificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UserNotificationMapperTest {
    @Test
    fun mapsUnknownTypeAndNormalizesEpochSeconds() {
        val notification = UserNotificationDto(
            id = " notice-1 ",
            userUuid = " user-1 ",
            title = " Title ",
            content = " Content ",
            locale = " zh-CN ",
            type = "future-event",
            status = "READ",
            readAt = 1_700_000_001L,
            createdAt = 1_700_000_000L,
        ).toDomainOrNull()

        requireNotNull(notification)
        assertEquals("notice-1", notification.id)
        assertEquals(" Content ", notification.content)
        assertEquals("future-event", notification.type)
        assertEquals(UserNotificationStatus.Read, notification.status)
        assertEquals(1_700_000_001_000L, notification.readAtEpochMillis)
        assertEquals(1_700_000_000_000L, notification.createdAtEpochMillis)
    }

    @Test
    fun preservesAnnouncementContentVerbatim() {
        val content = "  Intro \\[literal]\n" +
            "[Docs](https://example.test/a?x=1%202)\n" +
            "[Ticket](route:feedback_tickets?ticketUuid=ticket%2F7)  "

        val notification = UserNotificationDto(
            id = "announcement-copy",
            content = content,
        ).toDomainOrNull()

        assertEquals(content, requireNotNull(notification).content)
    }

    @Test
    fun mapsActionsInOrderAndPreservesServerLabelsVerbatim() {
        val notification = UserNotificationDto(
            id = "notice-actions",
            actions = listOf(
                UserNotificationActionDto(
                    label = AppStrings.ui_test_user_notification_mapper_view_tickets,
                    style = "primary",
                    kind = "route",
                    route = "feedback_tickets",
                    params = mapOf("ticketUuid" to "ticket-1"),
                ),
                UserNotificationActionDto(
                    label = "Documentation",
                    style = "secondary",
                    kind = "url",
                    url = "https://example.test/docs",
                ),
            ),
        ).toDomainOrNull()

        val actions = requireNotNull(notification).actions
        assertEquals(listOf(AppStrings.ui_test_user_notification_mapper_view_tickets, "Documentation"), actions.map { action -> action.label })
        assertEquals(NotificationActionStyle.Primary, actions[0].style)
        assertEquals(NotificationActionKind.Route, actions[0].kind)
        assertEquals("feedback_tickets", actions[0].route)
        assertEquals(mapOf("ticketUuid" to "ticket-1"), actions[0].params)
        assertEquals(NotificationActionKind.Url, actions[1].kind)
        assertEquals("https://example.test/docs", actions[1].url)
    }

    @Test
    fun absentActionsMapToEmptyAndMalformedEntriesAreDropped() {
        val absent = requireNotNull(UserNotificationDto(id = "notice-empty").toDomainOrNull())
        val malformed = requireNotNull(
            UserNotificationDto(
                id = "notice-malformed",
                actions = listOf(
                    UserNotificationActionDto(
                        label = "",
                        style = "primary",
                        kind = "url",
                        url = "https://example.test",
                    ),
                    UserNotificationActionDto(
                        label = "Two targets",
                        style = "secondary",
                        kind = "route",
                        url = "https://example.test",
                        route = "settings",
                    ),
                    UserNotificationActionDto(
                        label = "Unknown style",
                        style = "loud",
                        kind = "route",
                        route = "settings",
                    ),
                    UserNotificationActionDto(
                        label = "Settings",
                        style = "secondary",
                        kind = "route",
                        route = "settings",
                    ),
                ),
            ).toDomainOrNull(),
        )

        assertEquals(emptyList(), absent.actions)
        assertEquals(listOf("Settings"), malformed.actions.map { action -> action.label })
    }

    @Test
    fun keepsEpochMillisecondsAndDropsBlankIdentifiers() {
        val milliseconds = UserNotificationDto(
            id = "notice-2",
            createdAt = 1_700_000_000_123L,
        ).toDomainOrNull()

        assertEquals(1_700_000_000_123L, requireNotNull(milliseconds).createdAtEpochMillis)
        assertNull(UserNotificationDto(id = "   ").toDomainOrNull())
    }

    @Test
    fun pageMappingSkipsInvalidItemsAndPreservesBusinessFailures() {
        val success = UserNotificationEnvelopeDto(
            data = UserNotificationListDataDto(
                list = listOf(
                    UserNotificationDto(id = "valid", createdAt = 10),
                    UserNotificationDto(id = ""),
                ),
                total = 2,
                page = 2,
                pageSize = 30,
            ),
        ).toPageResult()

        val page = assertIs<ApiResult.Success<*>>(success).data as com.folderspan.pro.domain.model.UserNotificationPage
        assertEquals(listOf("valid"), page.items.map { it.id })
        assertEquals(2, page.total)
        assertEquals(2, page.page)

        val failure = UserNotificationEnvelopeDto<UserNotificationListDataDto>(
            code = 41001,
            msg = "not available",
        ).toPageResult()
        assertEquals(41001, assertIs<ApiResult.Failure>(failure).apiCode)
    }
}
