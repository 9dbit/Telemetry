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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telemetry.app.call.AndroidCallAppRuntime
import com.telemetry.app.call.NativeCallRuntimeEvent
import com.telemetry.app.crypto.AndroidIdentityStore
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.discovery.DiscoveryEvent
import com.telemetry.app.discovery.PeerCandidate
import com.telemetry.app.discovery.TelemetryBleDiscovery
import com.telemetry.app.media.AndroidMediaAppRuntime
import com.telemetry.app.media.NativeMediaTransferEvent
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
private val SosRed = Color(0xFFE43D4F)
private val SosRedDark = Color(0xFF35171F)

private val TelemetryScheme = darkColorScheme(
    primary = TelemetryBlue,
    secondary = TelemetryCyan,
    background = Ink,
    surface = InkRaised,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private enum class AppScreen { MESSAGES, NEARBY, VERIFY, CHAT, NETWORK, SETTINGS, SOS }

data class ChatMessage(
    val text: String,
    val outgoing: Boolean,
    val time: String,
    val delivered: Boolean = false,
    val emergency: Boolean = false
)

class MainActivity : ComponentActivity() {
    private lateinit var identity: DeviceIdentity
    private lateinit var trustStore: AndroidTrustStore
    private var discovery: TelemetryBleDiscovery? = null
    private var transport: TelemetryGattTransport? = null
    private var mediaRuntime: AndroidMediaAppRuntime? = null
    private var callRuntime: AndroidCallAppRuntime? = null
    private var pendingVoiceAction: String? = null
    private var pendingVoicePeerId: String? = null
    private var uiState by mutableStateOf(M1BUiState())
    private var screen by mutableStateOf(AppScreen.MESSAGES)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startDiscovery()
        else uiState = uiState.copy(
            status = "Nearby devices permission denied",
            discoveryActive = false,
            advertising = false
        )
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingVoiceAction
        val peerId = pendingVoicePeerId
        pendingVoiceAction = null
        pendingVoicePeerId = null
        if (!granted) {
            if (action == "accept") callRuntime?.declineIncoming("microphone-permission-denied")
            uiState = uiState.copy(
                callState = "idle",
                callStatus = "Microphone permission is required for a live voice call."
            )
        } else {
            when (action) {
                "start" -> peerId?.let(::startVoiceCallNow)
                "accept" -> acceptVoiceCallNow()
            }
        }
    }

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val peerId = uiState.trustedDeviceId
        if (uri != null && peerId != null) {
            uiState = uiState.copy(mediaStatus = "Preparing encrypted attachment…")
            mediaRuntime?.queueUri(
                uri = uri,
                peerId = peerId,
                conversationId = "chat:$peerId"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = AndroidIdentityStore(this).getOrCreate()
        trustStore = AndroidTrustStore(this)
        uiState = uiState.copy(localDeviceId = identity.deviceId)
        val activeTransport = TelemetryGattTransport(this, identity, trustStore) { event ->
            runOnUiThread { reduceSecure(event) }
        }
        transport = activeTransport
        val sharedRuntime = AndroidMediaAppRuntime(
            context = this,
            identity = identity,
            trustStore = trustStore,
            gattTransport = activeTransport
        ) { event ->
            runOnUiThread { reduceMedia(event) }
        }.also { it.start() }
        mediaRuntime = sharedRuntime
        callRuntime = AndroidCallAppRuntime(
            context = this,
            identity = identity,
            sharedRuntime = sharedRuntime
        ) { event ->
            runOnUiThread { reduceCall(event) }
        }

        setContent {
            MaterialTheme(colorScheme = TelemetryScheme) {
                TelemetryAppV2(
                    screen = screen,
                    state = uiState,
                    onMessages = { screen = AppScreen.MESSAGES },
                    onNearby = ::openNearby,
                    onNetwork = { screen = AppScreen.NETWORK },
                    onSettings = { screen = AppScreen.SETTINGS },
                    onSos = { screen = AppScreen.SOS },
                    onStopDiscovery = ::stopDiscovery,
                    onScanAgain = ::ensurePermissionsAndStart,
                    onConnect = { transport?.connect(it.radioAddress) },
                    onTrust = ::trustPending,
                    onOpenChat = { if (uiState.connectedAddress != null) screen = AppScreen.CHAT },
                    onDraft = { uiState = uiState.copy(draft = it) },
                    onSend = ::sendDraft,
                    onAttach = ::pickMedia,
                    onStartVoiceCall = ::startVoiceCall,
                    onAcceptVoiceCall = ::acceptVoiceCall,
                    onDeclineVoiceCall = ::declineVoiceCall,
                    onHangupVoiceCall = ::hangupVoiceCall,
                    onToggleVoiceMute = ::toggleVoiceMute,
                    onToggleVoiceSpeaker = ::toggleVoiceSpeaker,
                    onNearbyQuery = { uiState = uiState.copy(nearbyQuery = it) },
                    onStrongOnly = { uiState = uiState.copy(strongOnly = it) },
                    onToggleDiagnostics = {
                        uiState = uiState.copy(showDiagnostics = !uiState.showDiagnostics)
                    },
                    onTriggerSos = ::sendEmergency
                )
            }
        }
    }

    override fun onDestroy() {
        discovery?.stop()
        callRuntime?.stop()
        mediaRuntime?.stop()
        transport?.stop()
        super.onDestroy()
    }

    private fun openNearby() {
        screen = AppScreen.NEARBY
        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val required = requiredPermissions()
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startDiscovery()
        } else {
            permissionLauncher.launch(required)
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
        transport?.start()
        uiState = uiState.copy(
            status = "Scanning nearby Telemetry devices…",
            discoveryActive = true
        )
        discovery = TelemetryBleDiscovery(this) { event ->
            runOnUiThread { reduceDiscovery(event) }
        }.also { it.start() }
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        uiState = uiState.copy(
            status = "Discovery paused · secure sessions stay active",
            discoveryActive = false,
            advertising = false
        )
    }

    private fun trustPending() {
        val deviceId = uiState.pendingDeviceId ?: return
        if (transport?.trustPeer(deviceId) == true) {
            uiState = uiState.copy(status = "Trust confirmed · establishing encrypted channel…")
        }
    }

    private fun pickMedia() {
        if (uiState.trustedDeviceId == null) {
            uiState = uiState.copy(mediaStatus = "Connect to a trusted peer before sending media.")
            return
        }
        mediaPicker.launch(
            arrayOf(
                "image/*",
                "video/*",
                "application/pdf",
                "application/octet-stream"
            )
        )
    }

    private fun startVoiceCall() {
        val peerId = uiState.trustedDeviceId ?: run {
            uiState = uiState.copy(callStatus = "Connect to a trusted peer before starting a voice call.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceAction = "start"
            pendingVoicePeerId = peerId
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceCallNow(peerId)
    }

    private fun startVoiceCallNow(peerId: String) {
        if (callRuntime?.startOutgoing(peerId) == null && uiState.callStatus == null) {
            uiState = uiState.copy(callStatus = "Unable to start a direct voice call.")
        }
    }

    private fun acceptVoiceCall() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceAction = "accept"
            pendingVoicePeerId = uiState.callPeerId
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        acceptVoiceCallNow()
    }

    private fun acceptVoiceCallNow() {
        if (callRuntime?.acceptIncoming() != true) {
            uiState = uiState.copy(callStatus = "Unable to accept the current voice call.")
        }
    }

    private fun declineVoiceCall() {
        callRuntime?.declineIncoming("declined")
    }

    private fun hangupVoiceCall() {
        callRuntime?.hangup("local-hangup")
    }

    private fun toggleVoiceMute() {
        val next = !uiState.callMuted
        if (callRuntime?.setMuted(next) == true) uiState = uiState.copy(callMuted = next)
    }

    private fun toggleVoiceSpeaker() {
        val next = !uiState.callSpeakerEnabled
        if (callRuntime?.setSpeakerEnabled(next) == true) {
            uiState = uiState.copy(callSpeakerEnabled = next)
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

    private fun sendEmergency() {
        val address = uiState.connectedAddress
        if (address == null) {
            uiState = uiState.copy(
                sosStatus = "No trusted peer connected. Open Nearby and connect before sending SOS."
            )
            return
        }

        val text = "SOS · Emergency assistance requested"
        if (transport?.sendMessage(address, text) == true) {
            uiState = uiState.copy(
                messages = uiState.messages + ChatMessage(
                    text = text,
                    outgoing = true,
                    time = nowLabel(),
                    emergency = true
                ),
                status = "SOS encrypted alert sent · waiting for signed receipt",
                sosStatus = "Encrypted SOS sent to the active trusted peer.",
                sosSentAt = nowLabel()
            )
        } else {
            uiState = uiState.copy(
                sosStatus = "Unable to send SOS on the current direct route."
            )
        }
    }

    private fun reduceDiscovery(event: DiscoveryEvent) {
        uiState = when (event) {
            is DiscoveryEvent.Started -> uiState.copy(
                discoveryActive = true,
                advertising = event.advertising,
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
                uiState.copy(
                    peers = merged,
                    status = "${merged.size} Telemetry peer(s) nearby"
                )
            }
            is DiscoveryEvent.Error -> uiState.copy(
                status = event.message,
                discoveryActive = false
            )
            DiscoveryEvent.Stopped -> uiState.copy(
                status = "Discovery stopped",
                discoveryActive = false,
                advertising = false
            )
        }
    }

    private fun reduceSecure(event: SecureTransportEvent) {
        uiState = when (event) {
            is SecureTransportEvent.Connecting -> uiState.copy(
                status = "Connecting securely to nearby peer…"
            )
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
                mediaRuntime?.observeTrustedPeer(event.deviceId)
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
                    delivered = true,
                    emergency = event.text.startsWith("SOS")
                )
            )
            is SecureTransportEvent.DeliveryConfirmed -> uiState.copy(
                status = "✓ Delivered · signed receipt verified",
                messages = markLatestOutgoingDelivered(uiState.messages)
            )
            is SecureTransportEvent.Disconnected -> {
                uiState.trustedDeviceId?.let { mediaRuntime?.peerUnavailable(it) }
                uiState.copy(
                    status = "Peer disconnected · message queue remains local",
                    connectedAddress = uiState.connectedAddress.takeUnless { it == event.address }
                )
            }
            is SecureTransportEvent.Error -> uiState.copy(status = event.message)
        }
    }

    private fun reduceMedia(event: NativeMediaTransferEvent) {
        uiState = when (event) {
            is NativeMediaTransferEvent.Preparing -> uiState.copy(
                mediaStatus = "Encrypting ${event.displayName}…"
            )
            is NativeMediaTransferEvent.OutgoingQueued -> uiState.copy(
                mediaStatus = "Queued ${event.fileName} · ${event.totalChunks} encrypted chunk(s)"
            )
            is NativeMediaTransferEvent.OutgoingProgress -> uiState.copy(
                mediaStatus = buildString {
                    append("Sending encrypted attachment · ")
                    append(event.acknowledgedChunks)
                    append('/')
                    append(event.totalChunks)
                    event.transport?.let { append(" · ").append(it) }
                }
            )
            is NativeMediaTransferEvent.OutgoingComplete -> uiState.copy(
                mediaStatus = "Attachment delivered and verified",
                messages = uiState.messages + ChatMessage(
                    text = "📎 ${event.fileName}",
                    outgoing = true,
                    time = nowLabel(),
                    delivered = true
                )
            )
            is NativeMediaTransferEvent.IncomingProgress -> uiState.copy(
                mediaStatus = "Receiving ${event.fileName} · ${event.receivedChunks}/${event.totalChunks}"
            )
            is NativeMediaTransferEvent.IncomingReady -> {
                mediaRuntime?.materializeIncomingToCache(event.assetId)
                uiState.copy(
                    mediaStatus = "Received attachment verified",
                    messages = uiState.messages + ChatMessage(
                        text = "📎 ${event.fileName}",
                        outgoing = false,
                        time = nowLabel(),
                        delivered = true
                    )
                )
            }
            is NativeMediaTransferEvent.Error -> uiState.copy(
                mediaStatus = event.message
            )
        }
    }

    private fun reduceCall(event: NativeCallRuntimeEvent) {
        uiState = when (event) {
            is NativeCallRuntimeEvent.OutgoingRinging -> uiState.copy(
                callState = "outgoing-ringing",
                callPeerId = event.peerId,
                callStatus = "Calling nearby peer…",
                callMuted = false,
                callSpeakerEnabled = false
            )
            is NativeCallRuntimeEvent.IncomingRinging -> {
                screen = AppScreen.CHAT
                uiState.copy(
                    callState = "incoming-ringing",
                    callPeerId = event.peerId,
                    callStatus = "Incoming encrypted-signaling voice call",
                    callMuted = false,
                    callSpeakerEnabled = false
                )
            }
            is NativeCallRuntimeEvent.Negotiating -> uiState.copy(
                callState = "negotiating",
                callPeerId = event.peerId,
                callStatus = "Connecting direct voice · ${event.transport}"
            )
            is NativeCallRuntimeEvent.Active -> uiState.copy(
                callState = "active",
                callPeerId = event.peerId,
                callStatus = "Live voice · ${event.transport} · direct P2P"
            )
            is NativeCallRuntimeEvent.Ended -> uiState.copy(
                callState = "idle",
                callPeerId = null,
                callStatus = "Call ended · ${event.reason}",
                callMuted = false,
                callSpeakerEnabled = false
            )
            is NativeCallRuntimeEvent.Unavailable -> uiState.copy(
                callState = "idle",
                callPeerId = event.peerId,
                callStatus = if (event.offerVoiceMessage) {
                    "Live voice unavailable on this route · send a voice message instead."
                } else {
                    "Live voice unavailable · ${event.reason}"
                },
                callMuted = false,
                callSpeakerEnabled = false
            )
            is NativeCallRuntimeEvent.Error -> uiState.copy(
                callStatus = "Voice call error · ${event.message}"
            )
        }
    }

    private fun markLatestOutgoingDelivered(messages: List<ChatMessage>): List<ChatMessage> {
        val index = messages.indexOfLast { it.outgoing && !it.delivered }
        if (index < 0) return messages
        return messages.mapIndexed { i, message ->
            if (i == index) message.copy(delivered = true) else message
        }
    }

    private fun nowLabel(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

data class M1BUiState(
    val localDeviceId: String = "",
    val status: String = "Offline ready · no internet required",
    val peers: List<PeerCandidate> = emptyList(),
    val pendingDeviceId: String? = null,
    val pendingAddress: String? = null,
    val safetyCode: String? = null,
    val connectedAddress: String? = null,
    val trustedDeviceId: String? = null,
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val discoveryActive: Boolean = false,
    val advertising: Boolean = false,
    val nearbyQuery: String = "",
    val strongOnly: Boolean = false,
    val showDiagnostics: Boolean = false,
    val mediaStatus: String? = null,
    val callState: String = "idle",
    val callPeerId: String? = null,
    val callStatus: String? = null,
    val callMuted: Boolean = false,
    val callSpeakerEnabled: Boolean = false,
    val sosStatus: String? = null,
    val sosSentAt: String? = null
)

@Composable
private fun TelemetryAppV2(
    screen: AppScreen,
    state: M1BUiState,
    onMessages: () -> Unit,
    onNearby: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    onSos: () -> Unit,
    onStopDiscovery: () -> Unit,
    onScanAgain: () -> Unit,
    onConnect: (PeerCandidate) -> Unit,
    onTrust: () -> Unit,
    onOpenChat: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onAcceptVoiceCall: () -> Unit,
    onDeclineVoiceCall: () -> Unit,
    onHangupVoiceCall: () -> Unit,
    onToggleVoiceMute: () -> Unit,
    onToggleVoiceSpeaker: () -> Unit,
    onNearbyQuery: (String) -> Unit,
    onStrongOnly: (Boolean) -> Unit,
    onToggleDiagnostics: () -> Unit,
    onTriggerSos: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1A2D), Ink, Color(0xFF050B13))
                )
            )
    ) {
        when (screen) {
            AppScreen.MESSAGES -> MessagesScreen(
                state = state,
                onNearby = onNearby,
                onOpenChat = onOpenChat,
                onSos = onSos,
                onMessages = onMessages,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
            AppScreen.NEARBY -> NearbyScreen(
                state = state,
                onStop = onStopDiscovery,
                onScanAgain = onScanAgain,
                onConnect = onConnect,
                onQuery = onNearbyQuery,
                onStrongOnly = onStrongOnly,
                onToggleDiagnostics = onToggleDiagnostics,
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
            AppScreen.VERIFY -> VerifyScreen(
                state = state,
                onTrust = onTrust,
                onCancel = onNearby
            )
            AppScreen.CHAT -> ChatScreen(
                state = state,
                onDraft = onDraft,
                onSend = onSend,
                onAttach = onAttach,
                onStartVoiceCall = onStartVoiceCall,
                onAcceptVoiceCall = onAcceptVoiceCall,
                onDeclineVoiceCall = onDeclineVoiceCall,
                onHangupVoiceCall = onHangupVoiceCall,
                onToggleVoiceMute = onToggleVoiceMute,
                onToggleVoiceSpeaker = onToggleVoiceSpeaker,
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
            AppScreen.NETWORK -> NetworkScreen(
                state = state,
                onNearby = onNearby,
                onMessages = onMessages,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
            AppScreen.SETTINGS -> SettingsScreen(
                state = state,
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
            AppScreen.SOS -> SosScreen(
                state = state,
                onBack = onMessages,
                onNearby = onNearby,
                onTrigger = onTriggerSos
            )
        }
    }
}

@Composable
private fun MessagesScreen(
    state: M1BUiState,
    onNearby: () -> Unit,
    onOpenChat: () -> Unit,
    onSos: () -> Unit,
    onMessages: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Messages",
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF16304D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    TelemetryMark(24)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Telemetry",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Offline ready",
                        color = TelemetryCyan,
                        fontSize = 11.sp
                    )
                }
                StatusChip("No Internet", TextSecondary)
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "Messages",
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Trusted conversations and emergency chats, all in one place.",
                color = TextSecondary,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(18.dp))
            StaticSearchField("Search chats")

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", true) {}
                FilterChip("Nearby ${if (state.connectedAddress != null) "1" else "0"}", false, onNearby)
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "Inbox",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            if (state.connectedAddress != null) {
                ConversationCard(state, onOpenChat)
            } else {
                EmptyInboxCard(onNearby)
            }

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onSos,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SosRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(58.dp)
                ) {
                    Text("⚠  SOS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun NearbyScreen(
    state: M1BUiState,
    onStop: () -> Unit,
    onScanAgain: () -> Unit,
    onConnect: (PeerCandidate) -> Unit,
    onQuery: (String) -> Unit,
    onStrongOnly: (Boolean) -> Unit,
    onToggleDiagnostics: () -> Unit,
    onMessages: () -> Unit,
    onNearby: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    val visiblePeers = state.peers.filter { peer ->
        val queryMatch = state.nearbyQuery.isBlank() ||
            peer.ephemeralId.contains(state.nearbyQuery, ignoreCase = true)
        val strengthMatch = !state.strongOnly || peer.rssi >= -67
        queryMatch && strengthMatch
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Nearby",
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nearby Devices",
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Find Telemetry peers without internet.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                Button(
                    onClick = if (state.discoveryActive) onStop else onScanAgain,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = TelemetryBlue
                    )
                ) {
                    Text(if (state.discoveryActive) "Stop" else "Scan")
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = state.nearbyQuery,
                onValueChange = onQuery,
                placeholder = { Text("Search by device ID…", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = InkRaised,
                    unfocusedContainerColor = InkRaised,
                    focusedBorderColor = Divider,
                    unfocusedBorderColor = Divider,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All ${state.peers.size}", !state.strongOnly) {
                    onStrongOnly(false)
                }
                FilterChip(
                    "Strong ${state.peers.count { it.rssi >= -67 }}",
                    state.strongOnly
                ) {
                    onStrongOnly(true)
                }
            }

            Spacer(Modifier.height(14.dp))
            ScannerStatusCard(state)

            Spacer(Modifier.height(14.dp))
            if (visiblePeers.isEmpty()) {
                EmptyDiscoveryCard(
                    state = state,
                    onScanAgain = onScanAgain,
                    onDiagnostics = onToggleDiagnostics
                )
            } else {
                Text(
                    "Potential Telemetry Devices",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visiblePeers, key = { it.radioAddress }) { peer ->
                        PeerCardV2(peer, onConnect)
                    }
                }
            }

            if (state.showDiagnostics) {
                Spacer(Modifier.height(10.dp))
                DiagnosticsCard(state)
            }
        }
    }
}

