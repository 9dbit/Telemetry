import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { envelopeRelayView } from '../src/protocol/envelope.js';
import { createMediaPackage } from '../src/protocol/media.js';
import {
  compactChunkRanges,
  createMediaChunkAckEnvelope,
  expandChunkRanges,
  MediaReceiveSession,
  MediaTransferScheduler,
  openMediaChunkAckEnvelope
} from '../src/protocol/media-transfer.js';

test('chunk range codec is compact, canonical and reversible', () => {
  const ranges = compactChunkRanges([7, 1, 2, 3, 7, 9, 10, 12], 20);
  assert.deepEqual(ranges, [[1, 3], [7, 7], [9, 10], [12, 12]]);
  assert.deepEqual(expandChunkRanges(ranges, 20), [1, 2, 3, 7, 9, 10, 12]);
  assert.throws(() => expandChunkRanges([[2, 4], [4, 5]], 10), /sorted and non-overlapping/);
});

test('encrypted media ack hides asset progress from relays and verifies at sender', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const key = deriveSessionKey(alice, bob.exchange.publicKey);
  const media = createMediaPackage({
    bytes: randomBytes(180_000),
    kind: 'photo',
    mimeType: 'image/jpeg',
    fileName: 'inspection.jpg',
    chunkBytes: 32 * 1024
  });

  const ackEnvelope = createMediaChunkAckEnvelope({
    senderIdentity: bob,
    recipientId: alice.deviceId,
    sessionKey: deriveSessionKey(bob, alice.exchange.publicKey),
    assetId: media.manifest.assetId,
    chunkCount: media.manifest.chunkCount,
    receivedIndices: [0, 1, 3],
    conversationId: 'media-conversation'
  });

  const sealed = JSON.stringify(ackEnvelope.payload);
  assert.equal(sealed.includes(media.manifest.assetId), false);
  assert.equal(sealed.includes('receivedRanges'), false);
  const relayView = envelopeRelayView(ackEnvelope);
  assert.equal(Object.hasOwn(relayView, 'assetId'), false);
  assert.equal(Object.hasOwn(relayView, 'receivedRanges'), false);

  const ack = openMediaChunkAckEnvelope({
    envelope: ackEnvelope,
    senderSigningPublicKey: bob.signing.publicKey,
    sessionKey: key
  });
  assert.equal(ack.assetId, media.manifest.assetId);
  assert.deepEqual(ack.receivedRanges, [[0, 1], [3, 3]]);
  assert.equal(ack.complete, false);
});

test('sender retries exact encrypted chunk bytes after timeout and skips acknowledged chunks', () => {
  const media = createMediaPackage({
    bytes: randomBytes(150_000),
    kind: 'file',
    mimeType: 'application/octet-stream',
    fileName: 'asset.bin',
    chunkBytes: 32 * 1024
  });
  const scheduler = new MediaTransferScheduler({
    manifest: media.manifest,
    chunks: media.chunks,
    retryAfterMs: 5_000
  });

  const t0 = new Date('2026-09-25T08:00:00.000Z');
  const first = scheduler.nextBatch({ now: t0, maxChunks: 2 });
  assert.deepEqual(first.map((chunk) => chunk.index), [0, 1]);
  scheduler.markSent([0, 1], { now: t0 });

  const nextFresh = scheduler.nextBatch({ now: new Date('2026-09-25T08:00:01.000Z'), maxChunks: 2 });
  assert.deepEqual(nextFresh.map((chunk) => chunk.index), [2, 3]);
  scheduler.markSent([2, 3], { now: new Date('2026-09-25T08:00:01.000Z') });

  assert.equal(scheduler.applyAck({
    kind: 'media-chunk-ack',
    assetId: media.manifest.assetId,
    chunkCount: media.manifest.chunkCount,
    receivedRanges: [[0, 0], [2, 2]],
    complete: false
  }).accepted, true);

  const retry = scheduler.nextBatch({ now: new Date('2026-09-25T08:00:06.000Z'), maxChunks: 3 });
  assert.deepEqual(retry.map((chunk) => chunk.index), [1, 3, 4]);
  assert.deepEqual(retry[0], media.chunks[1]);
  assert.equal(retry[0].nonce, media.chunks[1].nonce);
  assert.equal(retry[0].ciphertext, media.chunks[1].ciphertext);
  assert.equal(retry[0].tag, media.chunks[1].tag);
});

