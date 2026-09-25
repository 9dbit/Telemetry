package com.telemetry.app.media

import android.content.Context
import android.net.Uri
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
            controllerRef?.ingestLocalControlWire(frame.encodedEnvelope)
        }
    )

    private val endpoint: AndroidMeshWireEndpoint = AndroidMeshWireEndpoint(identity, node)
    private val controller: AndroidMediaTransferController = AndroidMediaTransferController(
        context = context.applicationContext,
        identity = identity,
        controlChannel = AndroidTrustedControlChannel(identity, trustStore),
        node = node,
        wifiPort = wifiPort,
        onEvent = onEvent
    )

    init {
        endpointRef = endpoint
        controllerRef = controller
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
        gattTransport.setMeshRouteListener(null)
        controller.stop()
        wifiPort.stop()
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

    fun materializeIncomingToCache(assetId: String): File =
        controller.materializeIncomingToCache(assetId)

    fun routeSnapshot(): List<MobileRouteEntry> =
        node.routeSnapshot(System.currentTimeMillis())
}
