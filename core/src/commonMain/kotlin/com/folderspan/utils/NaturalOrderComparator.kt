package com.folderspan.utils

import com.folderspan.data.file.FileSimpleInfo

/**
 * 自然排序比较器的抽象基类。
 * 提供根据自然顺序比较对象的功能。
 *
 * @param T 要比较的对象类型。
 */
abstract class BaseNaturalOrderComparator<T> : Comparator<T> {

    override fun compare(a: T, b: T): Int {
        val left = extractComparableString(a)
        val right = extractComparableString(b)

        if (left == right) {
            return 0
        }

        val leftTokens = tokenize(left)
        val rightTokens = tokenize(right)
        val size = minOf(leftTokens.size, rightTokens.size)

        for (index in 0 until size) {
            val leftToken = leftTokens[index]
            val rightToken = rightTokens[index]
            val result = when (leftToken) {
                is Token.Number if rightToken is Token.Number -> compareNumberTokens(leftToken, rightToken)
                is Token.Text if rightToken is Token.Text -> LocaleStringComparator.compare(leftToken.value, rightToken.value)
                is Token.Number -> -1
                else -> 1
            }
            if (result != 0) {
                return result
            }
        }

        if (leftTokens.size != rightTokens.size) {
            return leftTokens.size.compareTo(rightTokens.size)
        }

        val localeResult = LocaleStringComparator.compare(left, right)
        if (localeResult != 0) {
            return localeResult
        }

        return left.compareTo(right)
    }

    protected abstract fun extractComparableString(obj: T): String

    private fun compareNumberTokens(a: Token.Number, b: Token.Number): Int {
        val lengthCompare = a.normalized.length.compareTo(b.normalized.length)
        if (lengthCompare != 0) {
            return lengthCompare
        }

        val valueCompare = a.normalized.compareTo(b.normalized)
        if (valueCompare != 0) {
            return valueCompare
        }

        val leadingZeroCompare = a.leadingZeros.compareTo(b.leadingZeros)
        if (leadingZeroCompare != 0) {
            return leadingZeroCompare
        }

        val rawLengthCompare = a.raw.length.compareTo(b.raw.length)
        if (rawLengthCompare != 0) {
            return rawLengthCompare
        }

        return LocaleStringComparator.compare(a.raw, b.raw)
    }

    private fun tokenize(value: String): List<Token> {
        if (value.isEmpty()) {
            return emptyList()
        }

        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < value.length) {
            val start = index
            val isDigit = value[index].isDigit()
            index++
            while (index < value.length && value[index].isDigit() == isDigit) {
                index++
            }

            val segment = value.substring(start, index)
            tokens += if (isDigit) Token.Number(segment) else Token.Text(segment)
        }
        return tokens
    }
}

private sealed class Token {
    data class Text(val value: String) : Token()
    data class Number(val raw: String) : Token() {
        val normalized: String = raw.trimLeadingZeros()
        val leadingZeros: Int = raw.countLeadingZeros()
    }
}

private fun String.trimLeadingZeros(): String {
    val firstNonZero = indexOfFirst { item ->  item != '0' }
    return when {
        firstNonZero == -1 -> "0"
        firstNonZero <= 0 -> this
        else -> substring(firstNonZero)
    }
}

private fun String.countLeadingZeros(): Int {
    val firstNonZero = indexOfFirst { item ->  item != '0' }
    return when {
        firstNonZero == -1 -> (length - 1).coerceAtLeast(0)
        firstNonZero <= 0 -> 0
        else -> firstNonZero
    }
}

/**
 * FileInfo对象按名称的自然顺序比较器。
 */
class NaturalOrderComparator : BaseNaturalOrderComparator<FileSimpleInfo>() {

    /**
     * 从FileInfo对象中提取可比较的字符串。
     *
     * @param obj 要从中提取可比较字符串的FileInfo对象。
     * @return FileInfo对象的名称作为可比较字符串。
     */
    override fun extractComparableString(obj: FileSimpleInfo): String {
        return obj.name
    }
}
