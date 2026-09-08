package com.folderspan.service.mcp.automation

import com.folderspan.service.http.getNetworkInterfacesInfo
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import strings.AppStrings

@Serializable
data class Ipv4InterfaceSubnet(
    val address: String,
    val prefixLength: Int,
)

fun interface ActiveIpv4SubnetProvider {
    suspend fun get(): List<Ipv4InterfaceSubnet>
}

object FolderSpanActiveIpv4SubnetProvider : ActiveIpv4SubnetProvider {
    override suspend fun get(): List<Ipv4InterfaceSubnet> = getNetworkInterfacesInfo()
        .asSequence()
        .filter { item -> item.isUp && !item.isLoopback }
        .flatMap { item -> item.addresses.asSequence() }
        .filter { item -> !item.isIPv6 }
        .map { item -> Ipv4InterfaceSubnet(item.address, item.prefixLength?.takeIf { prefix -> prefix in 0..32 } ?: 24) }
        .distinct()
        .toList()
}

fun interface McpDeviceProbe {
    suspend fun probe(address: String, port: Int): Boolean
}

@Serializable
enum class DeviceScanStatus {
    Queued,
    Running,
    Success,
    Failure,
    Cancelled,
}

@Serializable
data class DeviceScanOperation(
    val id: String,
    val subnet: String?,
    val status: DeviceScanStatus,
    val totalHosts: Int,
    val completedHosts: Int,
    val discoveredAddresses: List<String>,
    val createdAt: Long,
    val completedAt: Long? = null,
    val errorCode: String? = null,
)

class DeviceScanOperationStore(
    private val scope: CoroutineScope,
    private val subnetProvider: ActiveIpv4SubnetProvider,
    private val probe: McpDeviceProbe,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val operations = mutableMapOf<String, DeviceScanOperation>()

    suspend fun start(subnet: String?, port: Int): DeviceScanOperation {
        require(port in 1..65535) { "port must be between 1 and 65535" }
        val interfaces = subnetProvider.get()
        val localAddresses = interfaces.mapTo(mutableSetOf()) { item -> item.address }
        val cidrs = if (subnet.isNullOrBlank()) {
            require(interfaces.isNotEmpty()) { "no active IPv4 subnet is available" }
            interfaces.map { item -> parseIpv4Cidr("${item.address}/${item.prefixLength}", enforceLimit = false) }
        } else {
            val requested = parseIpv4Cidr(subnet, enforceLimit = true)
            require(interfaces.any { item -> requested.isCoveredBy(item) }) {
                "subnet must be within an active local IPv4 prefix"
            }
            listOf(requested)
        }
        val addresses = cidrs
            .flatMap { cidr -> cidr.hosts() }
            .filterNot { address -> address in localAddresses }
            .distinct()
        val id = "scan_${nowMillis().toString(16)}_${Random.nextInt().toUInt().toString(16)}"
        val operation = DeviceScanOperation(
            id = id,
            subnet = subnet?.trim()?.takeIf(String::isNotEmpty),
            status = DeviceScanStatus.Queued,
            totalHosts = addresses.size,
            completedHosts = 0,
            discoveredAddresses = emptyList(),
            createdAt = nowMillis(),
        )
        mutex.withLock {
            trimCompletedLocked()
            operations[id] = operation
        }
        scope.launch(dispatcher) { run(id, addresses, port) }
        return operation
    }

    suspend fun get(id: String): DeviceScanOperation? = mutex.withLock { operations[id] }

    private suspend fun run(id: String, addresses: List<String>, port: Int) {
        update(id) { item -> item.copy(status = DeviceScanStatus.Running) }
        val discovered = mutableListOf<String>()
        var completed = 0
        try {
            addresses.chunked(PROBE_CONCURRENCY).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val results = coroutineScope {
                    chunk.map { address ->
                        async(dispatcher) {
                            val found = try {
                                probe.probe(address, port)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                false
                            }
                            address to found
                        }
                    }.awaitAll()
                }
                completed += results.size
                discovered += results.filter { item -> item.second }.map { item -> item.first }
                update(id) { item ->
                    item.copy(
                        completedHosts = completed,
                        discoveredAddresses = discovered.sortedWith(::compareIpv4),
                    )
                }
            }
            update(id) { item ->
                item.copy(
                    status = DeviceScanStatus.Success,
                    completedHosts = addresses.size,
                    completedAt = nowMillis(),
                )
            }
        } catch (error: CancellationException) {
            update(id) { item -> item.copy(status = DeviceScanStatus.Cancelled, completedAt = nowMillis()) }
            throw error
        } catch (error: Throwable) {
            LogKit.e(AppStrings.ui_mcp_device_scan_failed_arg0.format(arg0 = (id)), error)
            update(id) { item ->
                item.copy(
                    status = DeviceScanStatus.Failure,
                    completedAt = nowMillis(),
                    errorCode = "scan_failed",
                )
            }
        }
    }

    private suspend fun update(id: String, transform: (DeviceScanOperation) -> DeviceScanOperation) {
        mutex.withLock {
            operations[id]?.let { item -> operations[id] = transform(item) }
        }
    }

    private fun trimCompletedLocked() {
        if (operations.size < MAX_RETAINED_OPERATIONS) return
        operations.values
            .filter { item -> item.completedAt != null }
            .sortedBy { item -> item.completedAt }
            .take(operations.size - MAX_RETAINED_OPERATIONS + 1)
            .forEach { item -> operations.remove(item.id) }
    }

    private companion object {
        const val PROBE_CONCURRENCY = 64
        const val MAX_RETAINED_OPERATIONS = 100
    }
}

