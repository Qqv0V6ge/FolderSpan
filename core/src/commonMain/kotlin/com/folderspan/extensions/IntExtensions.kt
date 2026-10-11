package com.folderspan.extensions

import com.folderspan.utils.secureRandomBytes
import strings.AppStrings

/**
 * 将整型 [Int] 作为扩展，用于生成指定长度的随机密码。
 * @param includeUpperCase 是否包含大写字母
 * @param includeLowerCase 是否包含小写字母
 * @param includeDigits 是否包含数字
 * @param includeSpecial 是否包含特殊字符
 * @throws IllegalArgumentException 当所有字符组都被禁用时抛出异常
 */
fun Int.randomString(
    includeUpperCase: Boolean = true,
    includeLowerCase: Boolean = true,
    includeDigits: Boolean = true,
    includeSpecial: Boolean = true
): String {
    // 根据参数构建可用字符集
    val uppercaseChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val lowercaseChars = "abcdefghijklmnopqrstuvwxyz"
    val digitChars = "0123456789"
    val specialChars = "!@#\$%^&*()-_=+"

    // 将用户选定的字符组汇总
    val characterPool = buildString {
        if (includeUpperCase) append(uppercaseChars)
        if (includeLowerCase) append(lowercaseChars)
        if (includeDigits) append(digitChars)
        if (includeSpecial) append(specialChars)
    }

    // 若没有选择任何字符类型，则抛出异常
    require(characterPool.isNotEmpty()) {
        AppStrings.ui_select_at_least_one_character_group
    }

    return buildString(this) {
        repeat(this@randomString) {
            val index = secureRandomInt(characterPool.length)
            append(characterPool[index])
        }
    }
}

private fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound must be positive" }
    val maxUnbiased = Int.MAX_VALUE - (Int.MAX_VALUE % bound)
    while (true) {
        val bytes = secureRandomBytes(Int.SIZE_BYTES)
        val value = (
            ((bytes[0].toInt() and 0x7F) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            )
        if (value < maxUnbiased) {
            return value % bound
        }
    }
}

/**
 * 将九位文件权限位（如 0o755 对应的十进制 Int）格式化为八进制字符串。
 * 默认带前导 0（例如：0755）。
 */
fun Int.formatPermissionsOctal(includeLeadingZero: Boolean = true): String {
    val mode = this and 0x1FF // 仅保留 rwxrwxrwx 的 9 位
    // 手写八进制转换以兼容多平台（不依赖 JDK Formatter）
    var v = mode
    val buf = CharArray(4)
    var i = 0
    do {
        val d = (v and 7)
        buf[i++] = ('0'.code + d).toChar()
        v = v ushr 3
    } while (v != 0 && i < buf.size)
    val oct = CharArray(i) { idx -> buf[i - 1 - idx] }.concatToString()
    val padded = oct.padStart(3, '0')
    return if (includeLeadingZero) "0$padded" else padded
}

/**
 * 将九位文件权限位（如 0o755 对应的十进制 Int）格式化为 rwx 字符串（如：rwxr-xr-x）。
 */
fun Int.formatPermissionsRwx(): String {
    val mode = this and 0x1FF
    val masks = intArrayOf(256, 128, 64, 32, 16, 8, 4, 2, 1)
    val chars = charArrayOf('r','w','x','r','w','x','r','w','x')
    val out = CharArray(9)
    for (i in 0..8) {
        out[i] = if ((mode and masks[i]) != 0) chars[i] else '-'
    }
    return out.concatToString()
}
