package com.folderspan

import com.folderspan.di.commonModule
import com.folderspan.di.commonScreenModule
import com.folderspan.service.webui.WebUiCredentialStore
import com.folderspan.service.webui.WebUiHttpServiceInterface
import com.folderspan.service.webui.WebUiRequestHandler
import com.folderspan.service.webui.WebUiSessionStore
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNull

class NativeServiceBindingsTest {
    @Test
    fun nativeApplicationDoesNotRegisterRuntimeWebUiServices() {
        val application = koinApplication { modules(commonModule, commonScreenModule) }
        try {
            val koin = application.koin
            assertNull(koin.getOrNull<WebUiHttpServiceInterface>())
            assertNull(koin.getOrNull<WebUiRequestHandler>())
            assertNull(koin.getOrNull<WebUiSessionStore>())
            assertNull(koin.getOrNull<WebUiCredentialStore>())
        } finally {
            application.close()
        }
    }
}