@Composable
private fun VerifyScreen(
    state: M1BUiState,
    onTrust: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹",
                color = TextPrimary,
                fontSize = 34.sp,
                modifier = Modifier.clickable(onClick = onCancel)
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Verify Identity",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Compare this safety code on both phones.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(36.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            DeviceCircle("▯", "This device", state.localDeviceId.takeLast(6))
            Text("⇄", color = TelemetryCyan, fontSize = 32.sp)
            DeviceCircle(
                "▯",
                "Peer device",
                state.pendingDeviceId?.takeLast(6) ?: "peer"
            )
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
                Text(
                    "SAFETY CODE",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 2.4.sp
                )
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
            "Matching codes confirm that this secure session\nhas not been silently substituted.",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onTrust,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TelemetryCyan,
                contentColor = Ink
            )
        ) {
            Text("✓  Codes match · Trust peer", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = TelemetryBlue
            )
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ChatScreen(
    state: M1BUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onAcceptVoiceCall: () -> Unit,
    onDeclineVoiceCall: () -> Unit,
    onHangupVoiceCall: () -> Unit,
    onToggleVoiceMute: () -> Unit,
    onToggleVoiceSpeaker: () -> Unit,
    onMessages: () -> Unit,
    onNearby: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Messages",
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹",
                    color = TextPrimary,
                    fontSize = 32.sp,
                    modifier = Modifier.clickable(onClick = onMessages)
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(42.dp).background(Color(0xFF243751), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("P", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peerAlias(state),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(TelemetryCyan, CircleShape)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Offline · Encrypted",
                            color = TelemetryCyan,
                            fontSize = 12.sp
                        )
                    }
                }
                if (state.callState == "idle") {
                    Button(
                        onClick = onStartVoiceCall,
                        enabled = state.trustedDeviceId != null,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = InkSoft,
                            contentColor = TelemetryCyan
                        )
                    ) {
                        Text("☎  Call", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("Voice", color = TelemetryCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))

            if (state.callState != "idle") {
                VoiceCallCard(
                    state = state,
                    onAccept = onAcceptVoiceCall,
                    onDecline = onDeclineVoiceCall,
                    onHangup = onHangupVoiceCall,
                    onMute = onToggleVoiceMute,
                    onSpeaker = onToggleVoiceSpeaker
                )
            }

            if (state.messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color(0xFF10334C), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▣", color = TelemetryCyan, fontSize = 27.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Secure offline channel ready",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
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
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
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
                    items(state.messages) { message ->
                        MessageBubble(message)
                    }
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
                state.mediaStatus?.let { status ->
                    Text(
                        status,
                        color = TelemetryBlue,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                    )
                }
                state.callStatus?.takeIf { state.callState == "idle" }?.let { status ->
                    Text(
                        status,
                        color = TelemetryCyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onAttach,
                        enabled = state.trustedDeviceId != null,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = InkRaised,
                            contentColor = TelemetryBlue
                        )
                    ) {
                        Text("+", color = TelemetryBlue, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(8.dp))
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
                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
                    ) {
                        Text("➤", color = Color.White, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallCard(
    state: M1BUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102A3D)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                state.callStatus ?: "Voice call",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.callState) {
                    "incoming-ringing" -> {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan, contentColor = Ink)
                        ) { Text("Accept", fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = onDecline,
                            colors = ButtonDefaults.buttonColors(containerColor = SosRed)
                        ) { Text("Decline") }
                    }
                    "active" -> {
                        Button(
                            onClick = onMute,
                            colors = ButtonDefaults.buttonColors(containerColor = if (state.callMuted) TelemetryBlue else InkSoft)
                        ) { Text(if (state.callMuted) "Unmute" else "Mute") }
                        Button(
                            onClick = onSpeaker,
                            colors = ButtonDefaults.buttonColors(containerColor = if (state.callSpeakerEnabled) TelemetryBlue else InkSoft)
                        ) { Text(if (state.callSpeakerEnabled) "Speaker On" else "Speaker") }
                        Button(
                            onClick = onHangup,
                            colors = ButtonDefaults.buttonColors(containerColor = SosRed)
                        ) { Text("End") }
                    }
                    else -> {
                        Button(
                            onClick = onHangup,
                            colors = ButtonDefaults.buttonColors(containerColor = SosRed)
                        ) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkScreen(
    state: M1BUiState,
    onNearby: () -> Unit,
    onMessages: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Network",
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Offline Network",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Live local transport and secure-session status.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                NetworkMetric(
                    "Bluetooth",
                    if (state.discoveryActive) "Active" else "Ready",
                    if (state.discoveryActive) TelemetryCyan else TextSecondary
                )
            }
            item {
                NetworkMetric(
                    "Nearby peers",
                    state.peers.size.toString(),
                    if (state.peers.isNotEmpty()) TelemetryCyan else TextSecondary
                )
            }
            item {
                NetworkMetric(
                    "Secure session",
                    if (state.connectedAddress != null) "Trusted" else "None",
                    if (state.connectedAddress != null) TelemetryCyan else TextSecondary
                )
            }
            item {
                NetworkMetric(
                    "Current route",
                    if (state.connectedAddress != null) "Direct BLE" else "Waiting",
                    TelemetryBlue
                )
            }
            item { NetworkMetric("Encryption", "AES-256-GCM", TelemetryBlue) }
            item { NetworkMetric("Key agreement", "X25519", TelemetryBlue) }
            item {
                PrimaryButton(
                    "Open Nearby Scanner",
                    onNearby,
                    Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: M1BUiState,
    onMessages: () -> Unit,
    onNearby: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            TelemetryBottomBar(
                active = "Settings",
                onMessages = onMessages,
                onNearby = onNearby,
                onNetwork = onNetwork,
                onSettings = onSettings
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                "Settings",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Identity, privacy and radio controls.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(18.dp))
            SettingCard(
                "Device identity",
                state.localDeviceId.ifBlank { "Generating…" }
            )
            Spacer(Modifier.height(10.dp))
            SettingCard(
                "Privacy",
                "Stable identity is never placed in BLE advertisements."
            )
            Spacer(Modifier.height(10.dp))
            SettingCard(
                "Cloud dependency",
                "None for direct offline messaging."
            )
            Spacer(Modifier.height(10.dp))
            SettingCard(
                "Build",
                "Telemetry Android 0.4.0 · UI v2"
            )
        }
    }
}

@Composable
private fun SosScreen(
    state: M1BUiState,
    onBack: () -> Unit,
    onNearby: () -> Unit,
    onTrigger: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF241017), Ink, Color(0xFF08080C))
                )
            )
            .padding(22.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = TextPrimary,
                fontSize = 34.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "SOS Broadcast",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SosRedDark),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Text(
                    "Emergency local alert",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.connectedAddress != null) {
                        "Current route: encrypted direct peer. Hold the button for 2 seconds to send."
                    } else {
                        "No trusted peer is connected. Connect to a nearby Telemetry device first."
                    },
                    color = Color(0xFFFFC7CE),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        StatusRow(
            "Secure peer",
            if (state.connectedAddress != null) peerAlias(state) else "Not connected",
            state.connectedAddress != null
        )
        StatusRow(
            "Encryption",
            if (state.connectedAddress != null) "AES-256-GCM" else "Waiting",
            state.connectedAddress != null
        )
        StatusRow(
            "Route",
            if (state.connectedAddress != null) "Direct BLE" else "Unavailable",
            state.connectedAddress != null
        )

        state.sosStatus?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                it,
                color = if (it.startsWith("Encrypted")) TelemetryCyan else Color(0xFFFFAAB4),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))

        HoldToSosButton(
            enabled = state.connectedAddress != null,
            onTriggered = onTrigger
        )

        Spacer(Modifier.height(12.dp))
        if (state.connectedAddress == null) {
            PrimaryButton(
                "Find Nearby Devices",
                onNearby,
                Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "Release before 2 seconds to cancel.",
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScannerStatusCard(state: M1BUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkSoft),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF12345C), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("ᛒ", color = TelemetryBlue, fontSize = 21.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.discoveryActive) "Scanning for devices…" else "Scanner paused",
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${state.peers.size} found · advertising ${if (state.advertising) "on" else "off"}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (state.discoveryActive) TelemetryCyan else TextSecondary,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(
    state: M1BUiState,
    onScanAgain: () -> Unit,
    onDiagnostics: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("◌", color = TextSecondary, fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "No Telemetry devices found",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            TroubleshootLine("Bluetooth", if (state.discoveryActive) "Scanning" else "Ready")
            TroubleshootLine("Permission", if (state.status.contains("permission", true)) "Check" else "Granted")
            TroubleshootLine("Advertising", if (state.advertising) "Active" else "Unavailable / paused")
            TroubleshootLine("Internet", "Not required")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onScanAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
                ) {
                    Text("Scan Again")
                }
                Button(
                    onClick = onDiagnostics,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkSoft,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Diagnostics")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: M1BUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B2233)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Network Diagnostics",
                color = TelemetryBlue,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticLine("BLE scan", if (state.discoveryActive) "active" else "stopped")
            DiagnosticLine("Advertising", if (state.advertising) "active" else "off")
            DiagnosticLine("Peers", state.peers.size.toString())
            DiagnosticLine("Secure session", if (state.connectedAddress != null) "trusted" else "none")
            DiagnosticLine("Transport", "BLE GATT")
            DiagnosticLine("Encryption", "AES-256-GCM / X25519")
        }
    }
}

