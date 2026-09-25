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
import com.telemetry.app.transport.AndroidWifiLocalMeshPort
import com.telemetry.app.transport.SecureTransportEvent
import com.telemetry.app.transport.TelemetryGattMeshRelayPort
import com.telemetry.app.transport.TelemetryGattTransport
import java.security.SecureRandom
import java.util.UUID

class MeshLabActivity : ComponentActivity() {
    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: AndroidTrustStore
    private lateinit var node: AndroidMeshNodeRuntime
    private lateinit var endpoint: AndroidMeshWireEndpoint
    private lateinit var transport: TelemetryGattTransport
    private lateinit var wifiPort: AndroidWifiLocalMeshPort
    private var discovery: TelemetryBleDiscovery? = null

    private lateinit var statusView: TextView
    private lateinit var peersContainer: LinearLayout
    private lateinit var routesView: TextView
    private lateinit var targetInput: EditText
    private lateinit var trustButton: Button
    private lateinit var advertiseButton: Button
    private lateinit var probeButton: Button
    private lateinit var largeProbeButton: Button

    private val peers = LinkedHashMap<String, PeerCandidate>()
    private var pendingDeviceId: String? = null
    private var trustedDeviceId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startDiscovery()
        else setStatus("Nearby-device permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identity = AndroidIdentityStore(this).getOrCreate()
        trustStore = AndroidTrustStore(this)
        wifiPort = AndroidWifiLocalMeshPort(this) { wire ->
            endpoint.ingestRelayWire(wire, System.currentTimeMillis()) != null
        }
        node = AndroidMeshNodeRuntime(
            localDeviceId = identity.deviceId,
            capabilities = listOf("ble", "mesh-relay", wifiPort.localCapability)
        ) { event ->
            runOnUiThread {
                setStatus("Mesh ${event.type}: ${event.messageId} ${event.reason ?: ""}".trim())
                renderRoutes()
            }
        }
        transport = TelemetryGattTransport(this, identity, trustStore) { event ->
            runOnUiThread { reduceSecure(event) }
        }
        endpoint = AndroidMeshWireEndpoint(identity, node)
        transport.attachMeshEndpoint(endpoint)
        node.registerTransport(BleMeshTransportAdapter(TelemetryGattMeshRelayPort(transport)))
        node.registerTransport(WifiLocalMeshTransportAdapter(wifiPort))
        wifiPort.start()

        setContentView(buildContent())
        ensurePermissionsAndStart()
    }

    override fun onDestroy() {
        discovery?.stop()
        wifiPort.stop()
        transport.stop()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 12, 0, 8)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        root.addView(TextView(this).apply {
            text = "Telemetry Mesh Lab"
            textSize = 24f
        })
        root.addView(label("Device ID\n${identity.deviceId}"))
        root.addView(label("Local Wi-Fi service\ntlm-${wifiPort.serviceId}\nEphemeral service ID only; device ID is not broadcast over mDNS."))

        statusView = label("Ready")
        root.addView(statusView)

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(button("Scan") { ensurePermissionsAndStart() })
        scanRow.addView(button("Stop") { stopDiscovery() })
        root.addView(scanRow)

