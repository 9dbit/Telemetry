import test from 'node:test';
import assert from 'node:assert/strict';
import { generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload, openPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, forwardEnvelope, verifyEnvelope } from '../src/protocol/envelope.js';
import {
  decodeControlEnvelopeWire,
  encodeControlEnvelopeWire,
  verifyControlEnvelopeWire
} from '../src/protocol/control-envelope-wire.js';

function controlEnvelope() {
  const sender = generateDeviceIdentity();
  const recipient = generateDeviceIdentity();
  const sessionKey = Buffer.alloc(32, 0x5a);
  const envelope = createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(sessionKey, {
      kind: 'control-test',
      value: 'hello'
    }),
    conversationId: 'conversation-tce1-0001',
    contentType: 'application/telemetry+control',
    hopLimit: 8
  });
  return { sender, recipient, sessionKey, envelope };
}

test('TCE1 round-trips a signed encrypted control envelope byte-for-byte semantically', () => {
  const { sender, sessionKey, envelope } = controlEnvelope();
  const wire = encodeControlEnvelopeWire(envelope);
  assert.equal(wire.subarray(0, 4).toString('ascii'), 'TCE1');

  const decoded = decodeControlEnvelopeWire(wire);
  assert.equal(decoded.messageId, envelope.messageId);
  assert.equal(decoded.conversationId, envelope.conversationId);
  assert.equal(decoded.senderId, envelope.senderId);
  assert.equal(decoded.recipientId, envelope.recipientId);
  assert.equal(decoded.contentType, envelope.contentType);
  assert.equal(decoded.signature, envelope.signature);
  assert.deepEqual(decoded.payload, envelope.payload);
  assert.equal(verifyEnvelope(decoded, sender.signing.publicKey), true);
  assert.deepEqual(openPayload(sessionKey, decoded.payload), {
    kind: 'control-test',
    value: 'hello'
  });
});

test('TCE1 keeps sender signature valid when mesh hop metadata advances outside the control envelope', () => {
  const { sender, envelope } = controlEnvelope();
  const forwarded = forwardEnvelope(envelope);
  assert.equal(forwarded.hopCount, 1);
  const decoded = decodeControlEnvelopeWire(encodeControlEnvelopeWire(forwarded));
  assert.equal(decoded.hopCount, 0);
  assert.equal(decoded.hopLimit, envelope.hopLimit);
  assert.equal(verifyEnvelope(decoded, sender.signing.publicKey), true);
});

test('TCE1 rejects truncation, trailing bytes and signature tampering', () => {
  const { sender, envelope } = controlEnvelope();
  const wire = encodeControlEnvelopeWire(envelope);
  assert.throws(() => decodeControlEnvelopeWire(wire.subarray(0, wire.length - 1)), /truncated|signature/i);
  assert.throws(() => decodeControlEnvelopeWire(Buffer.concat([wire, Buffer.from([0])])), /trailing/i);

  const tampered = Buffer.from(wire);
  tampered[tampered.length - 1] ^= 0x01;
  const result = verifyControlEnvelopeWire(tampered, sender.signing.publicKey);
  assert.equal(result.verified, false);
});