@Composable
private fun ConversationCard(state: M1BUiState, onOpenChat: () -> Unit) {
    val latest = state.messages.lastOrNull()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChat),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(Color(0xFF243751), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("P", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        peerAlias(state),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        latest?.time ?: "Nearby",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    latest?.text ?: "Secure offline session ready",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "● Nearby · Direct",
                    color = TelemetryCyan,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyInboxCard(onNearby: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("◫", color = TelemetryBlue, fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "No conversations yet",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Find a nearby Telemetry device to start an encrypted offline chat.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNearby,
                colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
            ) {
                Text("Find Nearby")
            }
        }
    }
}

@Composable
private fun PeerCardV2(peer: PeerCandidate, onConnect: (PeerCandidate) -> Unit) {
    val strength = signalLabel(peer.rssi)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF1B2F48), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▯", color = TextSecondary, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer.ephemeralId,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Potential Telemetry device",
                        color = TelemetryBlue,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = { onConnect(peer) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Signal: ${strength.first}",
                    color = strength.second,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "RSSI ${peer.rssi} dBm",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Ephemeral discovery ID · stable identity hidden until secure handshake",
                color = Color(0xFF627A96),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .background(
                        when {
                            message.emergency -> SosRed
                            message.outgoing -> TelemetryBlue
                            else -> InkSoft
                        },
                        RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Column {
                    Text(
                        message.text,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (message.outgoing && message.delivered) {
                            "${message.time}  ✓✓"
                        } else {
                            message.time
                        },
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
    onMessages: () -> Unit,
    onNearby: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEE081421))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BottomItem("◫", "Messages", active == "Messages", onMessages)
        BottomItem("◎", "Nearby", active == "Nearby", onNearby)
        BottomItem("⌁", "Network", active == "Network", onNetwork)
        BottomItem("⚙", "Settings", active == "Settings", onSettings)
    }
}

