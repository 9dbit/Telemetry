import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, envelopeRelayView } from '../src/protocol/envelope.js';
import { createRelayFrame, forwardRelayFrame, ingestRelayFrame, RelayDedupe } from '../src/protocol/mesh.js';
import { MeshDeliveryOrchestrator } from '../src/protocol/orchestrator.js';
import {
  createEncryptedReceiptEnvelope,
  openEncryptedReceiptEnvelope,
  RECEIPT_CONTROL_CONTENT_TYPE
} from '../src/protocol/receipt-envelope.js';

test('final signed receipt returns C to A through opaque relay B', () => {
  const alice = generateDeviceIdentity();
  const relayB = generateDeviceIdentity();
  const carol = generateDeviceIdentity();

  const aliceKey = deriveSessionKey(alice, carol.exchange.publicKey);
  const carolKey = deriveSessionKey(carol, alice.exchange.publicKey);
  assert.deepEqual(aliceKey, carolKey);

  const originalEnvelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: carol.deviceId,
    sealedPayload: sealPayload(aliceKey, { text: 'mesh delivery' }),
    hopLimit: 6
  });

  const origin = new MeshDeliveryOrchestrator({ localDeviceId: alice.deviceId });
  origin.enqueue(originalEnvelope);

  const { envelope: receiptEnvelope, receipt } = createEncryptedReceiptEnvelope({
    recipientIdentity: carol,
    originalEnvelope,
    sessionKey: carolKey,
    state: 'delivered',
    now: new Date('2026-09-25T07:00:00.000Z')
  });

  assert.equal(receiptEnvelope.senderId, carol.deviceId);
  assert.equal(receiptEnvelope.recipientId, alice.deviceId);
  assert.equal(receiptEnvelope.contentType, RECEIPT_CONTROL_CONTENT_TYPE);
  assert.notEqual(receiptEnvelope.messageId, originalEnvelope.messageId);
  assert.equal(Object.hasOwn(receiptEnvelope.payload, 'receipt'), false);

  const relayView = envelopeRelayView(receiptEnvelope);
  assert.equal(Object.hasOwn(relayView, 'state'), false);
  assert.equal(Object.hasOwn(relayView, 'originalMessageId'), false);

  const frame = createRelayFrame(receiptEnvelope, {
    now: new Date('2026-09-25T07:00:01.000Z')
  });
  const atB = ingestRelayFrame(frame, {
    localDeviceId: relayB.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T07:00:02.000Z')
  });
  assert.equal(atB.accepted, true);
  assert.equal(atB.action, 'relay');

  const forwarded = forwardRelayFrame(frame, {
    relayDeviceId: relayB.deviceId,
    now: new Date('2026-09-25T07:00:03.000Z')
  });
  const atA = ingestRelayFrame(forwarded, {
    localDeviceId: alice.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T07:00:04.000Z')
  });
  assert.equal(atA.accepted, true);
  assert.equal(atA.action, 'deliver-local');

  const openedReceipt = openEncryptedReceiptEnvelope({
    envelope: forwarded.envelope,
    receiptSignerPublicKey: carol.signing.publicKey,
    sessionKey: aliceKey
  });
  assert.deepEqual(openedReceipt, receipt);
  assert.equal(openedReceipt.messageId, originalEnvelope.messageId);

  const accepted = origin.acceptFinalReceipt(openedReceipt, carol.signing.publicKey);
  assert.equal(accepted.accepted, true);
  assert.equal(accepted.reason, 'verified-final-receipt');
  assert.equal(origin.stats().origin.delivered, 1);
});

test('receipt control envelope rejects outer tampering and wrong session key', () => {
  const alice = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const mallory = generateDeviceIdentity();
  const key = deriveSessionKey(alice, carol.exchange.publicKey);

  const originalEnvelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: carol.deviceId,
    sealedPayload: sealPayload(key, { text: 'hello' })
  });
  const { envelope } = createEncryptedReceiptEnvelope({
    recipientIdentity: carol,
    originalEnvelope,
    sessionKey: key
  });

  assert.throws(() => openEncryptedReceiptEnvelope({
    envelope: { ...envelope, recipientId: mallory.deviceId },
    receiptSignerPublicKey: carol.signing.publicKey,
    sessionKey: key
  }), /invalid control envelope signature/);

  const wrongKey = deriveSessionKey(alice, mallory.exchange.publicKey);
  assert.throws(() => openEncryptedReceiptEnvelope({
    envelope,
    receiptSignerPublicKey: carol.signing.publicKey,
    sessionKey: wrongKey
  }));
});
