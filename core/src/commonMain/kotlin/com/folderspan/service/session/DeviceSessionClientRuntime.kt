package com.folderspan.service.session

import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.service.message.DeviceMessageClient
import com.folderspan.service.message.DeviceMessageEndpointIdentity
import com.folderspan.service.message.DeviceMessageLiveEndpoint
import com.folderspan.service.message.DeviceMessageLiveEndpointRegistry
import com.folderspan.service.message.DeviceMessageTransport
import com.folderspan.service.message.IncomingDeviceMessageCoordinator
import com.folderspan.service.message.newDeviceMessageId
import com.folderspan.service.operation.HttpTransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal data class DeviceSessionClientAuthenticationContext(
    val peerDeviceId: String,
    val authenticate: suspend (DeviceSessionPeer) -> DeviceSessionConnectResponse,
)

internal data class DeviceSessionClientMessagingContext(
    val endpointRegistry: DeviceMessageLiveEndpointRegistry,
    val incomingCoordinator: IncomingDeviceMessageCoordinator,
)

internal class DeviceSessionClientRuntime internal constructor(
    val peer: DeviceSessionPeer,
    val sessionJob: Job,
    val connectResponse: DeviceSessionConnectResponse,
    val clients: DeviceSessionClients,
    val transferStatus: HttpTransferStatus,
    private val messageEndpoint: DeviceSessionClientMessageEndpoint?,
) {
    val messageClient: DeviceMessageClient?
        get() = messageEndpoint?.client

    suspend fun close() {
        messageEndpoint?.close()
        peer.close()
    }
}

internal object DeviceSessionClientRuntimeLauncher {
    suspend fun start(
        channel: DeviceSessionByteChannel,
        scope: CoroutineScope,
        authentication: DeviceSessionClientAuthenticationContext,
        messaging: DeviceSessionClientMessagingContext? = null,
        sessionPlan: DeviceSessionWindowPlan = DeviceSessionWindowPlan.fromMemory(),
    ): DeviceSessionClientRuntime {
        val transport = DeviceSessionTransport(
            channel = channel,
            sendPlan = sessionPlan,
            receivePlan = sessionPlan,
            isClient = true,
        )
        val peer = DeviceSessionPeer(transport, sessionPlan)
        var messageEndpoint: DeviceSessionClientMessageEndpoint? = null
        peer.setCloseHandler { messageEndpoint?.close() }
        val sessionJob = peer.start(scope)
        try {
            val response = authentication.authenticate(peer)
            val transferStatus = sessionPlan.toTransferStatus(
                remoteMaxFileStreams = response.maxFileStreams,
                remoteRecommendedChunkBytes = response.recommendedChunkBytes,
                sampledAtMillis = Clock.System.now().toEpochMilliseconds(),
            )
            val clients = createDeviceSessionClients(
                peer = peer,
                transferStatus = transferStatus,
                archiveCapabilities = response.archiveCapabilities.normalized(),
            )
            if (
                response.connection.connectType == DeviceConnectType.APPROVED &&
                messaging != null
            ) {
                messageEndpoint = DeviceSessionClientMessageEndpoint.install(
                    peer = peer,
                    peerDeviceId = authentication.peerDeviceId,
                    endpointRegistry = messaging.endpointRegistry,
                    incomingCoordinator = messaging.incomingCoordinator,
                )
            }
            peer.setRequestHandler { request ->
                clients.handleIncomingRequest(request)
                    ?: messageEndpoint?.handleIncomingRequest(request)
                    ?: DeviceSessionControlResponse(
                        requestId = request.requestId,
                        status = DEVICE_SESSION_RPC_NOT_FOUND,
                        errorMessage = request.method,
                    )
            }
            return DeviceSessionClientRuntime(
                peer = peer,
                sessionJob = sessionJob,
                connectResponse = response,
                clients = clients,
                transferStatus = transferStatus,
                messageEndpoint = messageEndpoint,
            )
        } catch (error: Throwable) {
            messageEndpoint?.close()
            runCatching { peer.close() }
            throw error
        }
    }
}

internal class DeviceSessionClientMessageEndpoint private constructor(
    private val identity: DeviceMessageEndpointIdentity,
    val client: DeviceMessageClient,
    private val handler: DeviceSessionMessageHandler,
    private val endpointRegistry: DeviceMessageLiveEndpointRegistry,
    private val incomingCoordinator: IncomingDeviceMessageCoordinator,
) {
    private val closeMutex = Mutex()
    private var active = true

    suspend fun close() {
        closeMutex.withLock {
            if (!active) return
            active = false
            incomingCoordinator.discardEndpoint(identity)
            endpointRegistry.unregister(identity)
        }
    }

    suspend fun handleIncomingRequest(
        request: DeviceSessionControlRequest,
    ): DeviceSessionControlResponse = handler.handle(request)

    companion object {
        suspend fun install(
            peer: DeviceSessionPeer,
            peerDeviceId: String,
            endpointRegistry: DeviceMessageLiveEndpointRegistry,
            incomingCoordinator: IncomingDeviceMessageCoordinator,
        ): DeviceSessionClientMessageEndpoint {
            val identity = DeviceMessageEndpointIdentity(
                peerDeviceId = peerDeviceId,
                transport = DeviceMessageTransport.Session,
                connectionId = newDeviceMessageId(),
            )
            val handler = DeviceSessionMessageHandler(
                endpointIdentity = identity,
                coordinator = incomingCoordinator,
            )
            peer.setStreamOpenHandler(handler)
            val client = SessionDeviceMessageClient(peer, identity)
            val endpoint = DeviceSessionClientMessageEndpoint(
                identity = identity,
                client = client,
                handler = handler,
                endpointRegistry = endpointRegistry,
                incomingCoordinator = incomingCoordinator,
            )
            endpointRegistry.register(
                DeviceMessageLiveEndpoint(
                    identity = identity,
                    client = client,
                    isAuthorized = { endpoint.active },
                ),
            )
            return endpoint
        }
    }
}
