package com.folderspan.shizuku

/** 当 Shizuku 尚未连接或授权却发起调用时抛出的异常。 */
class ShizukuUnavailableException(message: String) : IllegalStateException(message)
