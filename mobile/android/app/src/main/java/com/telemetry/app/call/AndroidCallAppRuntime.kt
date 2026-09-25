package com.telemetry.app.call

import android.content.Context
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.media.AndroidMediaAppRuntime

/**
 * App-level coordinator that binds Telemetry encrypted signaling to the native WebRTC media engine.
 *
 * The signaling plane may use Telemetry routing, but [AndroidCallRuntime] will only start realtime
 * media when [AndroidMediaAppRuntime] confirms a direct Wi-Fi-class peer path.
 */
class AndroidCallAppRuntime(
    context: Context,
    identity: DeviceIdentity,
    private val sharedRuntime: AndroidMediaAppRuntime,
    onEvent: (NativeCallRuntimeEvent) -> Unit = {}
) {
    private val mediaEngine = AndroidWebRtcAudioEngine(context.applicationContext)

    private val runtime = AndroidCallRuntime(
        localDeviceId = identity.deviceId,
        signalPort = sharedRuntime.callSignalPort(),
        capabilityProvider = object : CallDirectCapabilityProvider {
            override fun availableTransports(peerId: String): List<String> =
                sharedRuntime.availableRealtimeTransports(peerId)
        },
        mediaEngine = mediaEngine,
        onEvent = onEvent
    )

    init {
        sharedRuntime.setCallSignalHandler { signal ->
            runtime.ingest(signal, System.currentTimeMillis())
        }
    }

    fun startOutgoing(peerId: String): String? = runtime.startOutgoing(peerId)

    fun acceptIncoming(): Boolean = runtime.acceptIncoming()

    fun declineIncoming(reason: String = "declined"): Boolean =
        runtime.declineIncoming(reason)

    fun cancelOutgoing(): Boolean = runtime.cancelOutgoing()

    fun hangup(reason: String = "local-hangup"): Boolean = runtime.hangup(reason)

    fun setMuted(muted: Boolean): Boolean = runtime.setMuted(muted)

    fun setSpeakerEnabled(enabled: Boolean): Boolean = runtime.setSpeakerEnabled(enabled)

    fun activeCallId(): String? = runtime.activeCallId()

    fun state(): String = runtime.state()

    fun selectedTransport(): String? = runtime.selectedTransport()

    fun tick(nowEpochMs: Long = System.currentTimeMillis()): Boolean = runtime.tick(nowEpochMs)

    fun stop() {
        sharedRuntime.setCallSignalHandler(null)
        if (runtime.activeCallId() != null) runtime.hangup("runtime-stopped")
    }
}
