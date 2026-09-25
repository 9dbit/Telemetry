package com.telemetry.app.media

data class NativeMediaPumpAttempt(
    val index: Int,
    val accepted: Boolean,
    val wireBytes: Int
)

data class NativeMediaPumpResult(
    val assetId: String,
    val attempts: List<NativeMediaPumpAttempt>,
    val sentIndices: List<Int>,
    val reason: String
)

class AndroidMediaTransferPump(
    private val scheduler: AndroidMediaTransferScheduler
) {
    fun pump(
        nowEpochMs: Long,
        maxChunks: Int = 4,
        sendWire: (ByteArray) -> Boolean
    ): NativeMediaPumpResult {
        val batch = scheduler.nextBatch(nowEpochMs, maxChunks)
        if (batch.isEmpty()) {
            val status = scheduler.status()
            return NativeMediaPumpResult(
                assetId = status.assetId,
                attempts = emptyList(),
                sentIndices = emptyList(),
                reason = when {
                    status.complete -> "complete"
                    status.exhaustedIndices.isNotEmpty() -> "exhausted"
                    else -> "nothing-ready"
                }
            )
        }

        val attempts = mutableListOf<NativeMediaPumpAttempt>()
        val sent = mutableListOf<Int>()
        batch.forEach { record ->
            val accepted = runCatching { sendWire(record.wire.copyOf()) }.getOrDefault(false)
            attempts += NativeMediaPumpAttempt(
                index = record.index,
                accepted = accepted,
                wireBytes = record.wire.size
            )
            if (accepted) {
                scheduler.markSent(listOf(record.index), nowEpochMs)
                sent += record.index
            }
        }

        return NativeMediaPumpResult(
            assetId = scheduler.status().assetId,
            attempts = attempts,
            sentIndices = sent,
            reason = when {
                sent.isEmpty() -> "transport-rejected"
                sent.size == attempts.size -> "batch-sent"
                else -> "partial-batch-sent"
            }
        )
    }
}
