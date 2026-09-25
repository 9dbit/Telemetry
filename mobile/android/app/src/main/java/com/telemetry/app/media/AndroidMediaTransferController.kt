package com.telemetry.app.media

import android.content.Context
import android.net.Uri
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.mesh.AndroidMeshNodeRuntime
import com.telemetry.app.transport.AndroidControlEnvelopeCodec
import com.telemetry.app.transport.AndroidTrustedControlChannel
import com.telemetry.app.transport.AndroidWifiLocalMeshPort
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

sealed interface NativeMediaTransferEvent {
    data class Preparing(val displayName: String) : NativeMediaTransferEvent
    data class OutgoingQueued(
        val assetId: String,
        val kind: String,
        val fileName: String,
        val byteLength: Long,
        val totalChunks: Int
    ) : NativeMediaTransferEvent
    data class OutgoingProgress(
        val assetId: String,
        val acknowledgedChunks: Int,
        val totalChunks: Int,
        val transport: String?
    ) : NativeMediaTransferEvent
    data class OutgoingComplete(
        val assetId: String,
        val kind: String,
        val fileName: String,
        val byteLength: Long
    ) : NativeMediaTransferEvent
    data class IncomingProgress(
        val assetId: String,
        val kind: String,
        val fileName: String,
        val receivedChunks: Int,
        val totalChunks: Int
    ) : NativeMediaTransferEvent
    data class IncomingReady(
        val assetId: String,
        val kind: String,
        val fileName: String,
        val byteLength: Long
    ) : NativeMediaTransferEvent
    data class Error(val message: String) : NativeMediaTransferEvent
}

