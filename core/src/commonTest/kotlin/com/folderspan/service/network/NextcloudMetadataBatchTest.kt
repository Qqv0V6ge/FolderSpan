package com.folderspan.service.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NextcloudMetadataBatchTest {
    private val first = "a7b9adcb-5a0d-4739-b6c7-67ae827d9f1c.commit"
    private val second = "fbcd7c68-2217-4f20-a2df-1fd0f01903ea.commit"
    private val payload = ByteArray(527) { (it % 251).toByte() }

    @Test
    fun parsesOnlySelectedRegularMembersAndCopiesTheirBytes() {
        val other = ByteArray(577) { (it * 7 % 251).toByte() }
        val rootName = first.removeSuffix(".commit") + ".root"
        val locationName = first.removeSuffix(".commit") + ".location"
        val descriptor = ByteArray(124) { it.toByte() }
        val archive = archive(member(first, payload, prefix = "/"), member(second, other),
            member("//" + rootName, other), member("//" + locationName, descriptor))
        val expected = mapOf(first to payload.size.toLong(), second to other.size.toLong(),
            rootName to other.size.toLong(), locationName to descriptor.size.toLong())
        assertEquals(archive.size.toLong(), maxNextcloudMetadataTarBytes(expected))
        val actual = parseNextcloudMetadataTar(archive, expected)
        assertEquals(expected.keys, actual.keys)
        assertContentEquals(payload, actual.getValue(first))
        assertContentEquals(other, actual.getValue(second))
        assertContentEquals(other, actual.getValue(rootName))
        assertContentEquals(descriptor, actual.getValue(locationName))
        archive.fill(0)
        assertContentEquals(payload, actual.getValue(first))
    }

    @Test
    fun rejectsUnexpectedDuplicateMissingAndUnsafePaths() {
        val expected = mapOf(first to payload.size.toLong())
        for (name in listOf(second, "/" + first, "///" + first, "../" + first, "dir/" + first,
            "//../" + first, first.uppercase(), first.removeSuffix(".commit") + ".object")) {
            rejected(archive(member(name, payload)), expected)
        }
        rejected(archive(member(first, payload), member(first, payload)),
            expected + (second to payload.size.toLong()))
        rejected(archive(member(first, payload)), expected + (second to payload.size.toLong()))
    }

    @Test
    fun rejectsLinksDirectoriesPaxAndUnsupportedHeaders() {
        val expected = mapOf(first to payload.size.toLong())
        for (type in listOf('1', '2', '3', '4', '5', '6', 'x', 'g', 'L')) {
            rejected(archive(member(first, payload, type)), expected)
        }
        for ((offset, text) in listOf(157 to "target", 345 to "prefix", 257 to "gnu", 263 to "01")) {
            val invalid = member(first, payload)
            text.encodeToByteArray().copyInto(invalid, offset)
            checksum(invalid)
            rejected(archive(invalid), expected)
        }
    }

    @Test
    fun rejectsBadChecksumNumbersSizesPaddingAndTruncation() {
        val expected = mapOf(first to payload.size.toLong())
        val valid = archive(member(first, payload))
        val badChecksum = valid.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        rejected(badChecksum, expected)
        for (value in listOf('9'.code.toByte(), 0x80.toByte())) {
            val invalid = member(first, payload)
            invalid[124] = value
            checksum(invalid)
            rejected(archive(invalid), expected)
        }
        rejected(valid, mapOf(first to payload.size.toLong() + 1))
        rejected(valid.copyOf().also { it[512 + payload.size] = 1 }, expected)
        rejected(valid.copyOf().also { it[it.lastIndex] = 1 }, expected)
        rejected(valid + ByteArray(512), expected)
        for (length in listOf(0, 511, 512, 512 + payload.size - 1, valid.size - 1)) {
            rejected(valid.copyOf(length), expected)
        }
    }

    @Test
    fun boundsBatchCountNamesSizesAndTotalResponse() {
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(mapOf(first to -1L)) }
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(mapOf(first to 65585L)) }
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(mapOf(first to Long.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(mapOf("../" + first to 1L)) }
        val many = (0..32).associate {
            it.toString(16).padStart(8, '0') + "-0000-0000-0000-000000000000.commit" to 1L
        }
        assertFailsWith<IllegalArgumentException> { maxNextcloudMetadataTarBytes(many) }
        val largest = many.entries.take(32).associate { it.key to 65584L }
        assertTrue(maxNextcloudMetadataTarBytes(largest) < 3 * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> {
            parseNextcloudMetadataTar(ByteArray(2049), mapOf(first to 1L))
        }
    }

    private fun rejected(bytes: ByteArray, expected: Map<String, Long>) {
        assertFailsWith<IllegalArgumentException> { parseNextcloudMetadataTar(bytes, expected) }
    }

    private fun archive(vararg members: ByteArray): ByteArray {
        val result = ByteArray(members.sumOf { it.size } + 1024)
        var offset = 0
        for (member in members) {
            member.copyInto(result, offset)
            offset += member.size
        }
        return result
    }

    private fun member(name: String, bytes: ByteArray, type: Char = '0', prefix: String = ""): ByteArray {
        val result = ByteArray(512 + ((bytes.size + 511) / 512) * 512)
        name.encodeToByteArray().copyInto(result)
        prefix.encodeToByteArray().copyInto(result, 345)
        (bytes.size.toString(8).padStart(11, '0') + "\u0000").encodeToByteArray().copyInto(result, 124)
        result[156] = type.code.toByte()
        "ustar\u000000".encodeToByteArray().copyInto(result, 257)
        bytes.copyInto(result, 512)
        checksum(result)
        return result
    }

    private fun checksum(bytes: ByteArray) {
        for (index in 148..155) bytes[index] = ' '.code.toByte()
        val sum = (0 until 512).sumOf { bytes[it].toInt() and 0xff }
        (sum.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(bytes, 148)
    }
}
