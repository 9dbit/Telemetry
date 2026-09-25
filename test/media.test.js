import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { envelopeRelayView } from '../src/protocol/envelope.js';
import {
  createMediaPackage,
  createMediaManifestEnvelope,
  decryptMediaChunk,
  MediaChunkAssembler,
  OpaqueMediaRelayStore,
  openMediaManifestEnvelope,
  MEDIA_MANIFEST_CONTENT_TYPE
} from '../src/protocol/media.js';

test('encrypted photo chunks resume out of order and assemble byte-perfect', () => {
  const original = randomBytes(700_123);
  const { manifest, chunks } = createMediaPackage({
    bytes: original,
    kind: 'photo',
    mimeType: 'image/jpeg',
    fileName: 'site-report.jpg',
    chunkBytes: 64 * 1024,
    now: new Date('2026-09-25T08:00:00.000Z')
  });

  assert.equal(manifest.chunkCount, Math.ceil(original.length / (64 * 1024)));
  assert.equal(chunks.length, manifest.chunkCount);
  assert.equal(Buffer.from(manifest.contentKey, 'base64url').length, 32);

  const assembler = new MediaChunkAssembler(manifest);
  const firstWave = chunks.filter((_, index) => index % 2 === 1).reverse();
  for (const chunk of firstWave) assert.equal(assembler.ingest(chunk).accepted, true);

  const midway = assembler.progress();
  assert.equal(midway.complete, false);
  assert.ok(assembler.missingIndices().length > 0);
  assert.throws(() => assembler.assemble(), /incomplete/);

  for (const index of assembler.missingIndices()) {
    assert.equal(assembler.ingest(chunks[index]).accepted, true);
  }
  assert.equal(assembler.ingest(chunks[0]).reason, 'duplicate');
  assert.equal(assembler.progress().complete, true);
  assert.deepEqual(assembler.assemble(), original);
});

test('media manifest is encrypted inside signed envelope and hidden from relay view', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const sessionKey = deriveSessionKey(alice, bob.exchange.publicKey);
  const { manifest } = createMediaPackage({
    bytes: randomBytes(80_000),
    kind: 'file',
    mimeType: 'application/pdf',
    fileName: 'confidential-floor-plan.pdf',
    chunkBytes: 32 * 1024
  });

  const envelope = createMediaManifestEnvelope({
    senderIdentity: alice,
    recipientId: bob.deviceId,
    sessionKey,
    manifest,
    conversationId: 'conversation-media-1'
  });

  assert.equal(envelope.contentType, MEDIA_MANIFEST_CONTENT_TYPE);
  const serializedSealedPayload = JSON.stringify(envelope.payload);
  assert.equal(serializedSealedPayload.includes(manifest.fileName), false);
  assert.equal(serializedSealedPayload.includes(manifest.mimeType), false);
  assert.equal(serializedSealedPayload.includes(manifest.contentKey), false);
  assert.equal(serializedSealedPayload.includes(manifest.sha256), false);

  const relayView = envelopeRelayView(envelope);
  assert.equal(Object.hasOwn(relayView, 'fileName'), false);
  assert.equal(Object.hasOwn(relayView, 'mimeType'), false);
  assert.equal(Object.hasOwn(relayView, 'contentKey'), false);

  const opened = openMediaManifestEnvelope({
    envelope,
    senderSigningPublicKey: alice.signing.publicKey,
    sessionKey: deriveSessionKey(bob, alice.exchange.publicKey)
  });
  assert.deepEqual(opened, manifest);
});

test('tampered encrypted media chunk fails AEAD integrity check', () => {
  const { manifest, chunks } = createMediaPackage({
    bytes: randomBytes(100_000),
    kind: 'video',
    mimeType: 'video/mp4',
    fileName: 'clip.mp4',
    chunkBytes: 32 * 1024
  });
  const original = chunks[0];
  const ciphertext = Buffer.from(original.ciphertext, 'base64url');
  ciphertext[0] ^= 0x01;
  const tampered = { ...original, ciphertext: ciphertext.toString('base64url') };

  assert.throws(() => decryptMediaChunk(manifest, tampered));
});

test('wrong manifest session key cannot reveal filename, type, hash or content key', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const mallory = generateDeviceIdentity();
  const key = deriveSessionKey(alice, bob.exchange.publicKey);
  const { manifest } = createMediaPackage({
    bytes: randomBytes(40_000),
    kind: 'photo',
    mimeType: 'image/webp',
    fileName: 'private.webp',
    chunkBytes: 32 * 1024
  });
  const envelope = createMediaManifestEnvelope({
    senderIdentity: alice,
    recipientId: bob.deviceId,
    sessionKey: key,
    manifest
  });

  const wrongKey = deriveSessionKey(mallory, bob.exchange.publicKey);
  assert.throws(() => openMediaManifestEnvelope({
    envelope,
    senderSigningPublicKey: alice.signing.publicKey,
    sessionKey: wrongKey
  }));
});

test('opaque relay store dedupes encrypted chunks without receiving media key or private metadata', () => {
  const { chunks } = createMediaPackage({
    bytes: randomBytes(150_000),
    kind: 'file',
    mimeType: 'application/octet-stream',
    fileName: 'private.bin',
    chunkBytes: 32 * 1024
  });
  const store = new OpaqueMediaRelayStore();
  const expiry = '2026-09-25T09:00:00.000Z';

  assert.equal(store.put(chunks[0], { expiresAt: expiry }).accepted, true);
  assert.equal(store.put(chunks[0], { expiresAt: expiry }).reason, 'duplicate');
  assert.equal(store.stats({ now: new Date('2026-09-25T08:30:00.000Z') }).storedChunks, 1);

  const relayRecord = store.ready({ now: new Date('2026-09-25T08:30:00.000Z') })[0];
  assert.ok(relayRecord);
  assert.equal(Object.hasOwn(relayRecord.chunk, 'contentKey'), false);
  assert.equal(Object.hasOwn(relayRecord.chunk, 'fileName'), false);
  assert.equal(Object.hasOwn(relayRecord.chunk, 'mimeType'), false);
  assert.equal(Object.hasOwn(relayRecord.chunk, 'sha256'), false);
  assert.equal(typeof relayRecord.chunk.ciphertext, 'string');

  assert.equal(store.stats({ now: new Date('2026-09-25T09:00:00.000Z') }).storedChunks, 0);
});

test('manifest mismatch and malformed chunk cannot poison resumable assembler', () => {
  const first = createMediaPackage({
    bytes: randomBytes(90_000),
    kind: 'photo',
    mimeType: 'image/jpeg',
    fileName: 'a.jpg',
    chunkBytes: 32 * 1024
  });
  const second = createMediaPackage({
    bytes: randomBytes(90_000),
    kind: 'photo',
    mimeType: 'image/jpeg',
    fileName: 'b.jpg',
    chunkBytes: 32 * 1024
  });
  const assembler = new MediaChunkAssembler(first.manifest);

  assert.equal(assembler.ingest(second.chunks[0]).reason, 'manifest-mismatch');
  assert.equal(assembler.progress().received, 0);
  assert.throws(() => assembler.ingest({ ...first.chunks[0], nonce: 'bad' }));
  assert.equal(assembler.progress().received, 0);
});
