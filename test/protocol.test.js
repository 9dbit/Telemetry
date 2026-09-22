import test from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveSessionKey,
  deviceIdFromSigningPublicKey,
  generateDeviceIdentity
} from '../src/protocol/identity.js';
import { sealPayload, openPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, verifyEnvelope, forwardEnvelope } from '../src/protocol/envelope.js';
import { selectTransport } from '../src/protocol/router.js';
import { MessageQueue } from '../src/protocol/queue.js';
import {
  createPairingOffer,
  decodePairingQr,
  encodePairingQr,
  ReplayGuard,
  TrustRegistry,
  verifyPairingOffer
} from '../src/protocol/trust.js';
import { createDeliveryReceipt, verifyDeliveryReceipt } from '../src/protocol/receipt.js';

test('two devices derive the same session key', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const a = deriveSessionKey(alice, bob.exchange.publicKey);
  const b = deriveSessionKey(bob, alice.exchange.publicKey);
  assert.deepEqual(a, b);
});

test('device id is deterministically bound to signing public key', () => {
  const alice = generateDeviceIdentity();
  assert.equal(deviceIdFromSigningPublicKey(alice.signing.publicKey), alice.deviceId);
});

test('payload encrypts and decrypts at destination', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const a = deriveSessionKey(alice, bob.exchange.publicKey);
  const b = deriveSessionKey(bob, alice.exchange.publicKey);
  const sealed = sealPayload(a, { text: 'offline hello' });
  assert.notEqual(sealed.ciphertext, 'offline hello');
  assert.deepEqual(openPayload(b, sealed), { text: 'offline hello' });
});

test('signed envelope verifies before and after relay forwarding', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const key = deriveSessionKey(alice, bob.exchange.publicKey);
  const sealed = sealPayload(key, { text: 'secret' });
  const envelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: bob.deviceId,
    sealedPayload: sealed,
    hopLimit: 2
  });
  assert.equal(verifyEnvelope(envelope, alice.signing.publicKey), true);
  assert.equal(envelope.payload.ciphertext.includes('secret'), false);

  const forwarded = forwardEnvelope(envelope);
  assert.equal(forwarded.hopCount, 1);
  assert.equal(verifyEnvelope(forwarded, alice.signing.publicKey), true);

  const tampered = { ...forwarded, recipientId: 'tlm:device:tampered' };
  assert.equal(verifyEnvelope(tampered, alice.signing.publicKey), false);
});

test('transport router prefers local direct links and demotes unknown transports', () => {
  const route = selectTransport({
    payloadBytes: 500,
    transports: [
      { id: 'internet', available: true },
      { id: 'wifi-direct', available: true },
      { id: 'mystery-radio', available: true, quality: 20 },
      { id: 'satellite-gateway', available: true }
    ]
  });
  assert.equal(route.selected, 'wifi-direct');
});

test('message queue advances through store-and-forward lifecycle', () => {
  const queue = new MessageQueue();
  queue.enqueue({ messageId: 'm1' });
  assert.equal(queue.stats().queued, 1);
  assert.equal(queue.next().state, 'inflight');
  queue.markDelivered('m1');
  assert.equal(queue.stats().delivered, 1);
});

test('signed pairing QR round-trips and establishes trust', () => {
  const alice = generateDeviceIdentity();
  const now = new Date('2026-09-23T00:00:00.000Z');
  const offer = createPairingOffer({ identity: alice, deviceName: 'Alice Phone', now });
  const decoded = decodePairingQr(encodePairingQr(offer));
  const verification = verifyPairingOffer(decoded, { now });
  assert.equal(verification.ok, true);

  const registry = new TrustRegistry();
  const peer = registry.trust(decoded, { now });
  assert.equal(peer.deviceId, alice.deviceId);
  assert.equal(registry.isTrusted(alice.deviceId), true);
  registry.revoke(alice.deviceId, 'test', { now });
  assert.equal(registry.isTrusted(alice.deviceId), false);
});

test('pairing rejects tampering and expiry', () => {
  const alice = generateDeviceIdentity();
  const issued = new Date('2026-09-23T00:00:00.000Z');
  const offer = createPairingOffer({ identity: alice, now: issued, ttlSeconds: 60 });
  assert.equal(verifyPairingOffer({ ...offer, deviceName: 'Mallory' }, { now: issued }).ok, false);
  assert.equal(verifyPairingOffer(offer, { now: new Date('2026-09-23T00:02:00.000Z') }).reason, 'expired');
});

test('replay guard rejects a duplicate nonce until expiry', () => {
  const guard = new ReplayGuard();
  const now = new Date('2026-09-23T00:00:00.000Z');
  const expiresAt = new Date('2026-09-23T00:05:00.000Z');
  assert.equal(guard.checkAndRemember('nonce-1', { now, expiresAt }).accepted, true);
  assert.equal(guard.checkAndRemember('nonce-1', { now, expiresAt }).reason, 'duplicate');
});

test('recipient signs a verifiable delivery receipt', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const key = deriveSessionKey(alice, bob.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: bob.deviceId,
    sealedPayload: sealPayload(key, { text: 'receipt me' })
  });
  const receipt = createDeliveryReceipt({ recipientIdentity: bob, originalEnvelope: envelope });
  assert.equal(verifyDeliveryReceipt(receipt, bob.signing.publicKey), true);
  assert.equal(verifyDeliveryReceipt({ ...receipt, state: 'read' }, bob.signing.publicKey), false);
});
