package com.telemetry.app.mesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.telemetry.app.crypto.AndroidIdentityStore
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.discovery.DiscoveryEvent
import com.telemetry.app.discovery.PeerCandidate
import com.telemetry.app.discovery.TelemetryBleDiscovery
import com.telemetry.app.media.AndroidOpaqueMediaChunkStore
import com.telemetry.app.transport.AndroidWifiLocalMeshPort
import com.telemetry.app.transport.SecureTransportEvent
import com.telemetry.app.transport.TelemetryGattMeshRelayPort
import com.telemetry.app.transport.TelemetryGattTransport
import java.io.File
import java.security.SecureRandom
import java.util.UUID

class MeshLabActivity : ComponentActivity() {
    companion object {
        private const val MEDIA_RELAY_TTL_MS = 10 * 60_000L
    }

    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: AndroidTrustStore
    private lateinit var node: AndroidMeshNodeRuntime
    private lateinit var endpoint: AndroidMeshWireEndpoint
    private lateinit var transport: TelemetryGattTransport
    private lateinit var wifiPort: AndroidWifiLocalMeshPort
    private lateinit var mediaStore: AndroidOpaqueMediaChunkStore
    private var discovery: TelemetryBleDiscovery? = null

    private val peers = LinkedHashMap<String, PeerCandidate>()
    private var pendingDeviceId: String? = null
    private var trustedDeviceId: String? = null

    private lateinit var statusView: TextView
    private lateinit var peersView: LinearLayout
    private lateinit var routesView: TextView
    private lateinit var targetInput: EditText
    private lateinit var trustButton: Button
    private lateinit var routeButton: Button
    private lateinit var smallProbeButton: Button
    private lateinit var largeProbeButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startDiscovery() else setStatus("Nearby permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = AndroidIdentityStore(this).getOrCreate()
        trustStore = AndroidTrustStore(this)
        mediaStore = AndroidOpaqueMediaChunkStore(File(filesDir, "telemetry-media-relay"))

        wifiPort = AndroidWifiLocalMeshPort(
            context = this,
            onRelayWire = { wire -> endpoint.ingestRelayWire(wire, System.currentTimeMillis()) != null },
            onMediaChunkWire = { wire ->
                runCatching {
                    val result = mediaStore.put(wire, System.currentTimeMillis() + MEDIA_RELAY_TTL_MS)
                    result.accepted || result.reason == "duplicate"
                }.getOrDefault(false)
            }
        )
        node = AndroidMeshNodeRuntime(
            localDeviceId = identity.deviceId,
            capabilities = listOf("ble", "mesh-relay", wifiPort.localCapability),
            onLocalDelivery = { frame ->
                runOnUiThread { setStatus("Local opaque delivery ${frame.header.messageId}") }
            },
            onEvent = { event ->
                runOnUiThread {
                    setStatus("Mesh ${event.type}: ${event.messageId} ${event.reason ?: ""}".trim())
                    renderRoutes()
                }
            }
        )
        transport = TelemetryGattTransport(this, identity, trustStore) { event ->
            runOnUiThread { reduceSecure(event) }
        }
        endpoint = AndroidMeshWireEndpoint(identity, node)
        transport.attachMeshEndpoint(endpoint)
        node.registerTransport(BleMeshTransportAdapter(TelemetryGattMeshRelayPort(transport)))
        node.registerTransport(WifiLocalMeshTransportAdapter(wifiPort))
        wifiPort.start()

        setContentView(buildUi())
        ensurePermissionsAndStart()
    }

    override fun onDestroy() {
        discovery?.stop()
        wifiPort.stop()
        transport.stop()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            textSize = 14f
            setPadding(0, 10, 0, 8)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            setOnClickListener { action() }
        }

        root.addView(TextView(this).apply { text = "Telemetry Mesh Lab"; textSize = 24f })
        root.addView(label("Device ID\n${identity.deviceId}"))
        root.addView(label("Wi-Fi local service\ntlm-${wifiPort.serviceId}\nEphemeral mDNS ID only."))
        statusView = label("Ready")
        root.addView(statusView)

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(button("Scan") { ensurePermissionsAndStart() })
        scanRow.addView(button("Stop") { stopDiscovery() })
        root.addView(scanRow)

