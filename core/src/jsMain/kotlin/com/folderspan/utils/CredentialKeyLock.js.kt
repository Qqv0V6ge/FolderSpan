package com.folderspan.utils

// 同一浏览器事件循环中，无挂起点的密钥读写不会交错。
internal actual class CredentialKeyLock actual constructor() {
    actual fun lock() = Unit

    actual fun unlock() = Unit
}