class AndroidMediaTransferController(
    context: Context,
    private val identity: DeviceIdentity,
    private val controlChannel: AndroidTrustedControlChannel,
    private val node: AndroidMeshNodeRuntime,
    private val wifiPort: AndroidWifiLocalMeshPort,
    private val onEvent: (NativeMediaTransferEvent) -> Unit
) {
    companion object {
        private const val TRANSFER_TTL_MS = 7L * 24 * 60 * 60_000
        private const val CONTROL_RETRY_MS = 10_000L
        private const val TICK_MS = 2_000L
    }

    private data class OutgoingRuntime(
        val peerId: String,
        val manifest: NativeMediaManifest,
        val manifestControlWire: ByteArray,
        val manifestMessageId: String,
        val expiresAtEpochMs: Long,
        val scheduler: AndroidMediaTransferScheduler,
        val pump: AndroidMediaTransferPump,
        @Volatile var lastManifestAttemptAt: Long = 0L,
        @Volatile var lastTransport: String? = null
    )

    private data class IncomingRuntime(
        val senderId: String,
        val conversationId: String,
        val controlMessageId: String,
        val manifest: NativeMediaManifest,
        val expiresAtEpochMs: Long,
        @Volatile var readyEmitted: Boolean = false
    )

    private val appContext = context.applicationContext
    private val sourceAdapter = AndroidMediaSourceAdapter(appContext)
    private val builder = AndroidMediaPackageBuilder()
    private val outgoingStore = AndroidOpaqueMediaChunkStore(File(appContext.filesDir, "telemetry-media/outgoing"))
    private val incomingStore = AndroidOpaqueMediaChunkStore(File(appContext.filesDir, "telemetry-media/incoming"))
    private val assembler = AndroidMediaReceiveAssembler(incomingStore, builder)
    private val stateStore = AndroidMediaTransferStateStore(appContext)
    private val controlCodec = AndroidControlEnvelopeCodec(identity)
    private val outgoing = ConcurrentHashMap<String, OutgoingRuntime>()
    private val incoming = ConcurrentHashMap<String, IncomingRuntime>()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ticker: ScheduledFuture<*>? = null

    fun start() {
        if (ticker != null) return
        restorePersistedState()
        ticker = worker.scheduleWithFixedDelay(
            { runCatching { tick(System.currentTimeMillis()) }.onFailure(::emitError) },
            500L,
            TICK_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        ticker?.cancel(false)
        ticker = null
        worker.shutdownNow()
    }

    fun queueUri(
        uri: Uri,
        peerId: String,
        requestedKind: String? = null,
        conversationId: String
    ) {
        worker.execute {
            runCatching {
                sourceAdapter.persistReadPermission(uri)
                val source = sourceAdapter.resolve(uri, requestedKind)
                onEvent(NativeMediaTransferEvent.Preparing(source.fileName))
                val now = System.currentTimeMillis()
                val expiry = now + TRANSFER_TTL_MS
                val packageResult = builder.buildIntoStore(
                    openInput = source.openInput,
                    byteLength = source.byteLength,
                    kind = source.kind,
                    mimeType = source.mimeType,
                    fileName = source.fileName,
                    store = outgoingStore,
                    expiresAtEpochMs = expiry,
                    nowEpochMs = now
                )
                val manifest = packageResult.manifest
                val manifestPayload = AndroidMediaManifestJson.encodePayload(manifest)
                val controlWire = controlChannel.create(
                    peerDeviceId = peerId,
                    contentType = MEDIA_MANIFEST_CONTENT_TYPE,
                    plaintext = manifestPayload,
                    conversationId = conversationId,
                    hopLimit = 8
                )
                val manifestMessageId = controlCodec.decode(controlWire).messageId
                val scheduler = AndroidMediaTransferScheduler(
                    assetId = manifest.assetId,
                    chunkCount = manifest.chunkCount,
                    store = outgoingStore
                )
                val runtime = OutgoingRuntime(
                    peerId = peerId,
                    manifest = manifest,
                    manifestControlWire = controlWire,
                    manifestMessageId = manifestMessageId,
                    expiresAtEpochMs = expiry,
                    scheduler = scheduler,
                    pump = AndroidMediaTransferPump(scheduler)
                )
                outgoing[manifest.assetId] = runtime
                persist(runtime)
                onEvent(
                    NativeMediaTransferEvent.OutgoingQueued(
                        assetId = manifest.assetId,
                        kind = manifest.kind,
                        fileName = manifest.fileName,
                        byteLength = manifest.byteLength,
                        totalChunks = manifest.chunkCount
                    )
                )
                attempt(runtime, now)
            }.onFailure(::emitError)
        }
    }

    fun onPeerRouteChanged(peerId: String) {
        worker.execute {
            bindWifi(peerId, System.currentTimeMillis())
            outgoing.values.filter { it.peerId == peerId }.forEach { attempt(it, System.currentTimeMillis()) }
            retryIncomingAcksForPeer(peerId, System.currentTimeMillis())
        }
    }

    fun ingestLocalControlWire(wire: ByteArray): Boolean {
        if (!AndroidControlEnvelopeCodec.isTce1(wire)) return false
        worker.execute {
            runCatching { processControlWire(wire, persistIncoming = true) }.onFailure(::emitError)
        }
        return true
    }

    fun ingestIncomingChunk(wire: ByteArray): Boolean = runCatching {
        val chunk = AndroidMediaChunkWireCodec.decode(wire)
        val now = System.currentTimeMillis()
        val result = incomingStore.put(wire, now + TRANSFER_TTL_MS)
        if (result.reason == "conflict") {
            onEvent(NativeMediaTransferEvent.Error("Encrypted media chunk conflict rejected"))
            return@runCatching false
        }
        worker.execute {
            incoming[chunk.assetId]?.let { updateIncoming(it, System.currentTimeMillis()) }
        }
        result.accepted || result.reason == "duplicate"
    }.getOrDefault(false)

    fun materializeIncomingToCache(assetId: String): File? = runCatching {
        val runtime = incoming[assetId] ?: error("incoming media manifest unavailable")
        assembler.materializeToCache(
            manifest = runtime.manifest,
            nowEpochMs = System.currentTimeMillis(),
            cacheRoot = File(appContext.cacheDir, "telemetry-media-open")
        )
    }.getOrElse {
        emitError(it)
        null
    }

    fun outgoingStatus(assetId: String): NativeMediaTransferStatus? =
        outgoing[assetId]?.scheduler?.status()

    private fun restorePersistedState() {
        val now = System.currentTimeMillis()
        stateStore.loadOutgoing(now).forEach { persisted ->
            runCatching {
                val opened = controlChannel.open(persisted.manifestControlWire)
                require(opened.contentType == MEDIA_MANIFEST_CONTENT_TYPE) { "persisted media manifest content type mismatch" }
                val manifest = AndroidMediaManifestJson.decodePayload(opened.plaintext)
                require(manifest.assetId == persisted.assetId && manifest.chunkCount == persisted.chunkCount) {
                    "persisted media manifest mismatch"
                }
                val scheduler = AndroidMediaTransferScheduler(
                    assetId = manifest.assetId,
                    chunkCount = manifest.chunkCount,
                    store = outgoingStore,
                    snapshot = persisted.snapshot
                )
                outgoing[manifest.assetId] = OutgoingRuntime(
                    peerId = persisted.peerId,
                    manifest = manifest,
                    manifestControlWire = persisted.manifestControlWire,
                    manifestMessageId = opened.messageId,
                    expiresAtEpochMs = persisted.expiresAtEpochMs,
                    scheduler = scheduler,
                    pump = AndroidMediaTransferPump(scheduler)
                )
            }.onFailure { /* keep encrypted state for a later compatible trust context */ }
        }

        stateStore.loadIncomingControls(now).forEach { control ->
            runCatching { processControlWire(control.controlWire, persistIncoming = false) }
        }
    }

    private fun processControlWire(wire: ByteArray, persistIncoming: Boolean) {
        val decoded = controlChannel.decode(wire)
        if (decoded.contentType != MEDIA_MANIFEST_CONTENT_TYPE && decoded.contentType != MEDIA_CONTROL_CONTENT_TYPE) return
        if (persistIncoming) {
            stateStore.saveIncomingControl(
                PersistedIncomingMediaControl(
                    messageId = decoded.messageId,
                    controlWire = wire.copyOf(),
                    expiresAtEpochMs = System.currentTimeMillis() + TRANSFER_TTL_MS
                )
            )
        }
        val opened = controlChannel.open(wire)
        when (opened.contentType) {
            MEDIA_MANIFEST_CONTENT_TYPE -> {
                val manifest = AndroidMediaManifestJson.decodePayload(opened.plaintext)
                val runtime = IncomingRuntime(
                    senderId = opened.senderId,
                    conversationId = opened.conversationId,
                    controlMessageId = opened.messageId,
                    manifest = manifest,
                    expiresAtEpochMs = System.currentTimeMillis() + TRANSFER_TTL_MS
                )
                incoming[manifest.assetId] = runtime
                updateIncoming(runtime, System.currentTimeMillis())
            }
            MEDIA_CONTROL_CONTENT_TYPE -> {
                val ack = AndroidMediaAckJson.decode(opened.plaintext)
                val runtime = outgoing[ack.assetId] ?: run {
                    stateStore.removeIncomingControl(opened.messageId)
                    return
                }
                if (!runtime.scheduler.applyAck(ack)) return
                persist(runtime)
                val status = runtime.scheduler.status()
                if (status.complete) {
                    completeOutgoing(runtime)
                } else {
                    onEvent(
                        NativeMediaTransferEvent.OutgoingProgress(
                            assetId = runtime.manifest.assetId,
                            acknowledgedChunks = status.acknowledgedChunks,
                            totalChunks = status.totalChunks,
                            transport = runtime.lastTransport
                        )
                    )
                    attempt(runtime, System.currentTimeMillis())
                }
                stateStore.removeIncomingControl(opened.messageId)
            }
        }
    }

    private fun tick(now: Long) {
        outgoing.values.toList().forEach { runtime ->
            if (runtime.expiresAtEpochMs <= now) {
                stateStore.removeOutgoing(runtime.manifest.assetId)
                outgoing.remove(runtime.manifest.assetId)
                return@forEach
            }
            attempt(runtime, now)
        }
        incoming.values.toList().forEach { runtime ->
            if (runtime.expiresAtEpochMs <= now) incoming.remove(runtime.manifest.assetId)
        }
    }

    private fun attempt(runtime: OutgoingRuntime, now: Long) {
        bindWifi(runtime.peerId, now)
        val statusBefore = runtime.scheduler.status()
        if (statusBefore.complete) {
            completeOutgoing(runtime)
            return
        }

        if (runtime.lastManifestAttemptAt == 0L || now - runtime.lastManifestAttemptAt >= CONTROL_RETRY_MS) {
            val manifestResult = node.sendOriginEnvelope(
                recipientId = runtime.peerId,
                messageId = runtime.manifestMessageId,
                encodedEnvelope = runtime.manifestControlWire,
                nowEpochMs = now,
                ttlMs = TRANSFER_TTL_MS,
                hopLimit = 8
            )
            runtime.lastManifestAttemptAt = now
            if (manifestResult.sent) runtime.lastTransport = manifestResult.transportId
        }

        val pumpResult = runtime.pump.pump(nowEpochMs = now, maxChunks = 4) { wire ->
            wifiPort.sendMediaChunkWire(runtime.peerId, wire)
        }
        if (pumpResult.sentIndices.isNotEmpty()) runtime.lastTransport = "wifi-local"
        persist(runtime)
        val status = runtime.scheduler.status()
        onEvent(
            NativeMediaTransferEvent.OutgoingProgress(
                assetId = runtime.manifest.assetId,
                acknowledgedChunks = status.acknowledgedChunks,
                totalChunks = status.totalChunks,
                transport = runtime.lastTransport
            )
        )
        if (status.exhaustedIndices.isNotEmpty()) {
            onEvent(NativeMediaTransferEvent.Error("Media transfer paused after repeated unacknowledged chunks"))
        }
    }

    private fun updateIncoming(runtime: IncomingRuntime, now: Long) {
        val progress = assembler.progress(runtime.manifest, now)
        onEvent(
            NativeMediaTransferEvent.IncomingProgress(
                assetId = runtime.manifest.assetId,
                kind = runtime.manifest.kind,
                fileName = runtime.manifest.fileName,
                receivedChunks = progress.receivedChunks,
                totalChunks = progress.totalChunks
            )
        )
        sendAck(runtime, progress, now)
        if (progress.complete && !runtime.readyEmitted) {
            if (assembler.verifyComplete(runtime.manifest, now)) {
                runtime.readyEmitted = true
                stateStore.removeIncomingControl(runtime.controlMessageId)
                onEvent(
                    NativeMediaTransferEvent.IncomingReady(
                        assetId = runtime.manifest.assetId,
                        kind = runtime.manifest.kind,
                        fileName = runtime.manifest.fileName,
                        byteLength = runtime.manifest.byteLength
                    )
                )
            } else {
                onEvent(NativeMediaTransferEvent.Error("Received media failed final integrity verification"))
            }
        }
    }

    private fun sendAck(runtime: IncomingRuntime, progress: NativeMediaReceiveProgress, now: Long) {
        val ack = NativeMediaChunkAck(
            assetId = runtime.manifest.assetId,
            chunkCount = runtime.manifest.chunkCount,
            receivedRanges = compactNativeMediaAckRanges(progress.receivedIndices, runtime.manifest.chunkCount),
            complete = progress.complete
        )
        val wire = controlChannel.create(
            peerDeviceId = runtime.senderId,
            contentType = MEDIA_CONTROL_CONTENT_TYPE,
            plaintext = AndroidMediaAckJson.encode(ack),
            conversationId = runtime.conversationId,
            hopLimit = 8
        )
        val messageId = controlCodec.decode(wire).messageId
        val result = node.sendOriginEnvelope(
            recipientId = runtime.senderId,
            messageId = messageId,
            encodedEnvelope = wire,
            nowEpochMs = now,
            ttlMs = TRANSFER_TTL_MS,
            hopLimit = 8
        )
        if (!result.sent) {
            // The next progress update or route-change callback will generate a fresh signed ACK.
            // ACK state is cumulative and idempotent, so this is safe even after a temporary outage.
        }
    }

    private fun retryIncomingAcksForPeer(peerId: String, now: Long) {
        incoming.values.filter { it.senderId == peerId }.forEach { runtime ->
            val progress = assembler.progress(runtime.manifest, now)
            sendAck(runtime, progress, now)
        }
    }

    private fun bindWifi(peerId: String, now: Long) {
        val capabilities = node.peerCapabilities(peerId, now)
        wifiPort.bindPeerCapabilities(peerId, capabilities)
    }

    private fun persist(runtime: OutgoingRuntime) {
        stateStore.saveOutgoing(
            PersistedOutgoingMediaTransfer(
                assetId = runtime.manifest.assetId,
                peerId = runtime.peerId,
                manifestControlWire = runtime.manifestControlWire,
                chunkCount = runtime.manifest.chunkCount,
                snapshot = runtime.scheduler.snapshot(),
                expiresAtEpochMs = runtime.expiresAtEpochMs
            )
        )
    }

    private fun completeOutgoing(runtime: OutgoingRuntime) {
        if (outgoing.remove(runtime.manifest.assetId) == null) return
        stateStore.removeOutgoing(runtime.manifest.assetId)
        for (index in 0 until runtime.manifest.chunkCount) {
            outgoingStore.remove(runtime.manifest.assetId, index)
        }
        onEvent(
            NativeMediaTransferEvent.OutgoingComplete(
                assetId = runtime.manifest.assetId,
                kind = runtime.manifest.kind,
                fileName = runtime.manifest.fileName,
                byteLength = runtime.manifest.byteLength
            )
        )
    }

    private fun emitError(error: Throwable) {
        onEvent(NativeMediaTransferEvent.Error(error.message ?: error::class.java.simpleName))
    }
}
