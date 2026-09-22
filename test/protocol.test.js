import test from 'node:test';
import assert from 'node:assert/strict';
import { generateDeviceIdentity, deriveSessionKey } from '../src/protocol/identity.js';
import { sealPayload, openPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, verifyEnvelope, forwardEnvelope } from '../src/protocol/envelope.js';
import { selectTransport } from '../src/protocol/router.js';
import { MessageQueue } from '../src/protocol/queue.js';

test('two devices derive the same session key', () => {
  const alice = generateDeviceIdentity();
  const bob = generateDeviceIdentity();
  const a = deriveSessionKey(alice, bob.exchange.publicKey);
  const b = deriveSessionKey(bob, alice.exchange.publicKey);
  assert.deepEqual(a, b);
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
