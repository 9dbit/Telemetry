import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  deviceIdFromSigningPublicKey,
  signBytes,
  verifyBytes
} from '../src/protocol/identity.js';
import {
  decodeControlEnvelopeWire,
  encodeControlEnvelopeWire
} from '../src/protocol/control-envelope-wire.js';

const vectorPath = fileURLToPath(new URL('../mobile/contracts/control-envelope-wire-v1-vector.json', import.meta.url));
const vector = JSON.parse(readFileSync(vectorPath, 'utf8'));

const senderIdentity = {
  version: 1,
  deviceId: vector.senderDeviceId,
  signing: {
    publicKey: vector.senderSigningPublicKeySpkiBase64Url,
    privateKey: vector.senderSigningPrivateKeyPkcs8Base64Url
  },
  exchange: {
    publicKey: '',
    privateKey: ''
  }
};

const envelopeWithoutSignature = {
  protocol: vector.protocol,
  messageId: vector.messageId,
  conversationId: vector.conversationId,
  senderId: vector.senderDeviceId,
  recipientId: vector.recipientDeviceId,
  createdAt: vector.createdAt,
  hopCount: 0,
  hopLimit: vector.hopLimit,
  contentType: vector.contentType,
  payload: {
    algorithm: 'AES-256-GCM',
    keyAgreement: 'X25519-HKDF-SHA256',
    nonce: 'EBESExQVFhcYGRob',
    ciphertext: vector.ciphertextBase64Url,
    tag: vector.tagBase64Url
  }
};

test('TCE1 interop vector locks raw Ed25519 identity, canonical signature and full wire bytes', () => {
  assert.equal(
    deviceIdFromSigningPublicKey(vector.senderSigningPublicKeySpkiBase64Url),
    vector.senderDeviceId
  );
  const signature = signBytes(senderIdentity, vector.canonicalJson);
  assert.equal(signature, vector.signatureBase64Url);
  assert.equal(
    verifyBytes(vector.senderSigningPublicKeySpkiBase64Url, vector.canonicalJson, signature),
    true
  );

  const envelope = { ...envelopeWithoutSignature, signature };
  const wire = encodeControlEnvelopeWire(envelope);
  assert.equal(wire.toString('hex'), vector.wireHex);

  const decoded = decodeControlEnvelopeWire(wire);
  assert.equal(decoded.messageId, vector.messageId);
  assert.equal(decoded.senderId, vector.senderDeviceId);
  assert.equal(decoded.recipientId, vector.recipientDeviceId);
  assert.equal(decoded.signature, vector.signatureBase64Url);
  assert.equal(decoded.payload.ciphertext, vector.ciphertextBase64Url);
  assert.equal(decoded.payload.tag, vector.tagBase64Url);
});
