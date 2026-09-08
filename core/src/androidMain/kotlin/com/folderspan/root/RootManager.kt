package com.folderspan.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.folderspan.privileged.PrivilegedFileClient
import com.folderspan.shizuku.ShizukuFileServiceClient
import com.folderspan.utils.LogKit
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object RootManager {
    private const val SERVICE_BIND_TIMEOUT_MS = 1_500L

    private val initialized = AtomicBoolean(false)
    private val contextRef = AtomicReference<Context?>()
    private val bindLatchRef = AtomicReference<CountDownLatch?>()

    private val permissionStateInternal = MutableStateFlow<RootPermissionState>(RootPermissionState.NotDetermined)
    private val serviceStateInternal = MutableStateFlow<RootServiceState>(RootServiceState.Idle)

    private var serviceClient: ShizukuFileServiceClient? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service == null) {
                LogKit.e("Root service connected with null binder")
                serviceClient = null
                serviceStateInternal.value = RootServiceState.Failed(null)
                finishPendingBind()
                return
            }
            LogKit.i("Root service connected: $name")
            serviceClient = ShizukuFileServiceClient(service, backendName = "root")
            serviceStateInternal.value = RootServiceState.Bound
            finishPendingBind()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            LogKit.w("Root service disconnected: $name")
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Disconnected
            finishPendingBind()
        }

        override fun onBindingDied(name: ComponentName) {
            LogKit.w("Root service binding died: $name")
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Disconnected
            finishPendingBind()
        }

        override fun onNullBinding(name: ComponentName) {
            LogKit.e("Root service returned null binding: $name")
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Failed(null)
            finishPendingBind()
        }
    }

    val permissionState: StateFlow<RootPermissionState> = permissionStateInternal.asStateFlow()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        contextRef.set(appContext)
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setContext(appContext)
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
            )
        }.onFailure { error ->
            LogKit.w("Root shell builder setup failed: ${error.message}")
        }
        updatePassivePermissionState()
        if (hasPermission()) {
            bindRootService(appContext)
        }
    }

    fun hasPermission(): Boolean = permissionStateInternal.value == RootPermissionState.Granted

    suspend fun awaitServiceReady(): Boolean {
        updatePassivePermissionState()
        if (!hasPermission()) return false
        if (serviceClient != null) return true
        val context = contextRef.get() ?: return false

        bindRootService(context)
        val state = serviceStateInternal.first { item ->
            item is RootServiceState.Bound ||
                item is RootServiceState.Disconnected ||
                item is RootServiceState.Failed
        }
        return state is RootServiceState.Bound && serviceClient != null
    }

    fun requestPermission(): RootPermissionResult {
        val context = contextRef.get() ?: return RootPermissionResult.Unavailable.also {
            permissionStateInternal.value = RootPermissionState.Unsupported
        }
        permissionStateInternal.value = RootPermissionState.Requesting
        return try {
            if (Shell.getShell().isRoot) {
                permissionStateInternal.value = RootPermissionState.Granted
                bindRootService(context)?.awaitConnection()
                RootPermissionResult.Granted
            } else {
                permissionStateInternal.value = RootPermissionState.Denied
                RootPermissionResult.Denied
            }
        } catch (error: NoShellException) {
            LogKit.w("Root shell unavailable: ${error.message}")
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Failed(error)
            permissionStateInternal.value = RootPermissionState.Unsupported
            RootPermissionResult.Unavailable
        } catch (error: Throwable) {
            LogKit.e("Root permission request failed: ${error.message}", error)
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Failed(error)
            permissionStateInternal.value = RootPermissionState.Failed(error)
            RootPermissionResult.Unavailable
        }
    }

    fun ensureClient(): PrivilegedFileClient? {
        updatePassivePermissionState()
        if (!hasPermission()) return null
        serviceClient?.let { return it }
        val context = contextRef.get() ?: return null
        bindRootService(context)?.awaitConnection()
        return serviceClient
    }

    inline fun <T> withClient(block: (PrivilegedFileClient) -> Result<T>): Result<T> {
        val client = ensureClient() ?: return Result.failure(
            RootUnavailableException("Root service is not ready")
        )
        return try {
            block(client)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun updatePassivePermissionState() {
        val cachedShell = runCatching { Shell.getCachedShell() }.getOrNull()
        if (cachedShell?.isRoot == true) {
            permissionStateInternal.value = RootPermissionState.Granted
            return
        }
        val granted = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        if (granted == true) {
            permissionStateInternal.value = RootPermissionState.Granted
            return
        }
        if (!RootAvailability.hasSuBinary()) {
            permissionStateInternal.value = RootPermissionState.Unsupported
            return
        }
        permissionStateInternal.value = when (granted) {
            false -> RootPermissionState.Denied
            null -> RootPermissionState.NotDetermined
        }
    }

    private fun bindRootService(context: Context): CountDownLatch? {
        when (serviceStateInternal.value) {
            is RootServiceState.Bound -> return null
            is RootServiceState.Binding -> return bindLatchRef.get()
            else -> Unit
        }
        val latch = CountDownLatch(1)
        bindLatchRef.set(latch)
        serviceStateInternal.value = RootServiceState.Binding
        val intent = Intent(context, RootFileService::class.java)
        val scheduled = runOnMainThread {
            runCatching {
                RootService.bind(intent, serviceConnection)
            }.onFailure { error ->
                LogKit.e("Failed to bind root service: ${error.message}", error)
                serviceClient = null
                serviceStateInternal.value = RootServiceState.Failed(error)
                finishPendingBind()
            }
        }
        if (!scheduled) {
            val error = IllegalStateException("Unable to schedule root service bind on main thread")
            LogKit.e("Failed to bind root service: ${error.message}", error)
            serviceClient = null
            serviceStateInternal.value = RootServiceState.Failed(error)
            finishPendingBind()
            return null
        }
        return latch
    }

    private fun CountDownLatch.awaitConnection() {
        if (isMainThread()) return
        runCatching {
            await(SERVICE_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun isMainThread(): Boolean =
        runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)

    private fun runOnMainThread(block: () -> Unit): Boolean {
        if (isMainThread()) {
            block()
            return true
        }
        return runCatching {
            Handler(Looper.getMainLooper()).post(block)
        }.getOrDefault(false)
    }

    private fun finishPendingBind() {
        bindLatchRef.getAndSet(null)?.countDown()
    }
}
