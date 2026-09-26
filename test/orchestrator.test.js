import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { openPayload, sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope } from '../src/protocol/envelope.js';
import { createDeliveryReceipt } from '../src/protocol/receipt.js';
import { MeshRouteTable, createRelayFrame } from '../src/protocol/mesh.js';
import { MeshDeliveryOrchestrator } from '../src/protocol/orchestrator.js';
import { MeshNetworkSimulator } from '../src/protocol/mesh-simulator.js';

function envelopeBetween(sender, recipient, text = 'resilient hello', hopLimit = 8) {
  const key = deriveSessionKey(sender, recipient.exchange.publicKey);
  return createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(key, { text }),
    hopLimit
  });
}

function resolver(table) {
  return (destinationId, { excludePeers = [], now = new Date() } = {}) =>
    table.best(destinationId, { excludePeers, now });
}

test('origin waits without route, retries same message, then completes on verified receipt', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const routes = new MeshRouteTable();
  const engine = new MeshDeliveryOrchestrator({
    localDeviceId: alice.deviceId,
    routeResolver: resolver(routes)
  });
  const envelope = envelopeBetween(alice, carol);
  engine.enqueue(envelope);

  const noRoute = await engine.attemptOrigin({
    now: new Date('2026-09-25T01:00:00Z'),
    sendToPeer: async () => true
  });
  assert.equal(noRoute.action, 'wait-route');
  assert.equal(noRoute.messageId, envelope.messageId);
  assert.equal(engine.stats().origin.retry, 1);

  routes.observe({
    destinationId: carol.deviceId,
    viaPeerId: bob.deviceId,
    hops: 2,
    quality: 30,
    ttlMs: 60_000,
    now: new Date('2026-09-25T01:00:01Z')
  });
  const sent = [];
  const retried = await engine.attemptOrigin({
    now: new Date('2026-09-25T01:00:02Z'),
    sendToPeer: async (peerId, frame) => {
      sent.push({ peerId, frame });
      return true;
    }
  });
  assert.equal(retried.action, 'await-receipt');
  assert.equal(retried.messageId, envelope.messageId);
  assert.equal(sent[0].peerId, bob.deviceId);
  assert.equal(sent[0].frame.messageId, envelope.messageId);
  assert.equal(engine.stats().origin.inflight, 1);

  const receipt = createDeliveryReceipt({
    recipientIdentity: carol,
    originalEnvelope: envelope,
    now: new Date('2026-09-25T01:00:03Z')
  });
  const accepted = engine.acceptFinalReceipt(receipt, carol.signing.publicKey);
  assert.equal(accepted.accepted, true);
  assert.equal(engine.stats().origin.delivered, 1);
});

test('relay survives next-hop failure and forwards later through alternate route', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const dave = generateDeviceIdentity();
  const erin = generateDeviceIdentity();
  const routes = new MeshRouteTable();
  const engine = new MeshDeliveryOrchestrator({
    localDeviceId: bob.deviceId,
    routeResolver: resolver(routes)
  });
  const envelope = envelopeBetween(alice, carol);
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T02:00:00Z'), ttlMs: 120_000 });

  const ingress = engine.ingest(frame, { now: new Date('2026-09-25T02:00:01Z') });
  assert.equal(ingress.action, 'stored-relay');
  assert.equal(engine.stats({ now: new Date('2026-09-25T02:00:01Z') }).relay.stored, 1);

  routes.observe({
    destinationId: carol.deviceId,
    viaPeerId: dave.deviceId,
    hops: 2,
    quality: 40,
    ttlMs: 60_000,
    now: new Date('2026-09-25T02:00:02Z')
  });
  const failed = await engine.flushRelays({
    now: new Date('2026-09-25T02:00:03Z'),
    sendToPeer: async (peerId) => peerId !== dave.deviceId
  });
  assert.equal(failed[0].action, 'retry');
  assert.equal(failed[0].viaPeerId, dave.deviceId);
  assert.equal(engine.stats({ now: new Date('2026-09-25T02:00:03Z') }).relay.retry, 1);

  routes.removeViaPeer(dave.deviceId);
  routes.observe({
    destinationId: carol.deviceId,
    viaPeerId: erin.deviceId,
    hops: 3,
    quality: 20,
    ttlMs: 60_000,
    now: new Date('2026-09-25T02:00:04Z')
  });
  const forwardedFrames = [];
  const recovered = await engine.flushRelays({
    now: new Date('2026-09-25T02:00:05Z'),
    sendToPeer: async (peerId, forwarded) => {
      forwardedFrames.push({ peerId, forwarded });
      return true;
    }
  });
  assert.equal(recovered[0].action, 'forwarded');
  assert.equal(recovered[0].viaPeerId, erin.deviceId);
  assert.equal(recovered[0].messageId, envelope.messageId);
  assert.equal(forwardedFrames[0].forwarded.envelope.messageId, envelope.messageId);
  assert.equal(forwardedFrames[0].forwarded.envelope.hopCount, 1);
  assert.equal(engine.stats({ now: new Date('2026-09-25T02:00:05Z') }).relay.total, 0);
});

