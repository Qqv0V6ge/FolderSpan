package com.folderspan.data.file

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class FileSimpleInfoSerializationTest {
    @Serializable
    private data class LegacyFileSimpleInfo(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val description: String = "",
        @ProtoNumber(3) val isDirectory: Boolean,
        @ProtoNumber(4) val isHidden: Boolean,
        @ProtoNumber(5) val path: String,
        @ProtoNumber(6) val mineType: String,
        @ProtoNumber(7) val size: Long,
        @ProtoNumber(8) val createdDate: Long,
        @ProtoNumber(9) val updatedDate: Long,
        @ProtoNumber(10) val protocol: FileProtocol = FileProtocol.Local,
        @ProtoNumber(11) val protocolId: String = "",
        @ProtoNumber(12) val isIgnored: Boolean = false,
    )

    @Test
    fun legacyPayloadDecodesWithUnknownSymbolicLinkState() {
        val legacy = LegacyFileSimpleInfo(
            name = "folder",
            isDirectory = true,
            isHidden = false,
            path = "/folder",
            mineType = "",
            size = 0,
            createdDate = 0,
            updatedDate = 0,
        )

        val decoded = ProtoBuf.decodeFromByteArray<FileSimpleInfo>(
            ProtoBuf.encodeToByteArray(legacy)
        )

        assertFalse(decoded.isSymbolicLink)
        assertFalse(decoded.isSymbolicLinkKnown)
    }

    @Test
    fun symbolicLinkStateSurvivesSerializationAndWithCopy() {
        val source = FileSimpleInfo(
            name = "link",
            isDirectory = false,
            isHidden = false,
            path = "/link",
            mineType = "",
            size = 0,
            createdDate = 0,
            updatedDate = 0,
            isSymbolicLink = true,
            isSymbolicLinkKnown = true,
        )

        val decoded = ProtoBuf.decodeFromByteArray<FileSimpleInfo>(
            ProtoBuf.encodeToByteArray(source)
        ).withCopy(protocol = FileProtocol.Device, protocolId = "peer")

        assertTrue(decoded.isSymbolicLink)
        assertTrue(decoded.isSymbolicLinkKnown)
    }
}
