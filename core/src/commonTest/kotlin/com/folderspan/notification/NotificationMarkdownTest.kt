package com.folderspan.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationMarkdownTest {
    @Test
    fun plainContentAndLineBreaksRemainUnchanged() {
        val content = "First line\nSecond line"

        assertEquals(
            listOf(NotificationBlock.Paragraph(listOf(NotificationInlineSegment.PlainText(content)))),
            parseNotificationContent(content),
        )
        assertEquals(content, notificationPlainTextPreview(content))
    }

    @Test
    fun parsesOneMultipleAndAdjacentLinksInSourceOrder() {
        val content = "See [Docs](https://example.test/docs), " +
            "[Settings](route:settings)[Profile](route:user_profile)."

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.PlainText("See "),
                        NotificationInlineSegment.InlineLink("Docs", "https://example.test/docs"),
                        NotificationInlineSegment.PlainText(", "),
                        NotificationInlineSegment.InlineLink("Settings", "route:settings"),
                        NotificationInlineSegment.InlineLink("Profile", "route:user_profile"),
                        NotificationInlineSegment.PlainText("."),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals("See Docs, SettingsProfile.", notificationPlainTextPreview(content))
    }

    @Test
    fun escapedDelimitersDoNotTerminateValidComponents() {
        val content = "[Read \\] this](https://example.test/a\\)b) and \\[literal](ignored)"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.InlineLink(
                            label = "Read ] this",
                            target = "https://example.test/a)b",
                        ),
                        NotificationInlineSegment.PlainText(" and \\[literal](ignored)"),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals("Read ] this and \\[literal](ignored)", notificationPlainTextPreview(content))
    }

    @Test
    fun emptyMalformedNestedAndUnclosedSyntaxRemainLiteral() {
        val inputs = listOf(
            "[](https://example.test)",
            "[Label]()",
            "[Label]not-a-target",
            "[Label](nested(value))",
            "[Unclosed](https://example.test",
            "[Unclosed label",
        )

        inputs.forEach { content ->
            assertEquals(
                listOf(NotificationBlock.Paragraph(listOf(NotificationInlineSegment.PlainText(content)))),
                parseNotificationContent(content),
                content,
            )
            assertEquals(content, notificationPlainTextPreview(content), content)
        }
    }

    @Test
    fun malformedPrefixDoesNotHideFollowingValidLink() {
        val content = "[broken [Docs](https://example.test)"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.PlainText("[broken "),
                        NotificationInlineSegment.InlineLink("Docs", "https://example.test"),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
    }

    @Test
    fun parsesBoldItalicStrikethroughAndInlineCode() {
        val content = "**bold** and *italic* and ~~gone~~ and `code`."

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.Bold(listOf(NotificationInlineSegment.PlainText("bold"))),
                        NotificationInlineSegment.PlainText(" and "),
                        NotificationInlineSegment.Italic(listOf(NotificationInlineSegment.PlainText("italic"))),
                        NotificationInlineSegment.PlainText(" and "),
                        NotificationInlineSegment.Strikethrough(
                            listOf(NotificationInlineSegment.PlainText("gone")),
                        ),
                        NotificationInlineSegment.PlainText(" and "),
                        NotificationInlineSegment.Code("code"),
                        NotificationInlineSegment.PlainText("."),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals("bold and italic and gone and code.", notificationPlainTextPreview(content))
    }

    @Test
    fun emphasisCanNestOtherInlineSegments() {
        val content = "**a *b* c** and `[not a link](https://example.test)`"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.Bold(
                            listOf(
                                NotificationInlineSegment.PlainText("a "),
                                NotificationInlineSegment.Italic(
                                    listOf(NotificationInlineSegment.PlainText("b")),
                                ),
                                NotificationInlineSegment.PlainText(" c"),
                            ),
                        ),
                        NotificationInlineSegment.PlainText(" and "),
                        NotificationInlineSegment.Code("[not a link](https://example.test)"),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
    }

    @Test
    fun wordInternalDelimitersRemainLiteral() {
        val content = "snake_case and 2*3=6 and path/to_file~backup"

        assertEquals(
            listOf(NotificationBlock.Paragraph(listOf(NotificationInlineSegment.PlainText(content)))),
            parseNotificationContent(content),
        )
    }

    @Test
    fun parsesHeadingsWithOptionalClosingHashes() {
        val content = "# Title\n\n## Section\n\n### Sub ###"

        assertEquals(
            listOf(
                NotificationBlock.Heading(1, listOf(NotificationInlineSegment.PlainText("Title"))),
                NotificationBlock.Heading(2, listOf(NotificationInlineSegment.PlainText("Section"))),
                NotificationBlock.Heading(3, listOf(NotificationInlineSegment.PlainText("Sub"))),
            ),
            parseNotificationContent(content),
        )
    }

    @Test
    fun parsesUnorderedAndOrderedLists() {
        val content = "- one\n- **two**\n* three\n\n3. first\n4. second"

        assertEquals(
            listOf(
                NotificationBlock.UnorderedList(
                    listOf(
                        listOf(NotificationInlineSegment.PlainText("one")),
                        listOf(NotificationInlineSegment.Bold(listOf(NotificationInlineSegment.PlainText("two")))),
                        listOf(NotificationInlineSegment.PlainText("three")),
                    ),
                ),
                NotificationBlock.OrderedList(
                    start = 3,
                    items = listOf(
                        listOf(NotificationInlineSegment.PlainText("first")),
                        listOf(NotificationInlineSegment.PlainText("second")),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
    }

    @Test
    fun parsesCodeBlockQuoteAndHorizontalRule() {
        val content = "```\nval x = 1\nprintln(x)\n```\n\n> quoted line\n\n---"

        assertEquals(
            listOf(
                NotificationBlock.CodeBlock("val x = 1\nprintln(x)"),
                NotificationBlock.Blockquote(listOf(NotificationInlineSegment.PlainText("quoted line"))),
                NotificationBlock.HorizontalRule,
            ),
            parseNotificationContent(content),
        )
    }

    @Test
    fun unclosedCodeBlockRunsToEndOfContent() {
        assertEquals(
            listOf(NotificationBlock.CodeBlock("line 1\nline 2")),
            parseNotificationContent("```\nline 1\nline 2"),
        )
    }

    @Test
    fun parsesImage() {
        val content = "![logo](https://example.com/logo.png)\n\n" +
            "See ![alt text](https://example.com/image.jpg) and ![no alt](no-alt-url)"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(NotificationInlineSegment.Image("logo", "https://example.com/logo.png")),
                ),
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.PlainText("See "),
                        NotificationInlineSegment.Image("alt text", "https://example.com/image.jpg"),
                        NotificationInlineSegment.PlainText(" and "),
                        NotificationInlineSegment.Image("no alt", "no-alt-url"),
                    ),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals(
            "logo\nSee alt text and no alt",
            notificationPlainTextPreview(content),
        )
    }

    @Test
    fun imageWithEscapedCharacters() {
        val content = "![Read \\] this](https://example.test/a\\)b)"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(NotificationInlineSegment.Image("Read ] this", "https://example.test/a)b")),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals("Read ] this", notificationPlainTextPreview(content))
    }

    @Test
    fun parsesImageTitleFromQuotedDestination() {
        val doubleQuoted = "![logo](https://example.test/logo.png \"Release notes\")"
        val singleQuoted = "![logo](https://example.test/logo.png 'Release notes')"

        val expected = listOf(
            NotificationBlock.Paragraph(
                listOf(
                    NotificationInlineSegment.Image(
                        alt = "logo",
                        target = "https://example.test/logo.png",
                        title = "Release notes",
                    ),
                ),
            ),
        )
        assertEquals(expected, parseNotificationContent(doubleQuoted))
        assertEquals(expected, parseNotificationContent(singleQuoted))
        assertEquals("logo", notificationPlainTextPreview(doubleQuoted))
        assertEquals(
            MarkdownDestination("https://example.test/logo.png", "Release notes"),
            parseMarkdownDestination("https://example.test/logo.png \"Release notes\""),
        )
        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(NotificationInlineSegment.InlineLink("Docs", "https://example.test/docs")),
                ),
            ),
            parseNotificationContent("[Docs](https://example.test/docs \"Ignored\")"),
        )
    }

    @Test
    fun emptyAltImageIsParsed() {
        val content = "![](https://example.test/a.png)"

        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(NotificationInlineSegment.Image("", "https://example.test/a.png")),
                ),
            ),
            parseNotificationContent(content),
        )
        assertEquals("", notificationPlainTextPreview(content))
    }

    @Test
    fun malformedImagesRemainLiteralAndDoNotHideFollowingLinks() {
        val inputs = listOf(
            "![]()",
            "![Label]()",
            "![Label]not-a-target",
            "![Unclosed](https://example.test",
            "![Unclosed label",
        )

        inputs.forEach { content ->
            assertEquals(
                listOf(NotificationBlock.Paragraph(listOf(NotificationInlineSegment.PlainText(content)))),
                parseNotificationContent(content),
                content,
            )
            assertEquals(content, notificationPlainTextPreview(content), content)
        }

        val mixed = "![broken [Docs](https://example.test)"
        assertEquals(
            listOf(
                NotificationBlock.Paragraph(
                    listOf(
                        NotificationInlineSegment.PlainText("![broken "),
                        NotificationInlineSegment.InlineLink("Docs", "https://example.test"),
                    ),
                ),
            ),
            parseNotificationContent(mixed),
        )
    }

    @Test
    fun parserWorkIsLinearlyBoundedForLongAndAdversarialContent() {
        val inputs = listOf(
            "plain text ".repeat(10_000),
            "[".repeat(100_000),
            "[label](target)".repeat(8_000),
            "[label](unterminated ".repeat(5_000),
            "*".repeat(100_000),
            "**bold** ".repeat(10_000),
            "`code` ".repeat(10_000),
            "- item\n".repeat(10_000),
            "![img](url) ".repeat(10_000),
        )

        inputs.forEach { content ->
            val result = parseNotificationContentWithMetrics(content)
            assertTrue(
                result.inspectedCharacters <= content.length * 6 + 10,
                "Inspected ${result.inspectedCharacters} characters for ${content.length} input characters",
            )
            assertIs<List<NotificationBlock>>(result.blocks)
        }
    }
}
