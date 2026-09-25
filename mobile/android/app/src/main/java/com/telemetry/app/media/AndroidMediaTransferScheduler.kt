package com.telemetry.app.media

data class NativeMediaAckRange(val start: Int, val end: Int)

data class NativeMediaChunkAck(
    val assetId: String,
    val chunkCount: Int,
    val receivedRanges: List<NativeMediaAckRange>,
    val complete: Boolean
)

data class NativeMediaSendState(
    val attempts: Int,
    val lastSentAtEpochMs: Long
)

data class NativeMediaTransferSnapshot(
    val assetId: String,
    val chunkCount: Int,
    val acknowledgedIndices: List<Int>,
    val sentState: Map<Int, NativeMediaSendState>
)

data class NativeMediaTransferStatus(
    val assetId: String,
    val totalChunks: Int,
    val acknowledgedChunks: Int,
    val pendingChunks: Int,
    val complete: Boolean,
    val exhaustedIndices: List<Int>
)

fun compactNativeMediaAckRanges(indices: Collection<Int>, chunkCount: Int): List<NativeMediaAckRange> {
    require(chunkCount in 1..8192) { "invalid media ack chunkCount" }
    val sorted = indices.toSortedSet()
    sorted.forEach { require(it in 0 until chunkCount) { "invalid media ack index" } }
    if (sorted.isEmpty()) return emptyList()

    val ranges = mutableListOf<NativeMediaAckRange>()
    var start = sorted.first()
    var end = start
    sorted.drop(1).forEach { value ->
        if (value == end + 1) {
            end = value
        } else {
            ranges += NativeMediaAckRange(start, end)
            start = value
            end = value
        }
    }
    ranges += NativeMediaAckRange(start, end)
    return ranges
}

fun expandNativeMediaAckRanges(ranges: List<NativeMediaAckRange>, chunkCount: Int): List<Int> {
    require(chunkCount in 1..8192) { "invalid media ack chunkCount" }
    val output = mutableListOf<Int>()
    var previousEnd = -1
    ranges.forEach { range ->
        require(range.start >= 0 && range.end >= range.start && range.end < chunkCount) {
            "invalid media ack range"
        }
        require(range.start > previousEnd) { "media ack ranges must be sorted and non-overlapping" }
        for (index in range.start..range.end) output += index
        previousEnd = range.end
    }
    return output
}

class AndroidMediaTransferScheduler(
    private val assetId: String,
    private val chunkCount: Int,
    private val store: AndroidOpaqueMediaChunkStore,
    private val retryAfterMs: Long = 5_000L,
    private val maxAttempts: Int = 20,
    snapshot: NativeMediaTransferSnapshot? = null
) {
    private val acknowledged = linkedSetOf<Int>()
    private val sent = linkedMapOf<Int, NativeMediaSendState>()

    init {
        require(assetId.isNotBlank()) { "assetId is required" }
        require(chunkCount in 1..8192) { "invalid media chunk count" }
        require(retryAfterMs > 0L) { "invalid media retryAfterMs" }
        require(maxAttempts > 0) { "invalid media maxAttempts" }
        snapshot?.let(::restore)
    }

    @Synchronized
    fun nextBatch(nowEpochMs: Long, maxChunks: Int = 4): List<StoredOpaqueMediaChunk> {
        require(nowEpochMs >= 0L) { "invalid media scheduler time" }
        require(maxChunks in 1..128) { "invalid media maxChunks" }
        val available = store.records(nowEpochMs)
            .filter { it.assetId == assetId && it.count == chunkCount }
            .associateBy { it.index }

        val selected = mutableListOf<StoredOpaqueMediaChunk>()
        for (index in 0 until chunkCount) {
            if (selected.size >= maxChunks) break
            if (acknowledged.contains(index)) continue
            val record = available[index] ?: continue
            val state = sent[index]
            if (state != null && state.attempts >= maxAttempts) continue
            if (state != null && nowEpochMs - state.lastSentAtEpochMs < retryAfterMs) continue
            selected += record
        }
        return selected
    }

    @Synchronized
    fun markSent(indices: Collection<Int>, nowEpochMs: Long) {
        require(nowEpochMs >= 0L) { "invalid media scheduler time" }
        indices.forEach { index ->
            require(index in 0 until chunkCount) { "invalid sent media chunk index" }
            if (acknowledged.contains(index)) return@forEach
            val previous = sent[index] ?: NativeMediaSendState(0, 0L)
            sent[index] = NativeMediaSendState(
                attempts = previous.attempts + 1,
                lastSentAtEpochMs = nowEpochMs
            )
        }
    }

    @Synchronized
    fun applyAck(ack: NativeMediaChunkAck): Boolean {
        if (ack.assetId != assetId || ack.chunkCount != chunkCount) return false
        val indices = expandNativeMediaAckRanges(ack.receivedRanges, ack.chunkCount)
        if (ack.complete && indices.size != chunkCount) return false
        acknowledged += indices
        return true
    }

    @Synchronized
    fun snapshot(): NativeMediaTransferSnapshot = NativeMediaTransferSnapshot(
        assetId = assetId,
        chunkCount = chunkCount,
        acknowledgedIndices = acknowledged.toList().sorted(),
        sentState = sent.toMap()
    )

    @Synchronized
    fun status(): NativeMediaTransferStatus {
        val exhausted = (0 until chunkCount).filter { index ->
            !acknowledged.contains(index) && (sent[index]?.attempts ?: 0) >= maxAttempts
        }
        return NativeMediaTransferStatus(
            assetId = assetId,
            totalChunks = chunkCount,
            acknowledgedChunks = acknowledged.size,
            pendingChunks = chunkCount - acknowledged.size,
            complete = acknowledged.size == chunkCount,
            exhaustedIndices = exhausted
        )
    }

    private fun restore(snapshot: NativeMediaTransferSnapshot) {
        require(snapshot.assetId == assetId) { "media scheduler snapshot asset mismatch" }
        require(snapshot.chunkCount == chunkCount) { "media scheduler snapshot chunk count mismatch" }
        snapshot.acknowledgedIndices.forEach {
            require(it in 0 until chunkCount) { "invalid acknowledged media chunk index" }
            acknowledged += it
        }
        snapshot.sentState.forEach { (index, state) ->
            require(index in 0 until chunkCount) { "invalid media scheduler sent index" }
            require(state.attempts >= 0 && state.lastSentAtEpochMs >= 0L) { "invalid media scheduler sent state" }
            sent[index] = state
        }
    }
}
