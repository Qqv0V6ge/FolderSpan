package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpUrlsTest {
    @Test
    fun acceptsHttpAndHttps() {
        assertEquals("https://example.test/app", httpOrHttpsUrlOrNull("https://example.test/app"))
        assertEquals("http://example.test/app", httpOrHttpsUrlOrNull(" http://example.test/app "))
        assertEquals("HTTPS://EXAMPLE.TEST", httpOrHttpsUrlOrNull("HTTPS://EXAMPLE.TEST"))
    }

    @Test
    fun rejectsEmptyAndOtherSchemes() {
        assertNull(httpOrHttpsUrlOrNull(""))
        assertNull(httpOrHttpsUrlOrNull("javascript:alert(1)"))
        assertNull(httpOrHttpsUrlOrNull("folderSpan://update"))
        assertNull(httpOrHttpsUrlOrNull("example.test/app"))
    }
}
