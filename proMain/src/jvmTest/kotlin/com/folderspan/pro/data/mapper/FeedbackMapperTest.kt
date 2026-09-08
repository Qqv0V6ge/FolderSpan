package com.folderspan.pro.data.mapper

import strings.AppStrings

import com.folderspan.pro.test.ChineseLocalizationTest
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.defaultJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FeedbackMapperTest : ChineseLocalizationTest() {
    @Test
    fun listMapperAcceptsNumericDueAtAndPreservesUnknownValues() {
        val payload = defaultJson.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "total": 1,
                "list": [{
                  "id": 1,
                  "uuid": "ticket-1",
                  "type": "future-type",
                  "category": "future-category",
                  "content": "content",
                  "contact": "contact",
                  "status": "future-status",
                  "statusNote": "note",
                  "appVersion": "1.0",
                  "platform": "linux",
                  "createdAt": "created",
                  "updatedAt": "updated",
                  "priority": "future-priority",
                  "dueAt": 1764144000000,
                  "unreadCount": 2,
                  "version": 3
                }]
              }
            }
            """.trimIndent(),
        )

        val page = assertIs<ApiResult.Success<*>>(payload.toFeedbackPageResult()).data as com.folderspan.pro.domain.model.FeedbackPage
        val ticket = page.items.single()
        assertEquals("1764144000000", ticket.dueAt)
        assertEquals("future-type", ticket.type)
        assertEquals("future-status", ticket.status)
        assertEquals("future-priority", ticket.priority)
    }

    @Test
    fun missingTicketIdentityReturnsControlledFailure() {
        val payload = defaultJson.parseToJsonElement(
            """{"code":0,"data":{"total":1,"list":[{"id":1}]}}""",
        )

        assertIs<ApiResult.Failure>(payload.toFeedbackPageResult())
    }

    @Test
    fun nullDueAtRemainsNullAndCategoriesIgnoreBlankKeys() {
        val listPayload = defaultJson.parseToJsonElement(
            """{"data":{"total":1,"list":[{"id":1,"uuid":"ticket","dueAt":null}]}}""",
        )
        val page = assertIs<ApiResult.Success<*>>(listPayload.toFeedbackPageResult()).data as com.folderspan.pro.domain.model.FeedbackPage
        assertNull(page.items.single().dueAt)

        val categoryPayload = defaultJson.parseToJsonElement(
            AppStrings.ui_test_feedback_mapper_chinese_category_json,
        )
        val categories = assertIs<ApiResult.Success<*>>(categoryPayload.toFeedbackCategoriesResult()).data as List<*>
        assertEquals("bug", (categories.single() as com.folderspan.pro.domain.model.FeedbackCategory).key)
    }
}
