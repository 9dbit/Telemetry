package com.telemetry.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telemetry.app.crypto.AndroidIdentityStore
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.discovery.DiscoveryEvent
import com.telemetry.app.discovery.PeerCandidate
import com.telemetry.app.discovery.TelemetryBleDiscovery
import com.telemetry.app.transport.SecureTransportEvent
import com.telemetry.app.transport.TelemetryGattTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF07111F)
private val InkRaised = Color(0xFF0D1B2C)
private val InkSoft = Color(0xFF13243A)
private val TelemetryBlue = Color(0xFF2F86FF)
private val TelemetryCyan = Color(0xFF38E2C2)
private val TextPrimary = Color(0xFFF4F8FF)
private val TextSecondary = Color(0xFF9DAFC7)
private val Divider = Color(0xFF20344D)
private val DangerSoft = Color(0xFF3A2230)

private val TelemetryScheme = darkColorScheme(
    primary = TelemetryBlue,
    secondary = TelemetryCyan,
    background = Ink,
    surface = InkRaised,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private enum class AppScreen { HOME, DISCOVER, VERIFY, CHAT }

data class ChatMessage(
    val text: String,
    val outgoing: Boolean,
    val time: String,
    val delivered: Boolean = false
)

class MainActivity : ComponentActivity() {
    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: AndroidTrustStore
    private var discovery: TelemetryBleDiscovery? = null
    private var transport: TelemetryGattTransport? = null
    private var uiState by mutableStateOf(M1BUiState())
    private var screen by mutableStateOf(AppScreen.HOME)

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
            MaterialTheme(colorScheme = TelemetryScheme) {
                TelemetryApp(
                    screen = screen,
                    state = uiState,
                    onStart = ::openDiscovery,
                    onStop = ::stopDiscovery,
                    onConnect = { transport?.connect(it.radioAddress) },
                    onTrust = ::trustPending,
                    onDraft = { uiState = uiState.copy(draft = it) },
                    onSend = ::sendDraft,
                    onDiscover = { screen = AppScreen.DISCOVER },
                    onChat = { if (uiState.connectedAddress != null) screen = AppScreen.CHAT },
                    onHome = { screen = AppScreen.HOME }
                )
            }
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        transport?.stop()
        super.onDestroy()
    }

    private fun openDiscovery() {
        screen = AppScreen.DISCOVER
        ensurePermissionsAndStart()
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
        uiState = uiState.copy(status = "Scanning nearby Telemetry devices…")
        discovery = TelemetryBleDiscovery(this) { event -> runOnUiThread { reduceDiscovery(event) } }
            .also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        uiState = uiState.copy(status = "Discovery paused · secure sessions stay active")
    }

    private fun trustPending() {
        val deviceId = uiState.pendingDeviceId ?: return
        if (transport?.trustPeer(deviceId) == true) {
            uiState = uiState.copy(status = "Trust confirmed · establishing encrypted channel…")
        }
    }

    private fun sendDraft() {
        val address = uiState.connectedAddress ?: return
        val text = uiState.draft.trim()
        if (text.isEmpty()) return
        if (transport?.sendMessage(address, text) == true) {
            uiState = uiState.copy(
                draft = "",
                messages = uiState.messages + ChatMessage(
                    text = text,
                    outgoing = true,
                    time = nowLabel()
                ),
                status = "Encrypted message sent · waiting for signed receipt"
            )
        }
    }

    private fun reduceDiscovery(event: DiscoveryEvent) {
        uiState = when (event) {
            is DiscoveryEvent.Started -> uiState.copy(
                status = if (event.advertising) {
                    "Offline discovery active · scanning + advertising"
                } else {
                    "Scanning active · advertising unavailable on this device"
                }
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
            is SecureTransportEvent.Connecting -> uiState.copy(status = "Connecting securely to nearby peer…")
            is SecureTransportEvent.PendingVerification -> {
                screen = AppScreen.VERIFY
                uiState.copy(
                    status = "Compare the safety code on both phones",
                    pendingDeviceId = event.deviceId,
                    pendingAddress = event.address,
                    safetyCode = event.safetyCode
                )
            }
            is SecureTransportEvent.SessionTrusted -> {
                screen = AppScreen.CHAT
                uiState.copy(
                    status = "Offline · Encrypted",
                    connectedAddress = event.address,
                    trustedDeviceId = event.deviceId,
                    pendingDeviceId = null,
                    pendingAddress = null,
                    safetyCode = null
                )
            }
            is SecureTransportEvent.MessageReceived -> uiState.copy(
                status = "Offline · Encrypted",
                connectedAddress = event.address,
                trustedDeviceId = event.deviceId,
                messages = uiState.messages + ChatMessage(
                    text = event.text,
                    outgoing = false,
                    time = nowLabel(),
                    delivered = true
                )
            )
            is SecureTransportEvent.DeliveryConfirmed -> uiState.copy(
                status = "✓ Delivered · signed receipt verified",
                messages = markLatestOutgoingDelivered(uiState.messages)
            )
            is SecureTransportEvent.Disconnected -> uiState.copy(
                status = "Peer disconnected · message queue remains local",
                connectedAddress = uiState.connectedAddress.takeUnless { it == event.address }
            )
            is SecureTransportEvent.Error -> uiState.copy(status = event.message)
        }
    }

    private fun markLatestOutgoingDelivered(messages: List<ChatMessage>): List<ChatMessage> {
        val index = messages.indexOfLast { it.outgoing && !it.delivered }
        if (index < 0) return messages
        return messages.mapIndexed { i, message ->
            if (i == index) message.copy(delivered = true) else message
        }
    }

    private fun nowLabel(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

data class M1BUiState(
    val localDeviceId: String = "",
    val status: String = "Ready for secure offline communication",
    val peers: List<PeerCandidate> = emptyList(),
    val pendingDeviceId: String? = null,
    val pendingAddress: String? = null,
    val safetyCode: String? = null,
    val connectedAddress: String? = null,
    val trustedDeviceId: String? = null,
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList()
)

@Composable
private fun TelemetryApp(
    screen: AppScreen,
    state: M1BUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: (PeerCandidate) -> Unit,
    onTrust: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onDiscover: () -> Unit,
    onChat: () -> Unit,
    onHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF09182A), Ink, Color(0xFF050B13))
                )
            )
    ) {
        when (screen) {
            AppScreen.HOME -> HomeScreen(state, onStart)
            AppScreen.DISCOVER -> DiscoverScreen(state, onStop, onConnect, onDiscover, onChat, onHome)
            AppScreen.VERIFY -> VerifyScreen(state, onTrust) { onDiscover() }
            AppScreen.CHAT -> ChatScreen(state, onDraft, onSend, onDiscover, onChat, onHome)
        }
    }
}

