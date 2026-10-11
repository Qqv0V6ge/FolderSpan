package com.folderspan.extensions

fun ByteArray.chunked(size: Int): List<ByteArray> {
    if (size <= 0) {
        return emptyList()
    }

    val result = mutableListOf<ByteArray>()
    val totalChunks = (this.size + size - 1) / size // 计算总块数

    for (i in 0 until totalChunks) {
        val start = i * size
        val end = (start + size).coerceAtMost(this.size)
        result.add(this.copyOfRange(start, end))
    }

    return result
}