import { sealPayload, openPayload } from './crypto.js';
import { createSignedEnvelope, verifyEnvelope } from './envelope.js';
import { MediaChunkAssembler, validateMediaChunk, validateMediaManifest } from './media.js';

export const MEDIA_CONTROL_CONTENT_TYPE = 'application/telemetry+media-control';
export const MEDIA_CHUNK_ACK_KIND = 'media-chunk-ack';

function toMs(value) {
  const ms = value instanceof Date ? value.getTime() : new Date(value).getTime();
  if (!Number.isFinite(ms)) throw new Error('invalid transfer time');
  return ms;
}

export function compactChunkRanges(indices, chunkCount) {
  if (!Number.isInteger(chunkCount) || chunkCount < 1) throw new Error('invalid chunkCount');
  const sorted = [...new Set(indices)].sort((a, b) => a - b);
  for (const index of sorted) {
    if (!Number.isInteger(index) || index < 0 || index >= chunkCount) throw new Error('invalid chunk index');
  }
  if (sorted.length === 0) return [];

  const ranges = [];
  let start = sorted[0];
  let end = sorted[0];
  for (let position = 1; position < sorted.length; position += 1) {
    const value = sorted[position];
    if (value === end + 1) {
      end = value;
      continue;
    }
    ranges.push([start, end]);
    start = value;
    end = value;
  }
  ranges.push([start, end]);
  return ranges;
}

export function expandChunkRanges(ranges, chunkCount) {
  if (!Array.isArray(ranges)) throw new Error('chunk ranges must be an array');
  if (!Number.isInteger(chunkCount) || chunkCount < 1) throw new Error('invalid chunkCount');
  const indices = [];
  let previousEnd = -1;
  for (const range of ranges) {
    if (!Array.isArray(range) || range.length !== 2) throw new Error('invalid chunk range');
    const [start, end] = range;
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end >= chunkCount) {
      throw new Error('invalid chunk range bounds');
    }
    if (start <= previousEnd) throw new Error('chunk ranges must be sorted and non-overlapping');
    for (let index = start; index <= end; index += 1) indices.push(index);
    previousEnd = end;
  }
  return indices;
}

export function validateMediaChunkAck(ack) {
  if (!ack || ack.kind !== MEDIA_CHUNK_ACK_KIND) throw new Error('invalid media chunk ack kind');
  if (typeof ack.assetId !== 'string' || ack.assetId.length < 8 || ack.assetId.length > 128) {
    throw new Error('invalid media ack assetId');
  }
  if (!Number.isInteger(ack.chunkCount) || ack.chunkCount < 1 || ack.chunkCount > 8192) {
    throw new Error('invalid media ack chunkCount');
  }
  const received = expandChunkRanges(ack.receivedRanges, ack.chunkCount);
  if (typeof ack.complete !== 'boolean') throw new Error('invalid media ack complete flag');
  if (ack.complete && received.length !== ack.chunkCount) throw new Error('complete media ack is missing chunks');
  return ack;
}

export function createMediaChunkAckEnvelope({
  senderIdentity,
  recipientId,
  sessionKey,
  assetId,
  chunkCount,
  receivedIndices,
  conversationId,
  hopLimit = 8
}) {
  const receivedRanges = compactChunkRanges(receivedIndices, chunkCount);
  const ack = validateMediaChunkAck({
    kind: MEDIA_CHUNK_ACK_KIND,
    assetId,
    chunkCount,
    receivedRanges,
    complete: receivedIndices.length === chunkCount
  });
  const sealedPayload = sealPayload(sessionKey, ack);
  return createSignedEnvelope({
    senderIdentity,
    recipientId,
    sealedPayload,
    conversationId,
    contentType: MEDIA_CONTROL_CONTENT_TYPE,
    hopLimit
  });
}

export function openMediaChunkAckEnvelope({
  envelope,
  senderSigningPublicKey,
  sessionKey
}) {
  if (envelope?.contentType !== MEDIA_CONTROL_CONTENT_TYPE) throw new Error('unsupported media control content type');
  if (!verifyEnvelope(envelope, senderSigningPublicKey)) throw new Error('invalid media control envelope signature');
  return validateMediaChunkAck(openPayload(sessionKey, envelope.payload));
}

export class MediaReceiveSession {
  constructor(manifest) {
    this.manifest = validateMediaManifest({ ...manifest });
    this.assembler = new MediaChunkAssembler(this.manifest);
  }