@Composable
private fun HomeScreen(state: M1BUiState, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            StatusChip("● Offline", TelemetryCyan)
            Spacer(Modifier.width(8.dp))
            StatusChip("▣ Encrypted", TelemetryBlue)
            Spacer(Modifier.width(8.dp))
            StatusChip("⌁ No Internet", TextSecondary)
        }

        Spacer(Modifier.height(76.dp))
        TelemetryMark(92)
        Spacer(Modifier.height(20.dp))
        Text(
            "Telemetry",
            color = TextPrimary,
            fontSize = 42.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Secure offline communication\nfor a more connected world.",
            color = TextSecondary,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(42.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FeatureMini("◎", "Find", "nearby people")
            FeatureMini("▣", "End-to-end", "encrypted")
            FeatureMini("⌁", "Works", "without internet")
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton("Start Offline  →", onStart, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Text(
            "REAL CONNECTIONS STILL MATTER",
            color = Color(0xFF6F839D),
            fontSize = 10.sp,
            letterSpacing = 2.4.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Device ${state.localDeviceId.takeLast(8)}",
            color = Color(0xFF50657F),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun DiscoverScreen(
    state: M1BUiState,
    onStop: () -> Unit,
    onConnect: (PeerCandidate) -> Unit,
    onDiscover: () -> Unit,
    onChat: () -> Unit,
    onHome: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Discover",
                chatEnabled = state.connectedAddress != null,
                onDiscover = onDiscover,
                onChat = onChat,
                onHome = onHome
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text("Discover Peers", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Find nearby devices using Bluetooth.\nNo internet required.", color = TextSecondary, fontSize = 14.sp)

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = InkSoft),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).background(Color(0xFF12345C), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("ᛒ", color = TelemetryBlue, fontSize = 21.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Scanning for nearby devices…", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(state.status, color = TextSecondary, fontSize = 12.sp)
                    }
                    Box(
                        modifier = Modifier.size(12.dp).background(TelemetryCyan, CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nearby peers", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Pause", color = TelemetryBlue, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            }
            Spacer(Modifier.height(8.dp))

            if (state.peers.isEmpty()) {
                EmptyDiscoveryCard()
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.peers, key = { it.radioAddress }) { peer ->
                        PeerCard(peer, onConnect)
                    }
                }
            }

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextSecondary),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Stop discovery") }
        }
    }
}

@Composable
private fun VerifyScreen(state: M1BUiState, onTrust: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("‹", color = TextPrimary, fontSize = 34.sp)
        }
        Spacer(Modifier.height(22.dp))
        Text("Verify Identity", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Compare this code with your peer\nto ensure a secure connection.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(36.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            DeviceCircle("▯", "This device", state.localDeviceId.takeLast(6))
            Text("⇄", color = TelemetryCyan, fontSize = 32.sp)
            DeviceCircle("▯", "Peer device", state.pendingDeviceId?.takeLast(6) ?: "peer")
        }

        Spacer(Modifier.height(34.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InkRaised),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SAFETY CODE", color = TextSecondary, fontSize = 10.sp, letterSpacing = 2.4.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    formatSafetyCode(state.safetyCode ?: "------"),
                    color = TelemetryCyan,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "If the code matches on both devices,\nyou can trust this peer.",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onTrust,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan, contentColor = Ink)
        ) {
            Text("✓  Codes match · Trust peer", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TelemetryBlue)
        ) { Text("Cancel") }
    }
}

