import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { openPayload, sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope } from '../src/protocol/envelope.js';
import { MeshRouteTable } from '../src/protocol/mesh.js';
import {
  createRouteAdvertisement,
  ingestRouteAdvertisement,
  MAX_ADVERTISED_ROUTES,
  RouteAdvertisementGuard,
  routesForAdvertisement,
  verifyRouteAdvertisement
} from '../src/protocol/route-advertisement.js';
import { MeshNetworkSimulator } from '../src/protocol/mesh-simulator.js';

test('signed route advertisement is identity-bound, bounded, and rejects stale sequence', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const aliceRoutes = new MeshRouteTable();
  const bobRoutes = new MeshRouteTable();
  const now = new Date('2026-09-25T00:00:00.000Z');

  aliceRoutes.observe({
    destinationId: carol.deviceId,
    viaPeerId: carol.deviceId,
    hops: 1,
    quality: 72,
    now
  });

  const routes = routesForAdvertisement(aliceRoutes, {
    localDeviceId: alice.deviceId,
    toPeerId: bob.deviceId,
    now
  });
  const advertisement = createRouteAdvertisement({
    identity: alice,
    sequence: 7,
    routes,
    capabilities: ['ble', 'mesh-relay', 'ble'],
    now
  });

  assert.equal(verifyRouteAdvertisement(advertisement, alice.signing.publicKey, { now }).ok, true);
  assert.equal(verifyRouteAdvertisement(advertisement, bob.signing.publicKey, { now }).reason, 'identity-mismatch');
  assert.deepEqual(advertisement.capabilities, ['ble', 'mesh-relay']);
  assert.ok(advertisement.routes.length <= MAX_ADVERTISED_ROUTES);

  const tampered = {
    ...advertisement,
    routes: advertisement.routes.map((route, index) => index === 0 ? { ...route, quality: -100 } : route)
  };
  assert.equal(verifyRouteAdvertisement(tampered, alice.signing.publicKey, { now }).reason, 'bad-signature');

  const guard = new RouteAdvertisementGuard();
  const accepted = ingestRouteAdvertisement(bobRoutes, advertisement, {
    fromPeerId: alice.deviceId,
    signingPublicKey: alice.signing.publicKey,
    localDeviceId: bob.deviceId,
    guard,
    linkQuality: 60,
    now
  });
  assert.equal(accepted.accepted, true);
  assert.equal(bobRoutes.best(alice.deviceId, { now }).hops, 1);
  assert.equal(bobRoutes.best(alice.deviceId, { now }).quality, 60);
  assert.equal(bobRoutes.best(carol.deviceId, { now }).hops, 2);

  const stale = ingestRouteAdvertisement(bobRoutes, advertisement, {
    fromPeerId: alice.deviceId,
    signingPublicKey: alice.signing.publicKey,
    localDeviceId: bob.deviceId,
    guard,
    linkQuality: 60,
    now
  });
  assert.deepEqual(stale, { accepted: false, reason: 'stale-sequence', routesAccepted: 0 });

  assert.throws(() => createRouteAdvertisement({
    identity: alice,
    sequence: 8,
    routes: Array.from({ length: MAX_ADVERTISED_ROUTES + 1 }, (_, index) => ({
      destinationId: `peer-${index}`,
      hops: 1,
      quality: 0,
      transport: 'ble'
    })),
    now
  }), /routes exceed max/);
});

test('five-node mesh switches routes after expiry and restores preferred path', () => {
  const [alice, bob, carol, diana, erin] = Array.from({ length: 5 }, () => generateDeviceIdentity());
  const mesh = new MeshNetworkSimulator({ routeTtlMs: 15_000 });
  for (const identity of [alice, bob, carol, diana, erin]) {
    mesh.addNode({ identity, capabilities: ['ble', 'mesh-relay'] });
  }

  mesh.connect(alice.deviceId, bob.deviceId, { quality: 90 });
  mesh.connect(bob.deviceId, carol.deviceId, { quality: 90 });
  mesh.connect(carol.deviceId, erin.deviceId, { quality: 90 });
  mesh.connect(alice.deviceId, diana.deviceId, { quality: 25 });
  mesh.connect(diana.deviceId, erin.deviceId, { quality: 25 });
  mesh.converge({ rounds: 5 });

  assert.deepEqual(mesh.routePath(alice.deviceId, erin.deviceId), [
    alice.deviceId,
    bob.deviceId,
    carol.deviceId,
    erin.deviceId
  ]);
  assert.equal(mesh.bestRoute(alice.deviceId, erin.deviceId).viaPeerId, bob.deviceId);

  const sessionKey = deriveSessionKey(alice, erin.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: erin.deviceId,
    sealedPayload: sealPayload(sessionKey, { text: 'five node secret' }),
    hopLimit: 8
  });
  const firstDelivery = mesh.sendEnvelope(envelope, { fromId: alice.deviceId });
  assert.equal(firstDelivery.delivered, true);
  assert.deepEqual(firstDelivery.path, [alice.deviceId, bob.deviceId, carol.deviceId, erin.deviceId]);

  const bobWrongKey = deriveSessionKey(bob, erin.exchange.publicKey);
  assert.throws(() => openPayload(bobWrongKey, envelope.payload));
  const erinKey = deriveSessionKey(erin, alice.exchange.publicKey);
  assert.deepEqual(openPayload(erinKey, envelope.payload), { text: 'five node secret' });

  mesh.disconnect(bob.deviceId, carol.deviceId);
  mesh.advance(16_000);
  mesh.converge({ rounds: 5 });

  assert.deepEqual(mesh.routePath(alice.deviceId, erin.deviceId), [
    alice.deviceId,
    diana.deviceId,
    erin.deviceId
  ]);
  assert.equal(mesh.bestRoute(alice.deviceId, erin.deviceId).viaPeerId, diana.deviceId);

  mesh.reconnect(bob.deviceId, carol.deviceId, { quality: 95 });
  mesh.converge({ rounds: 5 });

  assert.deepEqual(mesh.routePath(alice.deviceId, erin.deviceId), [
    alice.deviceId,
    bob.deviceId,
    carol.deviceId,
    erin.deviceId
  ]);
});

test('split horizon prevents advertising a learned route straight back to its source peer', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const table = new MeshRouteTable();
  const now = new Date('2026-09-25T00:00:00.000Z');
  table.observe({
    destinationId: carol.deviceId,
    viaPeerId: bob.deviceId,
    hops: 2,
    quality: 55,
    now
  });

  const towardBob = routesForAdvertisement(table, {
    localDeviceId: alice.deviceId,
    toPeerId: bob.deviceId,
    now
  });
  assert.equal(towardBob.some((route) => route.destinationId === carol.deviceId), false);

  const towardOtherPeer = routesForAdvertisement(table, {
    localDeviceId: alice.deviceId,
    toPeerId: 'some-other-peer',
    now
  });
  assert.equal(towardOtherPeer.some((route) => route.destinationId === carol.deviceId), true);
});
