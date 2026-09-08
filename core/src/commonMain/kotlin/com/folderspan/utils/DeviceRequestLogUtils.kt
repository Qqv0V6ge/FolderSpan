package com.folderspan.utils

import com.folderspan.data.main.device.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 设备请求日志管理工具类
 * 内存中最多保存100条记录，超过后保存到临时文件
 */
class DeviceRequestLogUtils(private val device: Device) {
    private val memoryLogs = mutableListOf<String>()
    private val maxMemorySize = 100
    private val mutex = Mutex()

    private val logFilePath: String
        get() = "${PathUtils.getCachePath()}${PathUtils.getPathSeparator()}device_logs${PathUtils.getPathSeparator()}${device.id}.txt"

    /**
     * 添加请求路径
     */
    suspend fun addPath(path: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (memoryLogs.size >= maxMemorySize) {
                // 将内存中的日志写入文件
                flushToFile()
                memoryLogs.clear()
            }
            memoryLogs.add(path)
        }
    }

    /**
     * 获取所有请求路径
     */
    suspend fun getAllPaths(): List<String> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val fileLogs = readFromFile()
            fileLogs + memoryLogs
        }
    }

    /**
     * 清除所有日志
     */
    suspend fun clear() = withContext(Dispatchers.Default) {
        mutex.withLock {
            deleteLogFileLocked()
        }
    }

    fun deleteLogFile() {
        deleteLogFileLocked()
    }

    private fun deleteLogFileLocked() {
        memoryLogs.clear()
        FileUtils.deleteFile(FileAccessPermission.Allowed, logFilePath)
    }

    /**
     * 将内存日志写入文件
     */
    private fun flushToFile() {
        try {
            ensureLogDirExists()
            val content = memoryLogs.joinToString("\n") + "\n"
            FileUtils.appendToFile(FileAccessPermission.Allowed, logFilePath, content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 从文件读取日志
     */
    private fun readFromFile(): List<String> {
        return try {
            if (!PathUtils.exists(FileAccessPermission.Allowed, logFilePath)) {
                return emptyList()
            }
            FileUtils.readFileLines(FileAccessPermission.Allowed, logFilePath)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun ensureLogDirExists() {
        val dirPath = "${PathUtils.getCachePath()}${PathUtils.getPathSeparator()}device_logs"
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, dirPath)
    }

    companion object {
        /**
         * 清除所有设备的日志文件
         */
        suspend fun clearAllLogs() = withContext(Dispatchers.Default) {
            try {
                val dirPath = "${PathUtils.getCachePath()}${PathUtils.getPathSeparator()}device_logs"
                PathUtils.deleteDirectory(FileAccessPermission.Allowed, dirPath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}