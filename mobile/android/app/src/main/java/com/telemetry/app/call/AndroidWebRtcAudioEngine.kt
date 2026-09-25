package com.telemetry.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android audio-only WebRTC engine for Telemetry direct P2P calls.
 *
 * WebRTC is used only for the realtime media plane. All SDP/ICE signaling is emitted through
 * [RealtimeCallMediaEvent] and is expected to travel through Telemetry's encrypted signaling
 * channel. No STUN/TURN server is configured here: M1.6 live voice is intentionally limited to
 * direct Wi-Fi-class paths whose host ICE candidates are mutually reachable.
 */
class AndroidWebRtcAudioEngine(
    context: Context
) : RealtimeCallMediaEngine {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val factory: PeerConnectionFactory
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var listener: (RealtimeCallMediaEvent) -> Unit = {}

    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    private data class Session(
        val callId: String,
        val peerId: String,
        val transport: String,
        val isCaller: Boolean,
        val peerConnection: PeerConnection,
        val audioSource: AudioSource,
        val audioTrack: AudioTrack,
        val pendingRemoteIce: MutableList<IceCandidate> = mutableListOf(),
        var remoteDescriptionSet: Boolean = false,
        var connectedEmitted: Boolean = false
    )

    init {
        initializeWebRtcOnce(appContext)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun setListener(listener: (RealtimeCallMediaEvent) -> Unit) {
        this.listener = listener
    }

    override fun prepare(
        callId: String,
        peerId: String,
        transport: String,
        isCaller: Boolean
    ): Boolean {
        if (transport !in REALTIME_DIRECT_TRANSPORTS) return false
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (sessions.containsKey(callId)) return true
        if (sessions.isNotEmpty()) return false

        return runCatching {
            enterCommunicationAudioMode()

            val audioSource = factory.createAudioSource(MediaConstraints())
            val audioTrack = factory.createAudioTrack("telemetry-audio-$callId", audioSource).apply {
                setEnabled(true)
            }

            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }

            val observer = createObserver(callId)
            val peerConnection = factory.createPeerConnection(rtcConfig, observer)
                ?: error("webrtc-peer-connection-create-failed")

            peerConnection.addTrack(audioTrack, listOf("telemetry-audio"))

            val session = Session(
                callId = callId,
                peerId = peerId,
                transport = transport,
                isCaller = isCaller,
                peerConnection = peerConnection,
                audioSource = audioSource,
                audioTrack = audioTrack
            )
            sessions[callId] = session
            true
        }.getOrElse {
            leaveCommunicationAudioMode()
            emit(RealtimeCallMediaEvent.Failed(callId, it.message ?: "webrtc-prepare-failed"))
            false
        }
    }

    override fun createOffer(callId: String): Boolean {
        val session = sessions[callId] ?: return false
        if (!session.isCaller) return false
        return runCatching {
            session.peerConnection.createOffer(
                localDescriptionObserver(session, SessionDescription.Type.OFFER),
                audioOnlySdpConstraints()
            )
            true
        }.getOrElse {
            emit(RealtimeCallMediaEvent.Failed(callId, it.message ?: "webrtc-offer-create-failed"))
            false
        }
    }

    override fun applyRemoteOffer(callId: String, sessionDescription: String): Boolean {
        val session = sessions[callId] ?: return false
        if (session.isCaller) return false
        return setRemoteDescription(
            session = session,
            description = SessionDescription(SessionDescription.Type.OFFER, sessionDescription),
            afterSet = {
                session.peerConnection.createAnswer(
                    localDescriptionObserver(session, SessionDescription.Type.ANSWER),
                    audioOnlySdpConstraints()
                )
            }
        )
    }

    override fun applyRemoteAnswer(callId: String, sessionDescription: String): Boolean {
        val session = sessions[callId] ?: return false
        if (!session.isCaller) return false
        return setRemoteDescription(
            session = session,
            description = SessionDescription(SessionDescription.Type.ANSWER, sessionDescription)
        )
    }

    override fun addRemoteIceCandidate(callId: String, candidate: NativeIceCandidate): Boolean {
        val session = sessions[callId] ?: return false
        val rtcCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        synchronized(session) {
            if (!session.remoteDescriptionSet) {
                session.pendingRemoteIce += rtcCandidate
                return true
            }
        }
        return session.peerConnection.addIceCandidate(rtcCandidate)
    }

    override fun setMuted(callId: String, muted: Boolean): Boolean {
        val session = sessions[callId] ?: return false
        session.audioTrack.setEnabled(!muted)
        return true
    }

    override fun setSpeakerEnabled(callId: String, enabled: Boolean): Boolean {
        if (!sessions.containsKey(callId)) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: return false
                    audioManager.setCommunicationDevice(speaker)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
            true
        }.getOrDefault(false)
    }

    override fun stop(callId: String) {
        val session = sessions.remove(callId) ?: return
        runCatching { session.audioTrack.setEnabled(false) }
        runCatching { session.peerConnection.close() }
        runCatching { session.peerConnection.dispose() }
        runCatching { session.audioTrack.dispose() }
        runCatching { session.audioSource.dispose() }
        if (sessions.isEmpty()) leaveCommunicationAudioMode()
    }

    private fun createObserver(callId: String): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> emitConnectedOnce(callId)
                PeerConnection.IceConnectionState.FAILED ->
                    emitIfActive(callId) { RealtimeCallMediaEvent.Failed(callId, "webrtc-ice-failed") }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            emitIfActive(callId) {
                RealtimeCallMediaEvent.LocalIceCandidate(
                    callId,
                    NativeIceCandidate(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> emitConnectedOnce(callId)
                PeerConnection.PeerConnectionState.FAILED ->
                    emitIfActive(callId) { RealtimeCallMediaEvent.Failed(callId, "webrtc-peer-failed") }
                else -> Unit
            }
        }
    }

    private fun localDescriptionObserver(
        session: Session,
        expectedType: SessionDescription.Type
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            if (description.type != expectedType || sessions[session.callId] !== session) return
            session.peerConnection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (sessions[session.callId] !== session) return
                    when (expectedType) {
                        SessionDescription.Type.OFFER -> emit(
                            RealtimeCallMediaEvent.LocalOffer(session.callId, description.description)
                        )
                        SessionDescription.Type.ANSWER -> emit(
                            RealtimeCallMediaEvent.LocalAnswer(session.callId, description.description)
                        )
                        else -> Unit
                    }
                }

                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    emitIfActive(session.callId) {
                        RealtimeCallMediaEvent.Failed(session.callId, "webrtc-set-local-description:$error")
                    }
                }
            }, description)
        }

        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            emitIfActive(session.callId) {
                RealtimeCallMediaEvent.Failed(session.callId, "webrtc-create-sdp:$error")
            }
        }
        override fun onSetFailure(error: String) = Unit
    }

    private fun setRemoteDescription(
        session: Session,
        description: SessionDescription,
        afterSet: (() -> Unit)? = null
    ): Boolean = runCatching {
        session.peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() {
                if (sessions[session.callId] !== session) return
                val pending = synchronized(session) {
                    session.remoteDescriptionSet = true
                    session.pendingRemoteIce.toList().also { session.pendingRemoteIce.clear() }
                }
                pending.forEach(session.peerConnection::addIceCandidate)
                afterSet?.invoke()
            }

            override fun onCreateFailure(error: String) = Unit
            override fun onSetFailure(error: String) {
                emitIfActive(session.callId) {
                    RealtimeCallMediaEvent.Failed(session.callId, "webrtc-set-remote-description:$error")
                }
            }
        }, description)
        true
    }.getOrElse {
        emitIfActive(session.callId) {
            RealtimeCallMediaEvent.Failed(session.callId, it.message ?: "webrtc-remote-description-failed")
        }
        false
    }

    private fun audioOnlySdpConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun emitConnectedOnce(callId: String) {
        val session = sessions[callId] ?: return
        synchronized(session) {
            if (session.connectedEmitted) return
            session.connectedEmitted = true
        }
        emit(RealtimeCallMediaEvent.Connected(callId))
    }

    private inline fun emitIfActive(callId: String, event: () -> RealtimeCallMediaEvent) {
        if (sessions.containsKey(callId)) emit(event())
    }

    private fun emit(event: RealtimeCallMediaEvent) {
        listener(event)
    }

    private fun enterCommunicationAudioMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    private fun leaveCommunicationAudioMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        audioFocusRequest?.let { request ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { audioManager.abandonAudioFocusRequest(request) }
            }
        }
        audioFocusRequest = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        private val initialized = AtomicBoolean(false)

        private fun initializeWebRtcOnce(context: Context) {
            if (initialized.get()) return
            synchronized(initialized) {
                if (initialized.get()) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                initialized.set(true)
            }
        }
    }
}
