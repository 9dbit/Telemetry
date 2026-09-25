package com.telemetry.app.media

import android.content.Context
import android.net.Uri
import com.telemetry.app.call.AndroidCallSignalingChannel
import com.telemetry.app.call.CALL_SIGNAL_CONTENT_TYPE
import com.telemetry.app.call.CallSignalPort
import com.telemetry.app.call.NativeCallSignal
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.mesh.AndroidMeshNodeRuntime
import com.telemetry.app.mesh.AndroidMeshWireEndpoint
import com.telemetry.app.mesh.BleMeshTransportAdapter
import com.telemetry.app.mesh.MobileRouteEntry
import com.telemetry.app.mesh.WifiLocalMeshTransportAdapter
import com.telemetry.app.transport.AndroidTrustedControlChannel
import com.telemetry.app.transport.AndroidWifiLocalMeshPort
import com.telemetry.app.transport.TelemetryGattMeshRelayPort
import com.telemetry.app.transport.TelemetryGattTransport
import java.io.File

class AndroidMediaAppRuntime(
    context: Context,
    identity: DeviceIdentity,
    trustStore: AndroidTrustStore,
    private val gattTransport: TelemetryGattTransport,
    private val onEvent: (NativeMediaTransferEvent) -> Unit
) {
    private var controllerRef: AndroidMediaTransferController? = null
    private var endpointRef: AndroidMeshWireEndpoint? = null
    private var callChannelRef: AndroidCallSignalingChannel? = null

    @Volatile
    private var callSignalHandler: ((NativeCallSignal) -> Unit)? = null

    private val controlChannel = AndroidTrustedControlChannel(identity, trustStore)

    private val wifiPort: AndroidWifiLocalMeshPort = AndroidWifiLocalMeshPort(
        context = context.applicationContext,
        onRelayWire = { wire ->
            endpointRef?.ingestRelayWire(wire, System.currentTimeMillis()) != null
        },
        onMediaChunkWire = { wire ->
            controllerRef?.ingestIncomingChunk(wire) == true
        }
    )

    private val node: AndroidMeshNodeRuntime = AndroidMeshNodeRuntime(
        localDeviceId = identity.deviceId,
        capabilities = listOf("ble", "mesh-relay", wifiPort.localCapability),
        onLocalDelivery = { frame ->
            val wire = frame.encodedEnvelope
            val contentType = runCatching { controlChannel.decode(wire).contentType }.getOrNull()
            if (contentType == CALL_SIGNAL_CONTENT_TYPE) {
                val signal = runCatching { callChannelRef?.openLocal(wire) }.getOrNull()
                if (signal != null) callSignalHandler?.invoke(signal)
            } else {
                controllerRef?.let { controller ->
                    if (!controller.ingestLocalControlWire(wire)) {
                        controller.ingestIncomingChunk(wire)
                    }
                }
            }
        }
    )

    private val endpoint: AndroidMeshWireEndpoint = AndroidMeshWireEndpoint(identity, node)
    private val callChannel = AndroidCallSignalingChannel(identity, controlChannel, node)
    private val controller: AndroidMediaTransferController = AndroidMediaTransferController(
        context = context.applicationContext,
        identity = identity,
        controlChannel = controlChannel,
        node = node,
        wifiPort = wifiPort,
        onEvent = onEvent
    )

    init {
        endpointRef = endpoint
        controllerRef = controller
        callChannelRef = callChannel
        gattTransport.attachMeshEndpoint(endpoint)
        gattTransport.setMeshRouteListener { peerId, _ ->
            controller.onPeerRouteChanged(peerId)
        }
        node.registerTransport(BleMeshTransportAdapter(TelemetryGattMeshRelayPort(gattTransport)))
        node.registerTransport(WifiLocalMeshTransportAdapter(wifiPort))
    }

    fun start() {
        wifiPort.start()
        controller.start()
    }

    fun stop() {
        callSignalHandler = null
        gattTransport.setMeshRouteListener(null)
        controller.stop()
        wifiPort.stop()
    }

    fun setCallSignalHandler(handler: ((NativeCallSignal) -> Unit)?) {
        callSignalHandler = handler
    }

    fun callSignalPort(): CallSignalPort = callChannel

    fun availableRealtimeTransports(
        peerId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<String> {
        val capabilities = node.peerCapabilities(peerId, nowEpochMs)
        wifiPort.bindPeerCapabilities(peerId, capabilities)
        return buildList {
            if (wifiPort.peerState(peerId).available) add("wifi-local")
        }
    }

    fun observeTrustedPeer(peerId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        if (gattTransport.isMeshPeerAvailable(peerId)) {
            node.observeDirectPeer(peerId, 0, "ble", nowEpochMs)
        }
        controller.onPeerRouteChanged(peerId)
    }

    fun peerUnavailable(peerId: String) {
        node.peerUnavailable(peerId)
    }

    fun queueUri(
        uri: Uri,
        peerId: String,
        requestedKind: String? = null,
        conversationId: String = "peer:$peerId"
    ) {
        controller.queueUri(
            uri = uri,
            peerId = peerId,
            requestedKind = requestedKind,
            conversationId = conversationId
        )
    }

    fun materializeIncomingToCache(assetId: String): File? =
        controller.materializeIncomingToCache(assetId)

    fun routeSnapshot(): List<MobileRouteEntry> =
        node.routeSnapshot(System.currentTimeMillis())
}
