package com.telemetry.app.media

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object AndroidMediaAckJson {
    fun encode(ack: NativeMediaChunkAck): ByteArray {
        validate(ack)
        val ranges = JSONArray()
        ack.receivedRanges.forEach { range ->
            ranges.put(JSONArray().put(range.start).put(range.end))
        }
        return JSONObject()
            .put("kind", "media-chunk-ack")
            .put("assetId", ack.assetId)
            .put("chunkCount", ack.chunkCount)
            .put("receivedRanges", ranges)
            .put("complete", ack.complete)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): NativeMediaChunkAck {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(json.getString("kind") == "media-chunk-ack") { "invalid media ACK kind" }
        val rangesJson = json.getJSONArray("receivedRanges")
        val ranges = buildList {
            for (index in 0 until rangesJson.length()) {
                val range = rangesJson.getJSONArray(index)
                require(range.length() == 2) { "invalid media ACK range" }
                add(NativeMediaAckRange(range.getInt(0), range.getInt(1)))
            }
        }
        return NativeMediaChunkAck(
            assetId = json.getString("assetId"),
            chunkCount = json.getInt("chunkCount"),
            receivedRanges = ranges,
            complete = json.getBoolean("complete")
        ).also(::validate)
    }

    fun validate(ack: NativeMediaChunkAck) {
        require(ack.assetId.length in 8..128) { "invalid media ACK assetId" }
        require(ack.chunkCount in 1..8192) { "invalid media ACK chunkCount" }
        val expanded = expandNativeMediaAckRanges(ack.receivedRanges, ack.chunkCount)
        if (ack.complete) require(expanded.size == ack.chunkCount) { "complete media ACK is missing chunks" }
    }
}