@Composable
private fun ChatScreen(
    state: M1BUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onDiscover: () -> Unit,
    onChat: () -> Unit,
    onHome: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Chat",
                chatEnabled = true,
                onDiscover = onDiscover,
                onChat = onChat,
                onHome = onHome
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = TextPrimary, fontSize = 32.sp)
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(42.dp).background(Color(0xFF243751), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("P", color = TextPrimary, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.trustedDeviceId?.let { "peer-${it.takeLast(4)}" } ?: "secure peer",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).background(TelemetryCyan, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text("Offline · Encrypted", color = TelemetryCyan, fontSize = 12.sp)
                    }
                }
                Text("⋮", color = TextSecondary, fontSize = 26.sp)
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

            if (state.messages.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(64.dp).background(Color(0xFF10334C), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("▣", color = TelemetryCyan, fontSize = 27.sp) }
                    Spacer(Modifier.height(16.dp))
                    Text("Secure offline channel ready", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Messages are encrypted on-device and sent directly over the local radio link.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            "Today",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    items(state.messages) { message -> MessageBubble(message) }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                if (state.status.contains("Delivered")) {
                    Text(
                        "✓ Delivered · signed receipt verified",
                        color = TelemetryCyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = onDraft,
                        placeholder = { Text("Type a message…", color = TextSecondary) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = InkRaised,
                            unfocusedContainerColor = InkRaised,
                            focusedBorderColor = Divider,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        maxLines = 3
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onSend,
                        enabled = state.draft.isNotBlank() && state.connectedAddress != null,
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        contentPadding = ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
                    ) { Text("➤", color = Color.White, fontSize = 20.sp) }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .background(Color(0xFF132238), RoundedCornerShape(30.dp))
            .border(1.dp, Color(0xFF1E3450), RoundedCornerShape(30.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TelemetryMark(sizeDp: Int) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val stroke = size.width * 0.17f
        drawLine(
            color = TelemetryBlue,
            start = Offset(size.width * 0.20f, size.height * 0.75f),
            end = Offset(size.width * 0.50f, size.height * 0.22f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = TelemetryCyan,
            start = Offset(size.width * 0.50f, size.height * 0.22f),
            end = Offset(size.width * 0.80f, size.height * 0.75f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF1AB5C8),
            start = Offset(size.width * 0.30f, size.height * 0.75f),
            end = Offset(size.width * 0.70f, size.height * 0.75f),
            strokeWidth = stroke * 0.72f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun FeatureMini(icon: String, title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Text(icon, color = TelemetryBlue, fontSize = 25.sp)
        Spacer(Modifier.height(7.dp))
        Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TextSecondary, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyDiscoveryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("◌", color = TelemetryBlue, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text("Looking for Telemetry peers", color = TextPrimary, fontWeight = FontWeight.Medium)
            Text("Keep Bluetooth on. No internet connection is required.", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PeerCard(peer: PeerCandidate, onConnect: (PeerCandidate) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(Color(0xFF1B2F48), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) { Text("▯", color = TextSecondary, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.ephemeralId, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(TelemetryCyan, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("Nearby · ${peer.rssi} dBm", color = TextSecondary, fontSize = 11.sp)
                }
            }
            Button(
                onClick = { onConnect(peer) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
            ) { Text("Connect  ›", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DeviceCircle(icon: String, label: String, detail: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(74.dp).background(Color(0xFF12324F), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(icon, color = TelemetryBlue, fontSize = 30.sp) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(detail, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .background(
                        if (message.outgoing) TelemetryBlue else InkSoft,
                        RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Column {
                    Text(message.text, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (message.outgoing && message.delivered) "${message.time}  ✓✓" else message.time,
                        color = if (message.outgoing) Color(0xFFD8E7FF) else TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
            if (message.outgoing && message.delivered) {
                Text(
                    "Delivered · signed receipt verified",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryBottomBar(
    active: String,
    chatEnabled: Boolean,
    onDiscover: () -> Unit,
    onChat: () -> Unit,
    onHome: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xEE081421)).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BottomItem("◎", "Discover", active == "Discover", true, onDiscover)
        BottomItem("◫", "Chat", active == "Chat", chatEnabled, onChat)
        BottomItem("◇", "Device", false, true, onHome)
    }
}

@Composable
private fun BottomItem(icon: String, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (active) TelemetryBlue else TextSecondary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0xFF44566D)
        ),
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 20.sp)
            Text(label, fontSize = 10.sp)
        }
    }
}

private fun formatSafetyCode(code: String): String {
    val clean = code.filter { it.isLetterOrDigit() }
    return if (clean.length >= 6) "${clean.take(3)} ${clean.drop(3).take(3)}" else code
}
