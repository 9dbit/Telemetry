import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope } from '../src/protocol/envelope.js';
import { createRelayFrame, MeshRouteTable } from '../src/protocol/mesh.js';
import { MeshDeliveryOrchestrator } from '../src/protocol/orchestrator.js';
import {
  MockTransportAdapter,
  TransportCoordinator,
  serializedFrameBytes
} from '../src/protocol/transport-adapter.js';

function frameBetween(sender, recipient, text = 'wifi secret payload') {
  const key = deriveSessionKey(sender, recipient.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(key, { text }),
    hopLimit: 8
  });
  return createRelayFrame(envelope, { now: new Date('2026-09-25T04:00:00Z'), ttlMs: 120_000 });
}

function buildCoordinator(peerId) {
  const wifi = new MockTransportAdapter({ id: 'wifi-direct', maxPayloadBytes: 2 * 1024 * 1024 });
  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 512 * 1024 });
  wifi.setPeer(peerId, { available: true, quality: 70 });
  ble.setPeer(peerId, { available: true, quality: 30 });
  const coordinator = new TransportCoordinator({ adapters: [ble, wifi] });
  return { coordinator, wifi, ble };
}

test('wifi is preferred over BLE while carrying the exact same encrypted frame', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const frame = frameBetween(alice, bob, 'secret must stay encrypted');
  const { coordinator, wifi, ble } = buildCoordinator(bob.deviceId);

  const result = await coordinator.send(bob.deviceId, frame);
  assert.equal(result.sent, true);
  assert.equal(result.transportId, 'wifi-direct');
  assert.equal(wifi.transmissions.length, 1);
  assert.equal(ble.transmissions.length, 0);
  assert.equal(wifi.transmissions[0].frame, frame);
  assert.equal(wifi.transmissions[0].frame.messageId, frame.messageId);
  assert.equal(JSON.stringify(frame).includes('secret must stay encrypted'), false);
});

test('wifi failure falls back to BLE without changing messageId or frame bytes', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const frame = frameBetween(alice, bob);
  const before = JSON.stringify(frame);
  const { coordinator, wifi, ble } = buildCoordinator(bob.deviceId);
  wifi.failNext(bob.deviceId, 'wifi path dropped');

  const result = await coordinator.send(bob.deviceId, frame);
  assert.equal(result.sent, true);
  assert.equal(result.reason, 'fallback-transport');
  assert.equal(result.transportId, 'ble');
  assert.deepEqual(result.attempts.map((item) => item.transportId), ['wifi-direct', 'ble']);
  assert.equal(ble.transmissions[0].frame.messageId, frame.messageId);
  assert.equal(JSON.stringify(frame), before);
});

test('transport policy prefers wifi for larger payloads and respects adapter payload limits', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const largeText = 'x'.repeat(256 * 1024);
  const frame = frameBetween(alice, bob, largeText);
  const wifi = new MockTransportAdapter({ id: 'wifi-direct', maxPayloadBytes: 2 * 1024 * 1024 });
  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 64 * 1024 });
  wifi.setPeer(bob.deviceId, { available: true, quality: 50 });
  ble.setPeer(bob.deviceId, { available: true, quality: 90 });
  const coordinator = new TransportCoordinator({ adapters: [ble, wifi] });

  assert.ok(serializedFrameBytes(frame) > 64 * 1024);
  const decision = coordinator.candidates(bob.deviceId, frame);
  assert.equal(decision.selected, 'wifi-direct');
  assert.equal(decision.candidates.some((candidate) => candidate.id === 'ble'), false);
  const result = await coordinator.send(bob.deviceId, frame);
  assert.equal(result.transportId, 'wifi-direct');
});

test('restored wifi becomes preferred again after a temporary BLE fallback', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const { coordinator, wifi, ble } = buildCoordinator(bob.deviceId);

  wifi.setAvailable(bob.deviceId, false);
  const first = await coordinator.send(bob.deviceId, frameBetween(alice, bob, 'during outage'));
  assert.equal(first.transportId, 'ble');

  wifi.setAvailable(bob.deviceId, true);
  const second = await coordinator.send(bob.deviceId, frameBetween(alice, bob, 'after restore'));
  assert.equal(second.transportId, 'wifi-direct');
  assert.equal(ble.transmissions.length, 1);
  assert.equal(wifi.transmissions.length, 1);
});

test('orchestrator uses transport coordinator without becoming transport-aware', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const routes = new MeshRouteTable();
  routes.observe({ destinationId: bob.deviceId, viaPeerId: bob.deviceId, hops: 1, quality: 80 });
  const { coordinator, wifi, ble } = buildCoordinator(bob.deviceId);
  wifi.failNext(bob.deviceId, 'wifi interrupted');

  const key = deriveSessionKey(alice, bob.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: bob.deviceId,
    sealedPayload: sealPayload(key, { text: 'same pipeline' }),
    hopLimit: 4
  });
  const engine = new MeshDeliveryOrchestrator({
    localDeviceId: alice.deviceId,
    routeResolver: (destinationId, options) => routes.best(destinationId, options)
  });
  engine.enqueue(envelope);

  const result = await engine.attemptOrigin({
    now: new Date('2026-09-25T04:01:00Z'),
    sendToPeer: async (peerId, frame, context) => {
      const sent = await coordinator.send(peerId, frame, { context });
      return sent.sent;
    }
  });

  assert.equal(result.action, 'await-receipt');
  assert.equal(ble.transmissions.length, 1);
  assert.equal(ble.transmissions[0].frame.messageId, envelope.messageId);
  assert.equal(wifi.transmissions.length, 0);
});

test('burst of 20 messages chooses wifi with one transmission per stable messageId', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const { coordinator, wifi, ble } = buildCoordinator(bob.deviceId);
  const ids = new Set();

  for (let index = 0; index < 20; index += 1) {
    const frame = frameBetween(alice, bob, `burst-${index}`);
    ids.add(frame.messageId);
    const result = await coordinator.send(bob.deviceId, frame);
    assert.equal(result.sent, true);
    assert.equal(result.transportId, 'wifi-direct');
  }

  assert.equal(ids.size, 20);
  assert.equal(wifi.transmissions.length, 20);
  assert.equal(ble.transmissions.length, 0);
  assert.equal(new Set(wifi.transmissions.map((item) => item.frame.messageId)).size, 20);
});
