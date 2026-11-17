package com.folderspan.service.session

import com.folderspan.exception.AuthenticationException
import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.toPageErrorState
import com.folderspan.utils.ProtoBufCodec
import kotlin.test.*

class DeviceSessionFailureTest {
    @Test fun authenticationFailureKeepsItsKindAndMessageAcrossResultEncoding() {
        val encoded = ProtoBufCodec.encode(SerializableResult.failure(AuthenticationException("session expired")))
        val result = ProtoBufCodec.decode<SerializableResult>(encoded).toResult<Boolean>()
        assertIs<AuthenticationException>(result.exceptionOrNull())
        assertEquals("session expired", result.exceptionOrNull()?.message)
        assertEquals(DEVICE_SESSION_RPC_UNAUTHORIZED, deviceSessionRpcStatus(result.exceptionOrNull()!!))
        assertEquals(DEVICE_SESSION_RPC_FORBIDDEN, deviceSessionRpcStatus(AuthorityException("forbidden")))
    }

    @Test fun closedSessionsHaveConnectionErrors() {
        assertEquals(PageErrorType.Connection, DeviceSessionClosedException().toPageErrorState()?.type)
        assertEquals(PageErrorType.Connection, AuthenticationException().toPageErrorState()?.type)
        assertEquals(PageErrorType.Authority, AuthorityException().toPageErrorState()?.type)
    }
}