        root.addView(label("Nearby BLE peers"))
        peersView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersView)

        trustButton = button("Trust pending peer") { trustPending() }.apply { isEnabled = false }
        routeButton = button("Send signed route advertisement") { advertiseRoute() }.apply { isEnabled = false }
        root.addView(trustButton)
        root.addView(routeButton)

        routesView = label("No active routes")
        root.addView(label("Routes / capability state"))
        root.addView(routesView)
        root.addView(button("Refresh") { renderRoutes() })

        targetInput = EditText(this).apply {
            hint = "Target Telemetry device ID"
            isSingleLine = true
        }
        root.addView(targetInput)
        smallProbeButton = button("Send 64-byte adaptive relay probe") { sendProbe(64) }.apply { isEnabled = false }
        largeProbeButton = button("Send 64 KB Wi-Fi relay probe") { sendProbe(64 * 1024) }.apply { isEnabled = false }
        root.addView(smallProbeButton)
        root.addView(largeProbeButton)
        root.addView(label("Rules: TMA1 routes are signed. TMR1 relay data stays opaque. BLE rejects wire >480 bytes. Large probes require Wi-Fi-local. Incoming TMC1 media is stored encrypted."))
        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionsAndStart() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startDiscovery()
        else permissionLauncher.launch(required)
    }

    private fun startDiscovery() {
        discovery?.stop()
        transport.start()
        discovery = TelemetryBleDiscovery(this) { event -> runOnUiThread { reduceDiscovery(event) } }
            .also { it.start() }
        setStatus("Scanning and advertising")
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        setStatus("Discovery stopped")
    }

    private fun reduceDiscovery(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.PeerSeen -> {
                peers[event.peer.radioAddress] = event.peer
                renderPeers()
            }
            is DiscoveryEvent.Error -> setStatus(event.message)
            is DiscoveryEvent.Started -> setStatus(if (event.advertising) "BLE scan + advertise active" else "BLE scan active")
            DiscoveryEvent.Stopped -> setStatus("Discovery stopped")
        }
    }

    private fun renderPeers() {
        peersView.removeAllViews()
        peers.values.sortedByDescending { it.rssi }.forEach { peer ->
            peersView.addView(Button(this).apply {
                text = "${peer.ephemeralId} · ${peer.rssi} dBm · Connect"
                setOnClickListener { transport.connect(peer.radioAddress) }
            })
        }
    }

    private fun reduceSecure(event: SecureTransportEvent) {
        when (event) {
            is SecureTransportEvent.Connecting -> setStatus("Secure connection starting")
            is SecureTransportEvent.PendingVerification -> {
                pendingDeviceId = event.deviceId
                trustButton.isEnabled = true
                setStatus("Compare safety code ${event.safetyCode}")
            }
            is SecureTransportEvent.SessionTrusted -> {
                pendingDeviceId = null
                trustedDeviceId = event.deviceId
                trustButton.isEnabled = false
                routeButton.isEnabled = true
                smallProbeButton.isEnabled = true
                largeProbeButton.isEnabled = true
                node.observeDirectPeer(event.deviceId, 0, "ble", System.currentTimeMillis())
                setStatus("Trusted peer ${event.deviceId}")
                renderRoutes()
            }
            is SecureTransportEvent.MeshRouteUpdated -> {
                bindWifiForTrustedPeer()
                setStatus("Verified route update from ${event.deviceId}: ${event.acceptedRoutes}")
                renderRoutes()
            }
            is SecureTransportEvent.MessageReceived -> setStatus("Direct encrypted text received")
            is SecureTransportEvent.DeliveryConfirmed -> setStatus("Direct signed receipt verified")
            is SecureTransportEvent.Disconnected -> {
                trustedDeviceId?.let(node::peerUnavailable)
                trustedDeviceId = null
                routeButton.isEnabled = false
                smallProbeButton.isEnabled = false
                largeProbeButton.isEnabled = false
                setStatus("Peer disconnected")
                renderRoutes()
            }
            is SecureTransportEvent.Error -> setStatus(event.message)
        }
    }

    private fun trustPending() {
        val id = pendingDeviceId ?: return
        setStatus(if (transport.trustPeer(id)) "Trust stored" else "Unable to trust pending peer")
    }

    private fun advertiseRoute() {
        val peer = trustedDeviceId ?: return
        val wire = endpoint.createRouteWire(peer, System.currentTimeMillis())
        setStatus(if (transport.sendMeshRouteWire(peer, wire)) "Signed TMA1 sent (${wire.size} bytes)" else "TMA1 send failed")
    }

    private fun sendProbe(payloadBytes: Int) {
        val firstHop = trustedDeviceId ?: return
        val target = targetInput.text.toString().trim()
        if (target.isEmpty()) return setStatus("Enter a target device ID")
        bindWifiForTrustedPeer()
        val now = System.currentTimeMillis()
        val frame = OpaqueRelayFrame(
            MeshRelayHeader(
                messageId = "lab-${UUID.randomUUID()}",
                senderId = identity.deviceId,
                recipientId = target,
                hopCount = 0,
                hopLimit = 4,
                relayPath = listOf(identity.deviceId),
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 60_000L
            ),
            ByteArray(payloadBytes).also(SecureRandom()::nextBytes)
        )
        val result = node.sendOpaqueToNextHop(firstHop, frame)
        setStatus(if (result.sent) "Probe sent via ${result.transportId}" else "Probe queued/rejected: ${result.reason}")
    }

    private fun bindWifiForTrustedPeer(): Boolean {
        val peer = trustedDeviceId ?: return false
        return wifiPort.bindPeerCapabilities(peer, node.peerCapabilities(peer, System.currentTimeMillis()))
    }

    private fun renderRoutes() {
        val now = System.currentTimeMillis()
        val peer = trustedDeviceId
        val bound = bindWifiForTrustedPeer()
        val wifi = if (peer != null && bound) wifiPort.peerState(peer) else null
        val routes = node.routeSnapshot(now)
        routesView.text = buildString {
            if (routes.isEmpty()) append("No active routes")
            else routes.forEach { append("${it.destinationId} via ${it.viaPeerId} · ${it.hops} hop(s) · ${it.transport} · q=${it.quality}\n") }
            append("\nWi-Fi local: ")
            append(if (wifi?.available == true) "available" else if (bound) "waiting for mDNS endpoint" else "waiting for signed capability")
            append("\nEncrypted media chunks stored: ${mediaStore.records(now).size}")
        }
    }

    private fun setStatus(value: String) {
        statusView.text = value
    }
}
