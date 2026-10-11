package com.folderspan.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shizuku 中枢管理器：维护用户态服务连接、权限申请流程，并向上层提供可安全调用的文件服务入口。
 */
object ShizukuManager {

    private const val SERVICE_TAG = "com.folderspan.shizuku.FileService"
    private const val SERVICE_VERSION = 1

    private val initialized = AtomicBoolean(false)
    private val contextRef = AtomicReference<Context?>()

    private val permissionStateInternal = MutableStateFlow(ShizukuPermissionState.Unsupported)
    private val serviceStateInternal = MutableStateFlow<ShizukuServiceState>(ShizukuServiceState.Idle)

    private val requestCodeGenerator = AtomicInteger(1000)

    private val binderListener = Shizuku.OnBinderReceivedListener {
        LogKit.i("Shizuku binder received")
        onBinderReceived()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        LogKit.w("Shizuku binder dead")
        serviceClient = null
        serviceStateInternal.value = ShizukuServiceState.Disconnected
        updatePermissionState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service == null) {
                LogKit.e("Shizuku service connected with null binder")
                serviceStateInternal.value = ShizukuServiceState.Failed(null)
                serviceClient = null
                return
            }
            LogKit.i("Shizuku service connected: $name")
            serviceClient = ShizukuFileServiceClient(service)
            serviceStateInternal.value = ShizukuServiceState.Bound
        }

        override fun onServiceDisconnected(name: ComponentName) {
            LogKit.w("Shizuku service disconnected: $name")
            serviceClient = null
            serviceStateInternal.value = ShizukuServiceState.Disconnected
        }
    }

    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var serviceClient: ShizukuFileServiceClient? = null

    val permissionState: StateFlow<ShizukuPermissionState> = permissionStateInternal.asStateFlow()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        contextRef.set(context.applicationContext)
        permissionStateInternal.value = ShizukuPermissionState.ServiceMissing

        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Shizuku.pingBinder()) {
            onBinderReceived()
        }
    }

    fun hasPermission(): Boolean =
        permissionStateInternal.value == ShizukuPermissionState.Granted

    suspend fun awaitServiceReady(): Boolean {
        if (!hasPermission()) return false
        if (serviceClient != null) return true
        if (contextRef.get() == null) return false

        bindUserService()
        val state = serviceStateInternal.first { item ->
            item is ShizukuServiceState.Bound ||
                item is ShizukuServiceState.Disconnected ||
                item is ShizukuServiceState.Failed
        }
        return state is ShizukuServiceState.Bound && serviceClient != null
    }

    suspend fun requestPermission(): ShizukuPermissionResult {
        if (!Shizuku.pingBinder()) {
            permissionStateInternal.value = ShizukuPermissionState.ServiceMissing
            return ShizukuPermissionResult.ServiceMissing
        }
        if (hasPermission()) {
            return ShizukuPermissionResult.Granted
        }

        return suspendCancellableCoroutine { cont ->
            val requestCode = requestCodeGenerator.incrementAndGet()
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                    if (code != requestCode) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    updatePermissionState()
                    val result = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        ShizukuPermissionResult.Granted
                    } else {
                        ShizukuPermissionResult.Denied
                    }
                    if (!cont.isCompleted) {
                        cont.resume(result)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (!cont.isCompleted) {
                    cont.resume(ShizukuPermissionResult.Cancelled)
                }
            }
            runCatching {
                Shizuku.requestPermission(requestCode)
            }.onFailure { throwable ->
                Shizuku.removeRequestPermissionResultListener(listener)
                if (!cont.isCompleted) {
                    if (throwable is CancellationException) {
                        cont.resume(ShizukuPermissionResult.Cancelled)
                    } else {
                        cont.resumeWithException(throwable)
                    }
                }
            }
        }.also { item ->
            if (item == ShizukuPermissionResult.Granted) {
                bindUserService()
            }
        }
    }

    fun ensureClient(): ShizukuFileServiceClient? {
        if (!Shizuku.pingBinder()) {
            permissionStateInternal.value = ShizukuPermissionState.ServiceMissing
            serviceClient = null
            return null
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            permissionStateInternal.value = ShizukuPermissionState.Granted
        } else {
            permissionStateInternal.value = ShizukuPermissionState.Denied
            return null
        }
        val client = serviceClient
        if (client != null) return client
        bindUserService()
        return serviceClient
    }

    inline fun <T> withClient(block: (ShizukuFileServiceClient) -> Result<T>): Result<T> {
        val client = ensureClient() ?: return Result.failure(
            ShizukuUnavailableException("Shizuku service is not ready")
        )
        return try {
            block(client)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun onBinderReceived() {
        updatePermissionState()
        if (hasPermission()) {
            bindUserService()
        }
    }

    private fun bindUserService() {
        val context = contextRef.get() ?: return
        if (!hasPermission()) {
            LogKit.w("Shizuku bind skipped: permission not granted")
            return
        }

        val currentState = serviceStateInternal.value
        if (currentState is ShizukuServiceState.Binding || currentState is ShizukuServiceState.Bound) {
            return
        }

        val componentName = ComponentName(
            context.packageName,
            ShizukuFileService::class.java.name
        )
        val args = Shizuku.UserServiceArgs(componentName)
            .tag(SERVICE_TAG)
            .version(SERVICE_VERSION)
            .processNameSuffix("shizuku_user_service")

        serviceStateInternal.value = ShizukuServiceState.Binding
        runCatching {
            Shizuku.bindUserService(args, serviceConnection)
            serviceArgs = args
        }.onFailure { throwable ->
            LogKit.e("Failed to bind Shizuku service: ${throwable.message}", throwable)
            serviceClient = null
            serviceStateInternal.value = ShizukuServiceState.Failed(throwable)
        }
    }

    private fun updatePermissionState() {
        if (!Shizuku.pingBinder()) {
            permissionStateInternal.value = ShizukuPermissionState.ServiceMissing
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
        permissionStateInternal.value = if (granted) {
            ShizukuPermissionState.Granted
        } else {
            ShizukuPermissionState.Denied
        }
    }

}
