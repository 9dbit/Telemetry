import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope } from '../src/protocol/envelope.js';
import { createRelayFrame, MeshRouteTable } from '../src/protocol/mesh.js';
import { MeshDeliveryOrchestrator } from '../src/protocol/orchestrator.js';
import { MockTransportAdapter, TransportCoordinator, serializedFrameBytes } from '../src/protocol/transport-adapter.js';

function envelopeBetween(sender, recipient, text) {
  const key = deriveSessionKey(sender, recipient.exchange.publicKey);
  return createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(key, { text }),
    hopLimit: 8
  });
}

test('1 MB encrypted payload is eligible for wifi while BLE payload limit removes BLE candidate', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const envelope = envelopeBetween(alice, bob, 'w'.repeat(1024 * 1024));
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T05:00:00Z'), ttlMs: 120_000 });

  const wifi = new MockTransportAdapter({ id: 'wifi-direct', maxPayloadBytes: 2 * 1024 * 1024 });
  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 512 * 1024 });
  wifi.setPeer(bob.deviceId, { available: true, quality: 60 });
  ble.setPeer(bob.deviceId, { available: true, quality: 90 });
  const coordinator = new TransportCoordinator({ adapters: [ble, wifi] });

  assert.ok(serializedFrameBytes(frame) > 1024 * 1024);
  const decision = coordinator.candidates(bob.deviceId, frame);
  assert.equal(decision.selected, 'wifi-direct');
  assert.equal(decision.candidates.some((candidate) => candidate.id === 'ble'), false);

  const result = await coordinator.send(bob.deviceId, frame);
  assert.equal(result.sent, true);
  assert.equal(result.transportId, 'wifi-direct');
  assert.equal(wifi.transmissions[0].frame.messageId, envelope.messageId);
  assert.equal(JSON.stringify(frame).includes('w'.repeat(128)), false);
});

test('origin retries the same messageId after all transports fail and succeeds when wifi recovers', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const routes = new MeshRouteTable();
  const routeNow = new Date('2026-09-25T05:10:00Z');
  routes.observe({
    destinationId: bob.deviceId,
    viaPeerId: bob.deviceId,
    hops: 1,
    quality: 80,
    ttlMs: 180_000,
    now: routeNow
  });

  const wifi = new MockTransportAdapter({ id: 'wifi-direct', maxPayloadBytes: 2 * 1024 * 1024 });
  const ble = new MockTransportAdapter({ id: 'ble', maxPayloadBytes: 512 * 1024 });
  wifi.setPeer(bob.deviceId, { available: true, quality: 70 });
  ble.setPeer(bob.deviceId, { available: true, quality: 30 });
  wifi.failNext(bob.deviceId, 'wifi dropped mid-send');
  ble.failNext(bob.deviceId, 'ble fallback unavailable');
  const coordinator = new TransportCoordinator({ adapters: [wifi, ble] });

  const envelope = envelopeBetween(alice, bob, 'retry me without changing identity');
  const engine = new MeshDeliveryOrchestrator({
    localDeviceId: alice.deviceId,
    routeResolver: (destinationId, options) => routes.best(destinationId, options)
  });
  engine.enqueue(envelope);

  const observedMessageIds = [];
  const sendToPeer = async (peerId, frame, context) => {
    observedMessageIds.push(frame.messageId);
    const result = await coordinator.send(peerId, frame, { context });
    return result.sent;
  };

  const first = await engine.attemptOrigin({
    now: new Date('2026-09-25T05:10:10Z'),
    sendToPeer
  });
  assert.equal(first.action, 'retry');
  assert.equal(first.messageId, envelope.messageId);
  assert.equal(engine.stats({ now: new Date('2026-09-25T05:10:10Z') }).origin.retry, 1);

  const second = await engine.attemptOrigin({
    now: new Date('2026-09-25T05:10:20Z'),
    sendToPeer
  });
  assert.equal(second.action, 'await-receipt');
  assert.equal(second.messageId, envelope.messageId);
  assert.deepEqual(observedMessageIds, [envelope.messageId, envelope.messageId]);
  assert.equal(wifi.transmissions.length, 1);
  assert.equal(wifi.transmissions[0].frame.messageId, envelope.messageId);
});
