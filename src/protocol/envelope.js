import { randomUUID } from 'node:crypto';
import { signBytes, verifyBytes } from './identity.js';

export const PROTOCOL_VERSION = 'telemetry/0.1';

function canonicalEnvelopeData(envelope) {
  return JSON.stringify({
    protocol: envelope.protocol,
    messageId: envelope.messageId,
    conversationId: envelope.conversationId,
    senderId: envelope.senderId,
    recipientId: envelope.recipientId,
    createdAt: envelope.createdAt,
    hopCount: envelope.hopCount,
    hopLimit: envelope.hopLimit,
    contentType: envelope.contentType,
    payload: envelope.payload
  });
}

export function createSignedEnvelope({
  senderIdentity,
  recipientId,
  sealedPayload,
  conversationId = randomUUID(),
  contentType = 'application/telemetry+json',
  hopLimit = 8
}) {
  if (!senderIdentity?.deviceId) throw new Error('senderIdentity is required');
  if (!recipientId) throw new Error('recipientId is required');
  if (!sealedPayload?.ciphertext) throw new Error('sealedPayload is required');
  if (!Number.isInteger(hopLimit) || hopLimit < 1 || hopLimit > 32) {
    throw new Error('hopLimit must be an integer between 1 and 32');
  }

  const envelope = {
    protocol: PROTOCOL_VERSION,
    messageId: randomUUID(),
    conversationId,
    senderId: senderIdentity.deviceId,
    recipientId,
    createdAt: new Date().toISOString(),
    hopCount: 0,
    hopLimit,
    contentType,
    payload: sealedPayload
  };

  return {
    ...envelope,
    signature: signBytes(senderIdentity, canonicalEnvelopeData(envelope))
  };
}

export function verifyEnvelope(envelope, senderSigningPublicKey) {
  if (!envelope?.signature) return false;
  return verifyBytes(
    senderSigningPublicKey,
    canonicalEnvelopeData(envelope),
    envelope.signature
  );
}

export function forwardEnvelope(envelope) {
  if (envelope.hopCount >= envelope.hopLimit) {
    throw new Error('hop limit reached');
  }
  return { ...envelope, hopCount: envelope.hopCount + 1 };
}

export function envelopeRelayView(envelope) {
  return {
    protocol: envelope.protocol,
    messageId: envelope.messageId,
    senderId: envelope.senderId,
    recipientId: envelope.recipientId,
    createdAt: envelope.createdAt,
    hopCount: envelope.hopCount,
    hopLimit: envelope.hopLimit,
    contentType: envelope.contentType,
    payloadBytes: Buffer.byteLength(envelope.payload.ciphertext, 'base64url')
  };
}
