package com.telemetry.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCallRuntimeTest {
    private class RecordingSignalPort : CallSignalPort {
        val sent = mutableListOf<NativeCallSignal>()
        var allowSend = true
        override fun send(signal: NativeCallSignal, nowEpochMs: Long): Boolean {
            if (!allowSend) return false
            sent += signal
            return true
        }
    }

    private class Capabilities(vararg initial: Pair<String, List<String>>) : CallDirectCapabilityProvider {
        val map = initial.toMap().toMutableMap()
        override fun availableTransports(peerId: String): List<String> = map[peerId].orEmpty()
    }

    private class FakeMediaEngine : RealtimeCallMediaEngine {
        val prepared = mutableListOf<String>()
        val remoteCandidates = mutableListOf<String>()
        val connected = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        var muted = false
        var speaker = false
        var connectAllowed = true

        override fun prepare(callId: String, peerId: String, transport: String, isCaller: Boolean): RealtimeCallPreparation {
            prepared += "$callId|$peerId|$transport|$isCaller"
            return RealtimeCallPreparation("endpoint-$transport-${if (isCaller) "caller" else "callee"}")
        }

        override fun applyRemoteCandidate(callId: String, endpointToken: String): Boolean {
            remoteCandidates += "$callId|$endpointToken"
            return true
        }

        override fun connect(callId: String): Boolean {
            if (!connectAllowed) return false
            connected += callId
            return true
        }

        override fun setMuted(callId: String, muted: Boolean): Boolean {
            this.muted = muted
            return true
        }

        override fun setSpeakerEnabled(callId: String, enabled: Boolean): Boolean {
            speaker = enabled
            return true
        }

        override fun stop(callId: String) {
            stopped += callId
        }
    }

    private val caller = "tlm:device:caller-android-0001"
    private val callee = "tlm:device:callee-android-0001"
    private val now = 1_800_000_000_000L

    @Test
    fun noDirectWifiPathReturnsVoiceMessageFallbackInsteadOfLiveCall() {
        val port = RecordingSignalPort()
        val events = mutableListOf<NativeCallRuntimeEvent>()
        val runtime = AndroidCallRuntime(
            localDeviceId = caller,
            signalPort = port,
            capabilityProvider = Capabilities(callee to listOf("ble", "mesh-relay", "lora")),
            mediaEngine = FakeMediaEngine(),
            onEvent = events::add
        )

        assertNull(runtime.startOutgoing(callee, now))
        assertTrue(port.sent.isEmpty())
        val unavailable = events.single() as NativeCallRuntimeEvent.Unavailable
        assertEquals("no-direct-realtime-path", unavailable.reason)
        assertTrue(unavailable.offerVoiceMessage)
    }

    @Test
    fun outgoingCallNegotiatesDirectWifiAndActivatesFakeMediaEngine() {
        val port = RecordingSignalPort()
        val engine = FakeMediaEngine()
        val events = mutableListOf<NativeCallRuntimeEvent>()
        val runtime = AndroidCallRuntime(
            caller,
            port,
            Capabilities(callee to listOf("ble", "wifi-local")),
            engine,
            events::add
        )

        val callId = runtime.startOutgoing(callee, now)
        assertNotNull(callId)
        assertEquals("invite", port.sent.single().kind)
        assertEquals(listOf("wifi-local"), port.sent.single().directTransports)

        assertTrue(runtime.ingest(AndroidCallProtocol.createSignal(
            kind = "ringing",
            callId = callId!!,
            callerId = caller,
            calleeId = callee,
            fromId = callee,
            sequence = 1,
            nowEpochMs = now + 1_000
        ), now + 1_000))

        assertTrue(runtime.ingest(AndroidCallProtocol.createSignal(
            kind = "accept",
            callId = callId,
            callerId = caller,
            calleeId = callee,
            fromId = callee,
            sequence = 2,
            nowEpochMs = now + 2_000,
            transport = "wifi-local"
        ), now + 2_000))
        assertEquals("negotiating", runtime.state())
        assertEquals("candidate", port.sent.last().kind)
        assertEquals("wifi-local", runtime.selectedTransport())

        assertTrue(runtime.ingest(AndroidCallProtocol.createSignal(
            kind = "candidate",
            callId = callId,
            callerId = caller,
            calleeId = callee,
            fromId = callee,
            sequence = 3,
            nowEpochMs = now + 3_000,
            transport = "wifi-local",
            endpointToken = "remote-endpoint-token-0001"
        ), now + 3_000))
        assertEquals("active", runtime.state())
        assertTrue(engine.connected.contains(callId))
        assertEquals("connected", port.sent.last().kind)
        assertTrue(runtime.setMuted(true))
        assertTrue(runtime.setSpeakerEnabled(true))
        assertTrue(engine.muted)
        assertTrue(engine.speaker)

        assertTrue(runtime.ingest(AndroidCallProtocol.createSignal(
            kind = "end",
            callId = callId,
            callerId = caller,
            calleeId = callee,
            fromId = callee,
            sequence = 4,
            nowEpochMs = now + 4_000,
            reason = "remote-hangup"
        ), now + 4_000))
        assertEquals("idle", runtime.state())
        assertTrue(engine.stopped.contains(callId))
        assertTrue(events.any { it is NativeCallRuntimeEvent.Active })
    }

    @Test
    fun incomingCallRingsAcceptsAndSendsCandidateUsingCommonDirectTransport() {
        val port = RecordingSignalPort()
        val engine = FakeMediaEngine()
        val events = mutableListOf<NativeCallRuntimeEvent>()
        val runtime = AndroidCallRuntime(
            callee,
            port,
            Capabilities(caller to listOf("wifi-aware", "wifi-local")),
            engine,
            events::add
        )
        val callId = "call-incoming-android-0001"
        val invite = AndroidCallProtocol.createSignal(
            kind = "invite",
            callId = callId,
            callerId = caller,
            calleeId = callee,
            fromId = caller,
            sequence = 1,
            nowEpochMs = now,
            directTransports = listOf("wifi-local", "wifi-aware")
        )
        assertTrue(runtime.ingest(invite, now + 500))
        assertEquals("incoming-ringing", runtime.state())
        assertEquals("ringing", port.sent.single().kind)

        assertTrue(runtime.acceptIncoming(now + 1_000))
        assertEquals("negotiating", runtime.state())
        assertEquals(listOf("ringing", "accept", "candidate"), port.sent.map { it.kind })
        assertEquals("wifi-aware", runtime.selectedTransport())
        assertTrue(engine.prepared.single().contains("wifi-aware"))
        assertTrue(events.any { it is NativeCallRuntimeEvent.IncomingRinging })
    }

    @Test
    fun activeSessionRejectsSecondInviteWithBusySignalAndKeepsCurrentCall() {
        val port = RecordingSignalPort()
        val runtime = AndroidCallRuntime(
            callee,
            port,
            Capabilities(
                caller to listOf("wifi-local"),
                "tlm:device:other-android-0002" to listOf("wifi-local")
            ),
            FakeMediaEngine()
        )
        val first = AndroidCallProtocol.createSignal(
            kind = "invite",
            callId = "call-first-android-001",
            callerId = caller,
            calleeId = callee,
            fromId = caller,
            sequence = 1,
            nowEpochMs = now,
            directTransports = listOf("wifi-local")
        )
        assertTrue(runtime.ingest(first, now))

        val other = "tlm:device:other-android-0002"
        val second = AndroidCallProtocol.createSignal(
            kind = "invite",
            callId = "call-second-android-01",
            callerId = other,
            calleeId = callee,
            fromId = other,
            sequence = 1,
            nowEpochMs = now + 1_000,
            directTransports = listOf("wifi-local")
        )
        assertFalse(runtime.ingest(second, now + 1_000))
        assertEquals("busy", port.sent.last().kind)
        assertEquals("call-first-android-001", runtime.activeCallId())
    }

    @Test
    fun inviteTimeoutEndsSessionAndStaleRemoteSequenceIsIgnored() {
        val port = RecordingSignalPort()
        val engine = FakeMediaEngine()
        val runtime = AndroidCallRuntime(
            caller,
            port,
            Capabilities(callee to listOf("wifi-local")),
            engine
        )
        val callId = runtime.startOutgoing(callee, now, timeoutMs = 10_000)!!
        val ringing = AndroidCallProtocol.createSignal(
            kind = "ringing",
            callId = callId,
            callerId = caller,
            calleeId = callee,
            fromId = callee,
            sequence = 5,
            nowEpochMs = now + 1_000
        )
        assertTrue(runtime.ingest(ringing, now + 1_000))
        assertFalse(runtime.ingest(ringing, now + 1_500))
        assertTrue(runtime.tick(now + 10_001))
        assertEquals("idle", runtime.state())
        assertEquals("cancel", port.sent.last().kind)
    }
}
