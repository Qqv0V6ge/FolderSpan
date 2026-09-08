package com.folderspan.pro.core.network

internal object RequestSigner {
    private const val BlockSize = 64

    fun sign(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: ByteArray,
        timestamp: String,
        nonce: String,
        secret: String,
    ): SignedRequest {
        val bodyHash = sha256Hex(body)
        val canonicalQuery = canonicalQuery(query)
        val text = signingText(
            method = method,
            path = path,
            canonicalQuery = canonicalQuery,
            bodySha256Hex = bodyHash,
            timestamp = timestamp,
            nonce = nonce,
        )

        return SignedRequest(
            canonicalQuery = canonicalQuery,
            bodySha256Hex = bodyHash,
            signingText = text,
            signature = hmacSha256Hex(secret.encodeToByteArray(), text.encodeToByteArray()),
        )
    }

    fun signingText(
        method: String,
        path: String,
        canonicalQuery: String,
        bodySha256Hex: String,
        timestamp: String,
        nonce: String,
    ): String = listOf(
        method.uppercase(),
        path,
        canonicalQuery,
        bodySha256Hex,
        timestamp,
        nonce,
    ).joinToString("\n")

    fun canonicalQuery(query: List<Pair<String, String>>): String =
        query
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { (key, value) ->
                "${key.queryEscape()}=${value.queryEscape()}"
            }

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHexLowercase()

    private fun hmacSha256Hex(key: ByteArray, message: ByteArray): String {
        val normalizedKey = if (key.size > BlockSize) sha256(key) else key
        val keyBlock = ByteArray(BlockSize)
        normalizedKey.copyInto(keyBlock)

        val outerPad = ByteArray(BlockSize)
        val innerPad = ByteArray(BlockSize)
        for (index in 0 until BlockSize) {
            outerPad[index] = (keyBlock[index].toInt() xor 0x5c).toByte()
            innerPad[index] = (keyBlock[index].toInt() xor 0x36).toByte()
        }

        val innerHash = sha256(innerPad + message)
        return sha256(outerPad + innerHash).toHexLowercase()
    }

    private fun sha256(input: ByteArray): ByteArray {
        val padded = input.padForSha256()
        val hash = Sha256InitialHash.copyOf()
        val schedule = IntArray(64)

        for (offset in padded.indices step BlockSize) {
            for (index in 0 until 16) {
                val position = offset + index * 4
                schedule[index] =
                    ((padded[position].toInt() and 0xff) shl 24) or
                        ((padded[position + 1].toInt() and 0xff) shl 16) or
                        ((padded[position + 2].toInt() and 0xff) shl 8) or
                        (padded[position + 3].toInt() and 0xff)
            }

            for (index in 16 until 64) {
                val s0 = schedule[index - 15].rotateRight(7) xor
                    schedule[index - 15].rotateRight(18) xor
                    (schedule[index - 15] ushr 3)
                val s1 = schedule[index - 2].rotateRight(17) xor
                    schedule[index - 2].rotateRight(19) xor
                    (schedule[index - 2] ushr 10)
                schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]

            for (index in 0 until 64) {
                val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choose = (e and f) xor (e.inv() and g)
                val temp1 = h + sum1 + choose + Sha256RoundConstants[index] + schedule[index]
                val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + majority

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
        }

        val result = ByteArray(32)
        for (index in hash.indices) {
            val value = hash[index]
            val position = index * 4
            result[position] = (value ushr 24).toByte()
            result[position + 1] = (value ushr 16).toByte()
            result[position + 2] = (value ushr 8).toByte()
            result[position + 3] = value.toByte()
        }
        return result
    }

    private fun ByteArray.padForSha256(): ByteArray {
        val bitLength = size.toLong() * 8L
        val paddingLength = ((56 - (size + 1) % BlockSize) + BlockSize) % BlockSize
        val padded = ByteArray(size + 1 + paddingLength + 8)
        copyInto(padded)
        padded[size] = 0x80.toByte()

        for (index in 0 until 8) {
            padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }
        return padded
    }

    private fun ByteArray.toHexLowercase(): String {
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            result.append(HexDigits[value ushr 4])
            result.append(HexDigits[value and 0x0f])
        }
        return result.toString()
    }

    private fun String.queryEscape(): String {
        val bytes = encodeToByteArray()
        val result = StringBuilder(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            when {
                value.isQueryUnreserved() -> result.append(value.toChar())
                value == ' '.code -> result.append('+')
                else -> {
                    result.append('%')
                    result.append(UppercaseHexDigits[value ushr 4])
                    result.append(UppercaseHexDigits[value and 0x0f])
                }
            }
        }
        return result.toString()
    }

    private fun Int.isQueryUnreserved(): Boolean =
        this in 'a'.code..'z'.code ||
            this in 'A'.code..'Z'.code ||
            this in '0'.code..'9'.code ||
            this == '-'.code ||
            this == '_'.code ||
            this == '.'.code ||
            this == '~'.code

    private val HexDigits = "0123456789abcdef".toCharArray()
    private val UppercaseHexDigits = "0123456789ABCDEF".toCharArray()

    private val Sha256InitialHash = intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )

    private val Sha256RoundConstants = intArrayOf(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf.toInt(),
        0xe9b5dba5.toInt(),
        0x3956c25b,
        0x59f111f1,
        0x923f82a4.toInt(),
        0xab1c5ed5.toInt(),
        0xd807aa98.toInt(),
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe.toInt(),
        0x9bdc06a7.toInt(),
        0xc19bf174.toInt(),
        0xe49b69c1.toInt(),
        0xefbe4786.toInt(),
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152.toInt(),
        0xa831c66d.toInt(),
        0xb00327c8.toInt(),
        0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(),
        0xd5a79147.toInt(),
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e.toInt(),
        0x92722c85.toInt(),
        0xa2bfe8a1.toInt(),
        0xa81a664b.toInt(),
        0xc24b8b70.toInt(),
        0xc76c51a3.toInt(),
        0xd192e819.toInt(),
        0xd6990624.toInt(),
        0xf40e3585.toInt(),
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814.toInt(),
        0x8cc70208.toInt(),
        0x90befffa.toInt(),
        0xa4506ceb.toInt(),
        0xbef9a3f7.toInt(),
        0xc67178f2.toInt(),
    )
}

internal data class SignedRequest(
    val canonicalQuery: String,
    val bodySha256Hex: String,
    val signingText: String,
    val signature: String,
)