@Composable
private fun BottomItem(
    icon: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF122B45) else Color.Transparent,
            contentColor = if (active) TelemetryBlue else TextSecondary
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 18.sp)
            Text(label, fontSize = 9.sp)
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF173451) else InkRaised,
            contentColor = if (active) TextPrimary else TextSecondary
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun StaticSearchField(placeholder: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(InkRaised, RoundedCornerShape(18.dp))
            .border(1.dp, Divider, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text("⌕  $placeholder", color = TextSecondary, fontSize = 14.sp)
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
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TelemetryBlue)
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun DeviceCircle(
    icon: String,
    label: String,
    detail: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(74.dp).background(Color(0xFF12324F), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = TelemetryBlue, fontSize = 30.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(detail, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun NetworkMetric(
    label: String,
    value: String,
    accent: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                value,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    detail: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HoldToSosButton(
    enabled: Boolean,
    onTriggered: () -> Unit
) {
    val background = if (enabled) SosRed else Color(0xFF5A3038)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(background, RoundedCornerShape(26.dp))
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            val startedAt = System.currentTimeMillis()
                            val released = tryAwaitRelease()
                            val heldMs = System.currentTimeMillis() - startedAt
                            if (released && heldMs >= 2000L) {
                                onTriggered()
                            }
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠", color = Color.White, fontSize = 24.sp)
            Text(
                if (enabled) "HOLD 2 SECONDS TO SEND SOS" else "CONNECT A TRUSTED PEER FIRST",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    good: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (good) TelemetryCyan else TextSecondary, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TroubleshootLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(label, color = TextSecondary, modifier = Modifier.weight(1f), fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 11.sp)
    }
}

private fun formatSafetyCode(code: String): String {
    val clean = code.filter { it.isLetterOrDigit() }
    return if (clean.length >= 6) {
        "${clean.take(3)} ${clean.drop(3).take(3)}"
    } else {
        code
    }
}

private fun signalLabel(rssi: Int): Pair<String, Color> = when {
    rssi >= -60 -> "Strong" to TelemetryCyan
    rssi >= -72 -> "Fair" to Color(0xFFFFC857)
    else -> "Weak" to Color(0xFFFF6B73)
}

private fun peerAlias(state: M1BUiState): String =
    state.trustedDeviceId?.let { "peer-${it.takeLast(4)}" } ?: "secure peer"
