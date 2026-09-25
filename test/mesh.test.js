import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { openPayload, sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, verifyEnvelope } from '../src/protocol/envelope.js';
import {
  createRelayFrame,
  forwardRelayFrame,
  ingestRelayFrame,
  MeshRouteTable,
  RelayDedupe,
  RelayStore
} from '../src/protocol/mesh.js';

function encryptedEnvelope({ sender, recipient, text = 'mesh secret', hopLimit = 4 }) {
  const key = deriveSessionKey(sender, recipient.exchange.publicKey);
  return createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(key, { text }),
    hopLimit
  });
}

test('A → B → C relay keeps sender signature valid and relay cannot decrypt plaintext', () => {
  const alice = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const envelope = encryptedEnvelope({ sender: alice, recipient: carol });
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T00:00:00Z') });

  const relayIngress = ingestRelayFrame(frame, {
    localDeviceId: relay.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T00:00:01Z')
  });
  assert.deepEqual(relayIngress, { accepted: true, action: 'relay', reason: 'relay-eligible' });

  const relayWrongKey = deriveSessionKey(relay, carol.exchange.publicKey);
  assert.throws(() => openPayload(relayWrongKey, envelope.payload));

  const forwarded = forwardRelayFrame(frame, {
    relayDeviceId: relay.deviceId,
    now: new Date('2026-09-25T00:00:02Z')
  });
  assert.equal(forwarded.envelope.hopCount, 1);
  assert.deepEqual(forwarded.relayPath, [alice.deviceId, relay.deviceId]);
  assert.equal(verifyEnvelope(forwarded.envelope, alice.signing.publicKey), true);

  const carolIngress = ingestRelayFrame(forwarded, {
    localDeviceId: carol.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T00:00:03Z')
  });
  assert.equal(carolIngress.action, 'deliver-local');

  const carolKey = deriveSessionKey(carol, alice.exchange.publicKey);
  assert.deepEqual(openPayload(carolKey, forwarded.envelope.payload), { text: 'mesh secret' });
});

test('relay duplicate suppression drops repeated ciphertext without redelivery', () => {
  const alice = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const frame = createRelayFrame(encryptedEnvelope({ sender: alice, recipient: carol }));
  const dedupe = new RelayDedupe();
  const now = new Date('2026-09-25T00:00:00Z');

  assert.equal(ingestRelayFrame(frame, { localDeviceId: relay.deviceId, dedupe, now }).accepted, true);
  const repeated = ingestRelayFrame(frame, { localDeviceId: relay.deviceId, dedupe, now });
  assert.deepEqual(repeated, { accepted: false, action: 'drop', reason: 'duplicate' });
});

test('mesh relay blocks loops, expired frames, and exhausted hop limits', () => {
  const alice = generateDeviceIdentity();
  const relayA = generateDeviceIdentity();
  const relayB = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const now = new Date('2026-09-25T00:00:00Z');

  const expiring = createRelayFrame(encryptedEnvelope({ sender: alice, recipient: carol }), { now, ttlMs: 1000 });
  const expired = ingestRelayFrame(expiring, {
    localDeviceId: relayA.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T00:00:02Z')
  });
  assert.equal(expired.reason, 'expired');

  const oneHop = createRelayFrame(encryptedEnvelope({ sender: alice, recipient: carol, hopLimit: 1 }), { now });
  const forwarded = forwardRelayFrame(oneHop, { relayDeviceId: relayA.deviceId, now });
  assert.throws(() => forwardRelayFrame(forwarded, { relayDeviceId: relayB.deviceId, now }), /hop limit reached/);
  assert.throws(() => forwardRelayFrame(forwarded, { relayDeviceId: relayA.deviceId, now }), /relay loop detected/);
});

test('route table prefers stronger lower-hop route and expires stale routes', () => {
  const table = new MeshRouteTable();
  const now = new Date('2026-09-25T00:00:00Z');
  table.observe({ destinationId: 'carol', viaPeerId: 'peer-a', hops: 2, quality: 10, ttlMs: 30_000, now });
  table.observe({ destinationId: 'carol', viaPeerId: 'peer-b', hops: 1, quality: 5, ttlMs: 30_000, now });
  table.observe({ destinationId: 'carol', viaPeerId: 'peer-c', hops: 1, quality: 25, ttlMs: 1000, now });

  assert.equal(table.best('carol', { now }).viaPeerId, 'peer-c');
  assert.equal(table.best('carol', { excludePeers: ['peer-c'], now }).viaPeerId, 'peer-b');
  assert.equal(table.best('carol', { now: new Date('2026-09-25T00:00:02Z') }).viaPeerId, 'peer-b');
});

test('relay store keeps ciphertext until a route becomes available', () => {
  const alice = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const now = new Date('2026-09-25T00:00:00Z');
  const frame = createRelayFrame(encryptedEnvelope({ sender: alice, recipient: carol }), { now, ttlMs: 60_000 });
  const store = new RelayStore();
  const routes = new MeshRouteTable();

  store.put(frame, { now });
  assert.equal(store.stats({ now }).stored, 1);
  assert.equal(routes.best(carol.deviceId, { now }), null);

  routes.observe({
    destinationId: carol.deviceId,
    viaPeerId: relay.deviceId,
    hops: 1,
    quality: 30,
    now
  });
  const ready = store.ready({ now });
  assert.equal(ready.length, 1);
  assert.equal(routes.best(carol.deviceId, { now }).viaPeerId, relay.deviceId);

  store.markInflight(frame.messageId, { now });
  assert.equal(store.stats({ now }).inflight, 1);
  store.remove(frame.messageId);
  assert.equal(store.stats({ now }).total, 0);
});
