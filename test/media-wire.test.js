import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { createMediaPackage } from '../src/protocol/media.js';
import {
  createMediaTransportFrame,
  decodeMediaChunkWire,
  encodeMediaChunkWire
} from '../src/protocol/media-wire.js';
import {
  MockTransportAdapter,
  TransportCoordinator,
  serializedFrameBytes
} from '../src/protocol/transport-adapter.js';

const vector = JSON.parse(readFileSync(new URL('../mobile/contracts/media-chunk-wire-vector-v1.json', import.meta.url)));

test('TMC1 fixed vector is byte-for-byte deterministic', () => {
  const wire = encodeMediaChunkWire(vector.chunk);
  assert.equal(wire.toString('hex'), vector.expectedWireHex);
  assert.equal(wire.length, vector.wireBytes);
  assert.deepEqual(decodeMediaChunkWire(wire), vector.chunk);
});

test('TMC1 round-trips real encrypted media chunk without private manifest metadata', () => {
  const media = createMediaPackage({
    bytes: randomBytes(120_000),
    kind: 'photo',
    mimeType: 'image/jpeg',
    fileName: 'private-photo.jpg',
    chunkBytes: 32 * 1024
  });
  const chunk = media.chunks[0];
  const wire = encodeMediaChunkWire(chunk);
  const decoded = decodeMediaChunkWire(wire);
  assert.deepEqual(decoded, chunk);
  const ascii = wire.toString('utf8');
  assert.equal(ascii.includes(media.manifest.fileName), false);
  assert.equal(ascii.includes(media.manifest.mimeType), false);
  assert.equal(ascii.includes(media.manifest.contentKey), false);
  assert.equal(ascii.includes(media.manifest.sha256), false);
});

test('TMC1 rejects wrong magic, truncation and trailing bytes', () => {
  const media = createMediaPackage({
    bytes: randomBytes(40_000),
    kind: 'file',
    mimeType: 'application/octet-stream',
    fileName: 'wire.bin',
    chunkBytes: 32 * 1024
  });
  const wire = encodeMediaChunkWire(media.chunks[0]);
  const wrongMagic = Buffer.from(wire);
  wrongMagic[0] ^= 0xff;
  assert.throws(() => decodeMediaChunkWire(wrongMagic), /magic/);
  assert.throws(() => decodeMediaChunkWire(wire.subarray(0, wire.length - 1)), /truncated/);
  assert.throws(() => decodeMediaChunkWire(Buffer.concat([wire, Buffer.from([0])])), /trailing/);
});

test('TransportCoordinator measures actual TMC1 bytes and sends large media through wifi-local', async () => {
  const media = createMediaPackage({
    bytes: randomBytes(300_000),
    kind: 'video',
    mimeType: 'video/mp4',
    fileName: 'clip.mp4',
    chunkBytes: 256 * 1024
  });
  const frame = createMediaTransportFrame(media.chunks[0]);
  assert.equal(serializedFrameBytes(frame), frame.wire.length);

  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 32_000 });
  const wifi = new MockTransportAdapter({ id: 'wifi-local', maxPayloadBytes: 2 * 1024 * 1024 });
  ble.setPeer('peer-b', { available: true, quality: 20 });
  wifi.setPeer('peer-b', { available: true, quality: 0 });
  const coordinator = new TransportCoordinator({ adapters: [ble, wifi] });

  const result = await coordinator.send('peer-b', frame);
  assert.equal(result.sent, true);
  assert.equal(result.transportId, 'wifi-local');
  assert.equal(result.payloadBytes, frame.wire.length);
  assert.equal(ble.transmissions.length, 0);
  assert.equal(wifi.transmissions.length, 1);
});

test('small TMC1 chunk falls back from wifi-local to BLE without changing stable chunk id or wire bytes', async () => {
  const media = createMediaPackage({
    bytes: randomBytes(20_000),
    kind: 'file',
    mimeType: 'application/octet-stream',
    fileName: 'small.bin',
    chunkBytes: 16 * 1024
  });
  const frame = createMediaTransportFrame(media.chunks[0]);
  const originalWire = Buffer.from(frame.wire);
  const originalMessageId = frame.messageId;

  const wifi = new MockTransportAdapter({ id: 'wifi-local', maxPayloadBytes: 2 * 1024 * 1024 });
  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 32_000 });
  wifi.setPeer('peer-b', { available: true, quality: 10 });
  ble.setPeer('peer-b', { available: true, quality: 10 });
  wifi.failNext('peer-b', 'injected wifi failure');
  const coordinator = new TransportCoordinator({ adapters: [wifi, ble] });

  const result = await coordinator.send('peer-b', frame);
  assert.equal(result.sent, true);
  assert.equal(result.reason, 'fallback-transport');
  assert.equal(result.transportId, 'ble');
  assert.deepEqual(result.attempts.map((attempt) => attempt.transportId), ['wifi-local', 'ble']);
  assert.equal(frame.messageId, originalMessageId);
  assert.deepEqual(frame.wire, originalWire);
  assert.equal(ble.transmissions[0].frame.messageId, originalMessageId);
  assert.deepEqual(ble.transmissions[0].frame.wire, originalWire);
});
