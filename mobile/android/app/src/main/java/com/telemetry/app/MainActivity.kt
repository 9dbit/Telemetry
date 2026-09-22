package com.telemetry.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telemetry.app.crypto.AndroidIdentityStore
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.discovery.DiscoveryEvent
import com.telemetry.app.discovery.PeerCandidate
import com.telemetry.app.discovery.TelemetryBleDiscovery
import com.telemetry.app.transport.SecureTransportEvent
import com.telemetry.app.transport.TelemetryGattTransport

class MainActivity : ComponentActivity() {
    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: AndroidTrustStore
    private var discovery: TelemetryBleDiscovery? = null
    private var transport: TelemetryGattTransport? = null
    private var uiState by mutableStateOf(M1BUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startDiscovery()
        else uiState = uiState.copy(status = "Nearby devices permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = AndroidIdentityStore(this).getOrCreate()
        trustStore = AndroidTrustStore(this)
        uiState = uiState.copy(localDeviceId = identity.deviceId)
        transport = TelemetryGattTransport(this, identity, trustStore) { event ->
            runOnUiThread { reduceSecure(event) }
        }

        setContent {
            MaterialTheme {
                TelemetryM1BScreen(
                    state = uiState,
                    onStart = ::ensurePermissionsAndStart,
                    onStop = ::stopDiscovery,
                    onConnect = { transport?.connect(it.radioAddress) },
                    onTrust = ::trustPending,
                    onDraft = { uiState = uiState.copy(draft = it) },
                    onSend = ::sendDraft
                )
            }
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        transport?.stop()
        super.onDestroy()
    }

    private fun ensurePermissionsAndStart() {
        val required = requiredPermissions()
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startDiscovery()
        else permissionLauncher.launch(required)
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
        transport?.start()
        uiState = uiState.copy(status = "Starting M1B offline discovery...")
        discovery = TelemetryBleDiscovery(this) { event -> runOnUiThread { reduceDiscovery(event) } }
            .also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        uiState = uiState.copy(status = "Discovery stopped; secure sessions remain active")
    }

    private fun trustPending() {
        val deviceId = uiState.pendingDeviceId ?: return
        if (transport?.trustPeer(deviceId) == true) {
            uiState = uiState.copy(pendingDeviceId = null, safetyCode = null)
        }
    }

    private fun sendDraft() {
        val address = uiState.connectedAddress ?: return
        val text = uiState.draft.trim()
        if (text.isEmpty()) return
        if (transport?.sendMessage(address, text) == true) {
            uiState = uiState.copy(
                draft = "",
                messages = uiState.messages + "Me: $text",
                status = "Encrypted message sent; awaiting signed receipt..."
            )
        }
    }

    private fun reduceDiscovery(event: DiscoveryEvent) {
        uiState = when (event) {
            is DiscoveryEvent.Started -> uiState.copy(
                status = if (event.advertising) "M1B active: scanning + connectable BLE" else "Scanning active"
            )
            is DiscoveryEvent.PeerSeen -> {
                val merged = (uiState.peers + event.peer)
                    .associateBy { it.radioAddress }
                    .values
                    .sortedByDescending { it.rssi }
                uiState.copy(peers = merged, status = "${merged.size} Telemetry peer(s) nearby")
            }
            is DiscoveryEvent.Error -> uiState.copy(status = event.message)
            DiscoveryEvent.Stopped -> uiState.copy(status = "Discovery stopped")
        }
    }

    private fun reduceSecure(event: SecureTransportEvent) {
        uiState = when (event) {
            is SecureTransportEvent.Connecting -> uiState.copy(status = "Connecting to nearby Telemetry peer...")
            is SecureTransportEvent.PendingVerification -> uiState.copy(
                status = "Compare this safety code on both phones before trusting",
                pendingDeviceId = event.deviceId,
                pendingAddress = event.address,
                safetyCode = event.safetyCode
            )
            is SecureTransportEvent.SessionTrusted -> uiState.copy(
                status = "Secure offline session trusted",
                connectedAddress = event.address,
                trustedDeviceId = event.deviceId,
                pendingDeviceId = null,
                pendingAddress = null,
                safetyCode = null
            )
            is SecureTransportEvent.MessageReceived -> uiState.copy(
                status = "Encrypted message received",
                connectedAddress = event.address,
                trustedDeviceId = event.deviceId,
                messages = uiState.messages + "Peer: ${event.text}"
            )
            is SecureTransportEvent.DeliveryConfirmed -> uiState.copy(
                status = "✓ Delivered · signed receipt verified"
            )
            is SecureTransportEvent.Disconnected -> uiState.copy(
                status = "Peer disconnected",
                connectedAddress = uiState.connectedAddress.takeUnless { it == event.address }
            )
            is SecureTransportEvent.Error -> uiState.copy(status = event.message)
        }
    }
}

data class M1BUiState(
    val localDeviceId: String = "",
    val status: String = "Ready for secure offline session",
    val peers: List<PeerCandidate> = emptyList(),
    val pendingDeviceId: String? = null,
    val pendingAddress: String? = null,
    val safetyCode: String? = null,
    val connectedAddress: String? = null,
    val trustedDeviceId: String? = null,
    val draft: String = "",
    val messages: List<String> = emptyList()
)

@androidx.compose.runtime.Composable
private fun TelemetryM1BScreen(
    state: M1BUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: (PeerCandidate) -> Unit,
    onTrust: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Telemetry", style = MaterialTheme.typography.headlineMedium)
            Text("M1B · Secure Offline Session")
            Text("Local ${state.localDeviceId.take(26)}…", style = MaterialTheme.typography.bodySmall)
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Start offline") }
                Button(onClick = onStop) { Text("Stop discovery") }
            }

            state.safetyCode?.let { code ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Safety code", style = MaterialTheme.typography.titleMedium)
                        Text(code, style = MaterialTheme.typography.headlineSmall)
                        Text("Verify the same code is shown on the other phone.")
                        Button(onClick = onTrust) { Text("Codes match · Trust peer") }
                    }
                }
            }

            Text("Nearby peers", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.height(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.peers, key = { it.radioAddress }) { peer ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(peer.ephemeralId)
                                Text("signal ${peer.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onConnect(peer) }) { Text("Connect") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Text("Offline chat", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.messages) { Text(it) }
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraft,
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.connectedAddress != null
            )
            Button(onClick = onSend, enabled = state.connectedAddress != null && state.draft.isNotBlank()) {
                Text("Send encrypted")
            }
        }
    }
}