  ingest(chunk) {
    return this.assembler.ingest(chunk);
  }

  ackState() {
    const receivedIndices = [];
    const missing = new Set(this.assembler.missingIndices());
    for (let index = 0; index < this.manifest.chunkCount; index += 1) {
      if (!missing.has(index)) receivedIndices.push(index);
    }
    return {
      assetId: this.manifest.assetId,
      chunkCount: this.manifest.chunkCount,
      receivedIndices,
      receivedRanges: compactChunkRanges(receivedIndices, this.manifest.chunkCount),
      complete: receivedIndices.length === this.manifest.chunkCount
    };
  }

  assemble() {
    return this.assembler.assemble();
  }
}

export class MediaTransferScheduler {
  constructor({
    manifest,
    chunks,
    retryAfterMs = 5_000,
    maxAttempts = 20
  }) {
    this.manifest = validateMediaManifest({ ...manifest });
    if (!Array.isArray(chunks) || chunks.length !== this.manifest.chunkCount) {
      throw new Error('media chunks do not match manifest count');
    }
    this.chunks = new Map();
    for (const chunk of chunks) {
      validateMediaChunk(chunk);
      if (chunk.assetId !== this.manifest.assetId || chunk.count !== this.manifest.chunkCount) {
        throw new Error('media chunk does not match manifest');
      }
      if (this.chunks.has(chunk.index)) throw new Error('duplicate media chunk index');
      this.chunks.set(chunk.index, Object.freeze({ ...chunk }));
    }
    if (this.chunks.size !== this.manifest.chunkCount) throw new Error('media chunk index set is incomplete');
    if (!Number.isInteger(retryAfterMs) || retryAfterMs < 1) throw new Error('invalid retryAfterMs');
    if (!Number.isInteger(maxAttempts) || maxAttempts < 1) throw new Error('invalid maxAttempts');
    this.retryAfterMs = retryAfterMs;
    this.maxAttempts = maxAttempts;
    this.acked = new Set();
    this.sent = new Map();
  }

  nextBatch({ now = new Date(), maxChunks = 4 } = {}) {
    if (!Number.isInteger(maxChunks) || maxChunks < 1 || maxChunks > 128) throw new Error('invalid maxChunks');
    const nowMs = toMs(now);
    const selected = [];
    for (let index = 0; index < this.manifest.chunkCount && selected.length < maxChunks; index += 1) {
      if (this.acked.has(index)) continue;
      const state = this.sent.get(index);
      if (state?.attempts >= this.maxAttempts) continue;
      if (state && nowMs - state.lastSentAtMs < this.retryAfterMs) continue;
      selected.push(this.chunks.get(index));
    }
    return selected;
  }

  markSent(indices, { now = new Date() } = {}) {
    const nowMs = toMs(now);
    for (const index of indices) {
      if (!Number.isInteger(index) || !this.chunks.has(index)) throw new Error('invalid sent chunk index');
      if (this.acked.has(index)) continue;
      const previous = this.sent.get(index) || { attempts: 0, lastSentAtMs: 0 };
      this.sent.set(index, {
        attempts: previous.attempts + 1,
        lastSentAtMs: nowMs
      });
    }
  }

  applyAck(ack) {
    validateMediaChunkAck(ack);
    if (ack.assetId !== this.manifest.assetId || ack.chunkCount !== this.manifest.chunkCount) {
      return { accepted: false, reason: 'transfer-mismatch' };
    }
    const indices = expandChunkRanges(ack.receivedRanges, ack.chunkCount);
    for (const index of indices) this.acked.add(index);
    return {
      accepted: true,
      reason: 'ack-applied',
      newlyKnown: indices.length,
      complete: this.acked.size === this.manifest.chunkCount
    };
  }

  state() {
    const exhausted = [];
    for (let index = 0; index < this.manifest.chunkCount; index += 1) {
      if (!this.acked.has(index) && (this.sent.get(index)?.attempts || 0) >= this.maxAttempts) exhausted.push(index);
    }
    return {
      assetId: this.manifest.assetId,
      totalChunks: this.manifest.chunkCount,
      acknowledgedChunks: this.acked.size,
      pendingChunks: this.manifest.chunkCount - this.acked.size,
      complete: this.acked.size === this.manifest.chunkCount,
      exhaustedIndices: exhausted
    };
  }
}