test('duplicate relay frame is suppressed while stored copy remains single', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const engine = new MeshDeliveryOrchestrator({
    localDeviceId: bob.deviceId,
    routeResolver: () => null
  });
  const frame = createRelayFrame(envelopeBetween(alice, carol), {
    now: new Date('2026-09-25T03:00:00Z')
  });

  assert.equal(engine.ingest(frame, { now: new Date('2026-09-25T03:00:01Z') }).action, 'stored-relay');
  const duplicate = engine.ingest(frame, { now: new Date('2026-09-25T03:00:02Z') });
  assert.equal(duplicate.accepted, false);
  assert.equal(duplicate.reason, 'duplicate');
  assert.equal(engine.stats({ now: new Date('2026-09-25T03:00:02Z') }).relay.total, 1);
});

test('tampered final receipt never marks origin delivered', async () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const routes = new MeshRouteTable();
  routes.observe({ destinationId: bob.deviceId, viaPeerId: bob.deviceId, hops: 1, quality: 50 });
  const engine = new MeshDeliveryOrchestrator({ localDeviceId: alice.deviceId, routeResolver: resolver(routes) });
  const envelope = envelopeBetween(alice, bob);
  engine.enqueue(envelope);
  await engine.attemptOrigin({ sendToPeer: async () => true });

  const receipt = createDeliveryReceipt({ recipientIdentity: bob, originalEnvelope: envelope });
  const rejected = engine.acceptFinalReceipt({ ...receipt, state: 'read' }, bob.signing.publicKey);
  assert.equal(rejected.accepted, false);
  assert.equal(engine.stats().origin.inflight, 1);
});

test('eight-node mesh reroutes encrypted traffic across independent paths during topology chaos', () => {
  const ids = Array.from({ length: 8 }, () => generateDeviceIdentity());
  const [a, b, c, d, e, f, g, h] = ids;
  const net = new MeshNetworkSimulator({ routeTtlMs: 5_000 });
  for (const identity of ids) net.addNode({ identity, capabilities: ['ble', 'mesh-relay'] });

  net.connect(a.deviceId, b.deviceId, { quality: 85 });
  net.connect(b.deviceId, c.deviceId, { quality: 80 });
  net.connect(c.deviceId, h.deviceId, { quality: 80 });
  net.connect(a.deviceId, d.deviceId, { quality: 55 });
  net.connect(d.deviceId, e.deviceId, { quality: 55 });
  net.connect(e.deviceId, h.deviceId, { quality: 55 });
  net.connect(b.deviceId, f.deviceId, { quality: 45 });
  net.connect(f.deviceId, g.deviceId, { quality: 45 });
  net.connect(g.deviceId, h.deviceId, { quality: 45 });
  net.converge({ rounds: 6 });

  const first = envelopeBetween(a, h, 'path one');
  const firstDelivery = net.sendEnvelope(first);
  assert.equal(firstDelivery.delivered, true);
  assert.equal(firstDelivery.path[0], a.deviceId);
  assert.equal(firstDelivery.path.at(-1), h.deviceId);
  assert.throws(() => openPayload(deriveSessionKey(b, h.exchange.publicKey), firstDelivery.frame.envelope.payload));
  assert.deepEqual(openPayload(deriveSessionKey(h, a.exchange.publicKey), firstDelivery.frame.envelope.payload), { text: 'path one' });

  net.disconnect(c.deviceId, h.deviceId);
  net.advance(6_000);
  net.converge({ rounds: 6 });
  const secondDelivery = net.sendEnvelope(envelopeBetween(a, h, 'path two'));
  assert.equal(secondDelivery.delivered, true);
  assert.notEqual(secondDelivery.path.at(-2), c.deviceId);

  net.disconnect(e.deviceId, h.deviceId);
  net.advance(6_000);
  net.converge({ rounds: 6 });
  const thirdDelivery = net.sendEnvelope(envelopeBetween(a, h, 'path three'));
  assert.equal(thirdDelivery.delivered, true);
  assert.equal(thirdDelivery.path.at(-2), g.deviceId);

  net.disconnect(g.deviceId, h.deviceId);
  net.advance(6_000);
  net.converge({ rounds: 4 });
  const unavailable = net.sendEnvelope(envelopeBetween(a, h, 'store me'));
  assert.equal(unavailable.delivered, false);
  assert.equal(unavailable.reason, 'no-route');

  net.reconnect(c.deviceId, h.deviceId, { quality: 90 });
  net.converge({ rounds: 6 });
  const recovered = net.sendEnvelope(envelopeBetween(a, h, 'back online'));
  assert.equal(recovered.delivered, true);
  assert.equal(recovered.path.at(-2), c.deviceId);
});