test('receiver restart can resume from encrypted chunks and sender completes from compact ack', () => {
  const original = randomBytes(220_000);
  const media = createMediaPackage({
    bytes: original,
    kind: 'video',
    mimeType: 'video/mp4',
    fileName: 'short.mp4',
    chunkBytes: 32 * 1024
  });
  const scheduler = new MediaTransferScheduler({ manifest: media.manifest, chunks: media.chunks });

  const receiverBeforeRestart = new MediaReceiveSession(media.manifest);
  [0, 2, 5].filter((index) => index < media.chunks.length).forEach((index) => {
    receiverBeforeRestart.ingest(media.chunks[index]);
  });
  const persistedIndices = receiverBeforeRestart.ackState().receivedIndices;

  const receiverAfterRestart = new MediaReceiveSession(media.manifest);
  for (const index of persistedIndices) receiverAfterRestart.ingest(media.chunks[index]);
  const resumeAck = receiverAfterRestart.ackState();
  scheduler.applyAck({
    kind: 'media-chunk-ack',
    assetId: resumeAck.assetId,
    chunkCount: resumeAck.chunkCount,
    receivedRanges: resumeAck.receivedRanges,
    complete: resumeAck.complete
  });

  const pending = scheduler.nextBatch({ now: new Date('2026-09-25T08:10:00.000Z'), maxChunks: 128 });
  assert.ok(pending.every((chunk) => !persistedIndices.includes(chunk.index)));
  for (const chunk of pending) receiverAfterRestart.ingest(chunk);

  const finalAck = receiverAfterRestart.ackState();
  assert.equal(finalAck.complete, true);
  assert.equal(scheduler.applyAck({
    kind: 'media-chunk-ack',
    assetId: finalAck.assetId,
    chunkCount: finalAck.chunkCount,
    receivedRanges: finalAck.receivedRanges,
    complete: true
  }).complete, true);
  assert.equal(scheduler.state().complete, true);
  assert.deepEqual(receiverAfterRestart.assemble(), original);
});

test('ack from another asset cannot advance scheduler and tampered outer ack is rejected', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const media = createMediaPackage({
    bytes: randomBytes(70_000),
    kind: 'file',
    mimeType: 'application/pdf',
    fileName: 'report.pdf',
    chunkBytes: 32 * 1024
  });
  const other = createMediaPackage({
    bytes: randomBytes(70_000),
    kind: 'file',
    mimeType: 'application/pdf',
    fileName: 'other.pdf',
    chunkBytes: 32 * 1024
  });
  const scheduler = new MediaTransferScheduler({ manifest: media.manifest, chunks: media.chunks });

  const mismatch = scheduler.applyAck({
    kind: 'media-chunk-ack',
    assetId: other.manifest.assetId,
    chunkCount: media.manifest.chunkCount,
    receivedRanges: [[0, 0]],
    complete: false
  });
  assert.equal(mismatch.accepted, false);
  assert.equal(scheduler.state().acknowledgedChunks, 0);

  const key = deriveSessionKey(bob, alice.exchange.publicKey);
  const envelope = createMediaChunkAckEnvelope({
    senderIdentity: bob,
    recipientId: alice.deviceId,
    sessionKey: key,
    assetId: media.manifest.assetId,
    chunkCount: media.manifest.chunkCount,
    receivedIndices: [0]
  });
  assert.throws(() => openMediaChunkAckEnvelope({
    envelope: { ...envelope, recipientId: 'tlm:device:tampered' },
    senderSigningPublicKey: bob.signing.publicKey,
    sessionKey: key
  }), /invalid media control envelope signature/);
});

test('max attempts exposes exhausted chunks instead of retrying forever', () => {
  const media = createMediaPackage({
    bytes: randomBytes(40_000),
    kind: 'file',
    mimeType: 'application/octet-stream',
    fileName: 'retry.bin',
    chunkBytes: 32 * 1024
  });
  const scheduler = new MediaTransferScheduler({
    manifest: media.manifest,
    chunks: media.chunks,
    retryAfterMs: 1,
    maxAttempts: 2
  });

  scheduler.markSent([0], { now: new Date('2026-09-25T08:00:00.000Z') });
  scheduler.markSent([0], { now: new Date('2026-09-25T08:00:01.000Z') });
  const batch = scheduler.nextBatch({ now: new Date('2026-09-25T08:00:02.000Z'), maxChunks: 10 });
  assert.equal(batch.some((chunk) => chunk.index === 0), false);
  assert.deepEqual(scheduler.state().exhaustedIndices, [0]);
});
