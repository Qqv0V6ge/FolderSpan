package com.folderspan.routes

import strings.AppStrings

import com.folderspan.utils.FileAccessPermission
import com.folderspan.exception.EmptyDataException
import com.folderspan.service.data.SerializableResult
import com.folderspan.service.data.toResult
import com.folderspan.utils.FileUtils
import com.folderspan.utils.ProtoBufCodec
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RawHttpFileRoutesTest {
    @Test
    fun fileFailureMapsMissingFileMessagesToNotFound() {
        listOf(AppStrings.ui_not_found_file, AppStrings.ui_file_does_not_exist).forEach { message ->
            val response = fileFailure(
                Result.failure<Unit>(Exception(message))
            )

            assertEquals(404, response.statusCode, "message=$message")
            val body = response.body as RawHttpBody.Bytes
            val error = ProtoBufCodec.decode<SerializableResult>(body.bytes)
                .toResult<Unit>()
                .exceptionOrNull()

            assertIs<EmptyDataException>(error)
            assertEquals(message, error.message)
        }
    }

    @Test
    fun fileFailureMapsActualMissingFileReadToNotFound() {
        val missingPath = Files.createTempDirectory("raw-http-missing-file")
            .resolve("missing.txt")
            .absolutePathString()
        val response = fileFailure(runCatching { FileUtils.readFileLines(FileAccessPermission.Allowed, missingPath) })

        assertEquals(404, response.statusCode)
        val body = response.body as RawHttpBody.Bytes
        val error = ProtoBufCodec.decode<SerializableResult>(body.bytes)
            .toResult<Unit>()
            .exceptionOrNull()

        assertIs<EmptyDataException>(error)
    }
}