        root.addView(label("Nearby BLE peers"))
        peersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersContainer)

        trustButton = button("Trust pending peer") { trustPending() }.apply { isEnabled = false }
        root.addView(trustButton)

        advertiseButton = button("Send signed route advertisement") { advertiseRoute() }.apply { isEnabled = false }
        root.addView(advertiseButton)

        root.addView(label("Routes"))
        routesView = label("No routes")
        root.addView(routesView)
        root.addView(button("Refresh routes + Wi-Fi binding") { renderRoutes() })

        targetInput = EditText(this).apply {
            hint = "Target Telemetry device ID for relay probe"
            isSingleLine = true
        }
        root.addView(targetInput)
        probeButton = button("Send 64-byte adaptive relay probe") { sendRelayProbe(64) }.apply { isEnabled = false }
        root.addView(probeButton)
        largeProbeButton = button("Send 64 KB Wi-Fi relay probe") { sendRelayProbe(64 * 1024) }.apply { isEnabled = false }
        root.addView(largeProbeButton)

        root.addView(label("Lab rules: route advertisements are signed. Relay probes contain random opaque bytes, never plaintext. BLE frames over 480 bytes are rejected instead of fragmented silently. The 64 KB probe therefore requires Wi-Fi-local."))

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionsAndStart() {
        val permissions = requiredPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startDiscovery()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startDiscovery() {
        discovery?.stop()
        transport.start()
        discovery = TelemetryBleDiscovery(this) { event ->
            runOnUiThread { reduceDiscovery(event) }
        }.also { it.start() }
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
            is DiscoveryEvent.Started -> setStatus(
                if (event.advertising) "BLE scan + advertise active" else "BLE scan active; advertising unavailable"
            )
            DiscoveryEvent.Stopped -> setStatus("Discovery stopped")
        }
    }

    private fun renderPeers() {
        peersContainer.removeAllViews()
        peers.values.sortedByDescending { it.rssi }.forEach { peer ->
            peersContainer.addView(Button(this).apply {
                text = "${peer.ephemeralId}  ${peer.rssi} dBm  Connect"
                setOnClickListener {
                    setStatus("Connecting to ${peer.ephemeralId}")
                    transport.connect(peer.radioAddress)
                }
            })
        }
    }

    private fun reduceSecure(event: SecureTransportEvent) {
        when (event) {
            is SecureTransportEvent.Connecting -> setStatus("Secure connection starting")
            is SecureTransportEvent.PendingVerification -> {
                pendingDeviceId = event.deviceId
                trustButton.isEnabled = true
                setStatus("Verify safety code ${event.safetyCode} for ${event.deviceId}")
            }
            is SecureTransportEvent.SessionTrusted -> {
                pendingDeviceId = null
                trustedDeviceId = event.deviceId
                trustButton.isEnabled = false
                advertiseButton.isEnabled = true
                probeButton.isEnabled = true
                largeProbeButton.isEnabled = true
                node.observeDirectPeer(event.deviceId, 0, "ble", System.currentTimeMillis())
                setStatus("Trusted BLE mesh peer: ${event.deviceId}")
                renderRoutes()
            }
            is SecureTransportEvent.MessageReceived -> setStatus("Direct encrypted chat message received")
            is SecureTransportEvent.DeliveryConfirmed -> setStatus("Direct message receipt verified")
            is SecureTransportEvent.Disconnected -> {
                trustedDeviceId?.let(node::peerUnavailable)
                trustedDeviceId = null
                advertiseButton.isEnabled = false
                probeButton.isEnabled = false
                largeProbeButton.isEnabled = false
                setStatus("Peer disconnected")
                renderRoutes()
            }
            is SecureTransportEvent.Error -> setStatus(event.message)
        }
    }

    private fun trustPending() {
        val deviceId = pendingDeviceId ?: return
        if (transport.trustPeer(deviceId)) {
            setStatus("Trust stored; secure mesh route can now be established")
        } else {
            setStatus("Unable to trust pending peer")
        }
    }

    private fun advertiseRoute() {
        val peerId = trustedDeviceId ?: return
        val wire = endpoint.createRouteWire(peerId, System.currentTimeMillis())
        val sent = transport.sendMeshRouteWire(peerId, wire)
        setStatus(if (sent) "Signed TMA1 route advertisement sent (${wire.size} bytes)" else "Route advertisement could not be sent")
    }

    private fun sendRelayProbe(payloadBytes: Int) {
        val firstHop = trustedDeviceId ?: return
        val target = targetInput.text.toString().trim()
        if (target.isEmpty()) {
            setStatus("Enter a target Telemetry device ID")
            return
        }
        bindWifiForTrustedPeer()
        val now = System.currentTimeMillis()
        val opaque = ByteArray(payloadBytes).also(SecureRandom()::nextBytes)
        val frame = OpaqueRelayFrame(
            header = MeshRelayHeader(
                messageId = "lab-${UUID.randomUUID()}",
                senderId = identity.deviceId,
                recipientId = target,
                hopCount = 0,
                hopLimit = 4,
                relayPath = listOf(identity.deviceId),
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 60_000L
            ),
            encodedEnvelope = opaque
        )
        val result = node.sendOpaqueToNextHop(firstHop, frame)
        setStatus(
            if (result.sent) {
                "Opaque relay probe sent via ${result.transportId} (${payloadBytes} payload bytes)"
            } else {
                "Relay probe not sent: ${result.reason}"
            }
        )
    }

    private fun bindWifiForTrustedPeer(): Boolean {
        val peerId = trustedDeviceId ?: return false
        val capabilities = node.peerCapabilities(peerId, System.currentTimeMillis())
        return wifiPort.bindPeerCapabilities(peerId, capabilities)
    }

    private fun renderRoutes() {
        val now = System.currentTimeMillis()
        val wifiBound = bindWifiForTrustedPeer()
        val peerId = trustedDeviceId
        val wifiState = if (peerId != null && wifiBound) wifiPort.peerState(peerId) else null
        val routes = node.routeSnapshot(now)
        val routeText = if (routes.isEmpty()) {
            "No active routes"
        } else {
            routes.joinToString("\n") {
                "${it.destinationId} via ${it.viaPeerId} · ${it.hops} hop(s) · ${it.transport} · q=${it.quality}"
            }
        }
        routesView.text = buildString {
            append(routeText)
            append("\n\nWi-Fi local: ")
            append(
                when {
                    peerId == null -> "no trusted peer"
                    !wifiBound -> "waiting for signed peer capability"
                    wifiState?.available == true -> "available"
                    else -> "capability verified; waiting for mDNS endpoint"
                }
            )
        }
    }

    private fun setStatus(value: String) {
        statusView.text = value
    }
}
