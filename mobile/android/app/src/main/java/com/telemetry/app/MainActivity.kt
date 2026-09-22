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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telemetry.app.discovery.DiscoveryEvent
import com.telemetry.app.discovery.PeerCandidate
import com.telemetry.app.discovery.TelemetryBleDiscovery

class MainActivity : ComponentActivity() {
    private var discovery: TelemetryBleDiscovery? = null
    private var uiState by mutableStateOf(DiscoveryUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startDiscovery()
        else uiState = uiState.copy(status = "Nearby devices permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TelemetryM1AScreen(
                    state = uiState,
                    onStart = ::ensurePermissionsAndStart,
                    onStop = ::stopDiscovery
                )
            }
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }

    private fun ensurePermissionsAndStart() {
        val required = requiredPermissions()
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startDiscovery()
        } else {
            permissionLauncher.launch(required)
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        uiState = DiscoveryUiState(status = "Starting offline discovery...")
        discovery = TelemetryBleDiscovery(this) { event ->
            runOnUiThread { reduce(event) }
        }.also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        uiState = uiState.copy(status = "Discovery stopped")
    }

    private fun reduce(event: DiscoveryEvent) {
        uiState = when (event) {
            is DiscoveryEvent.Started -> uiState.copy(
                status = if (event.advertising) {
                    "Offline discovery active: scanning + advertising"
                } else {
                    "Scanning active; this device cannot advertise BLE"
                }
            )
            is DiscoveryEvent.PeerSeen -> {
                val merged = (uiState.peers + event.peer)
                    .associateBy { it.ephemeralId }
                    .values
                    .sortedByDescending { it.rssi }
                uiState.copy(peers = merged, status = "${merged.size} Telemetry peer(s) nearby")
            }
            is DiscoveryEvent.Error -> uiState.copy(status = event.message)
            DiscoveryEvent.Stopped -> uiState.copy(status = "Discovery stopped")
        }
    }
}

data class DiscoveryUiState(
    val status: String = "Ready for M1A offline discovery",
    val peers: List<PeerCandidate> = emptyList()
)

@androidx.compose.runtime.Composable
private fun TelemetryM1AScreen(
    state: DiscoveryUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Telemetry", style = MaterialTheme.typography.headlineMedium)
            Text("M1A · Offline Peer Discovery")
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Start discovery") }
                Button(onClick = onStop) { Text("Stop") }
            }
            Spacer(Modifier.height(4.dp))
            Text("Nearby peers", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.peers, key = { it.ephemeralId }) { peer ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(peer.ephemeralId)
                            Text("signal ${peer.rssi} dBm")
                        }
                    }
                }
            }
        }
    }
}