internal data class ParsedIpv4Cidr(
    val network: UInt,
    val prefixLength: Int,
) {
    val addressCount: Long = 1L shl (32 - prefixLength)

    fun isCoveredBy(local: Ipv4InterfaceSubnet): Boolean {
        val localCidr = parseIpv4Cidr("${local.address}/${local.prefixLength}", enforceLimit = false)
        if (prefixLength < localCidr.prefixLength) return false
        val localMask = if (localCidr.prefixLength == 0) 0u else UInt.MAX_VALUE shl (32 - localCidr.prefixLength)
        return (network and localMask) == localCidr.network
    }

    fun hosts(): List<String> {
        if (prefixLength == 32) return listOf(network.toIpv4())
        if (prefixLength == 31) return listOf(network.toIpv4(), (network + 1u).toIpv4())
        val first = network + 1u
        val last = network + addressCount.toUInt() - 2u
        return (first..last).map { item -> item.toIpv4() }
    }
}

internal fun parseIpv4Cidr(value: String, enforceLimit: Boolean): ParsedIpv4Cidr {
    val parts = value.trim().split('/')
    require(parts.size == 2) { "subnet must be an IPv4 CIDR" }
    val address = parseIpv4(parts[0])
    val prefix = parts[1].toIntOrNull()?.takeIf { item -> item in 0..32 }
        ?: throw IllegalArgumentException("subnet prefix must be between 0 and 32")
    val addressCount = 1L shl (32 - prefix)
    if (enforceLimit) require(addressCount <= MAX_CIDR_ADDRESS_COUNT) {
        "subnet must contain at most $MAX_CIDR_ADDRESS_COUNT addresses"
    }
    val mask = if (prefix == 0) 0u else UInt.MAX_VALUE shl (32 - prefix)
    return ParsedIpv4Cidr(address and mask, prefix)
}

private fun parseIpv4(value: String): UInt {
    val parts = value.split('.')
    require(parts.size == 4) { "invalid IPv4 address" }
    return parts.fold(0u) { result, part ->
        val octet = part.toIntOrNull()?.takeIf { item -> item in 0..255 }
            ?: throw IllegalArgumentException("invalid IPv4 address")
        (result shl 8) or octet.toUInt()
    }
}

private fun UInt.toIpv4(): String = listOf(
    this shr 24,
    (this shr 16) and 0xffu,
    (this shr 8) and 0xffu,
    this and 0xffu,
).joinToString(".") { item -> item.toString() }

private fun compareIpv4(first: String, second: String): Int = parseIpv4(first).compareTo(parseIpv4(second))

private const val MAX_CIDR_ADDRESS_COUNT = 4096L
