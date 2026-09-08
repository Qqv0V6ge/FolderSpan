package com.folderspan.pro.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateChannelTest {
    @Test
    fun fromTokenDefaultsToRelease() {
        assertEquals(AppUpdateChannel.Release, AppUpdateChannel.fromToken(null))
        assertEquals(AppUpdateChannel.Release, AppUpdateChannel.fromToken(""))
        assertEquals(AppUpdateChannel.Release, AppUpdateChannel.fromToken("nightly"))
        assertEquals(AppUpdateChannel.Release, AppUpdateChannel.fromToken("release"))
        assertEquals(AppUpdateChannel.Release, AppUpdateChannel.fromToken("RELEASE"))
    }

    @Test
    fun fromTokenAcceptsBetaIgnoringCaseAndWhitespace() {
        assertEquals(AppUpdateChannel.Beta, AppUpdateChannel.fromToken("beta"))
        assertEquals(AppUpdateChannel.Beta, AppUpdateChannel.fromToken(" Beta "))
        assertEquals(AppUpdateChannel.Beta, AppUpdateChannel.fromToken("BETA"))
    }
}
