package com.folderspan.shizuku

import android.os.IBinder

/** App 进程与 Shizuku 特权服务共享的 Binder 事务码定义。 */
object ShizukuTransactions {
    const val DESCRIPTOR: String = "com.folderspan.shizuku.IFileService"

    const val TRANSACTION_LIST_CHILDREN: Int = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_GET_FILE: Int = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_GET_FILE_INFO: Int = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_GET_FILE_IN_DIRECTORY: Int = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_DELETE_PATH: Int = IBinder.FIRST_CALL_TRANSACTION + 5
    const val TRANSACTION_CREATE_DIRECTORY: Int = IBinder.FIRST_CALL_TRANSACTION + 6
    const val TRANSACTION_CREATE_FILE: Int = IBinder.FIRST_CALL_TRANSACTION + 7
    const val TRANSACTION_RENAME: Int = IBinder.FIRST_CALL_TRANSACTION + 8
    const val TRANSACTION_TOTAL_SPACE: Int = IBinder.FIRST_CALL_TRANSACTION + 9
    const val TRANSACTION_FREE_SPACE: Int = IBinder.FIRST_CALL_TRANSACTION + 10
    const val TRANSACTION_EXISTS: Int = IBinder.FIRST_CALL_TRANSACTION + 11
    const val TRANSACTION_DELETE_DIRECTORY: Int = IBinder.FIRST_CALL_TRANSACTION + 12
    const val TRANSACTION_OPEN_FILE_DESCRIPTOR: Int = IBinder.FIRST_CALL_TRANSACTION + 13
    const val TRANSACTION_DESTROY: Int = 16777115
}
