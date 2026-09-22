import { signBytes, verifyBytes } from './identity.js';

export const RECEIPT_VERSION = 'telemetry/receipt/0.2';
const RECEIPT_STATES = new Set(['received', 'delivered', 'read', 'rejected']);

function canonicalReceiptData(receipt) {
  return JSON.stringify({
    version: receipt.version,
    messageId: receipt.messageId,
    originalSenderId: receipt.originalSenderId,
    recipientId: receipt.recipientId,
    state: receipt.state,
    at: receipt.at
  });
}

export function createDeliveryReceipt({ recipientIdentity, originalEnvelope, state = 'delivered', now = new Date() }) {
  if (!recipientIdentity?.deviceId) throw new Error('recipientIdentity is required');
  if (!originalEnvelope?.messageId) throw new Error('originalEnvelope is required');
  if (originalEnvelope.recipientId !== recipientIdentity.deviceId) {
    throw new Error('receipt signer is not the envelope recipient');
  }
  if (!RECEIPT_STATES.has(state)) throw new Error('unsupported receipt state');

  const receipt = {
    version: RECEIPT_VERSION,
    messageId: originalEnvelope.messageId,
    originalSenderId: originalEnvelope.senderId,
    recipientId: recipientIdentity.deviceId,
    state,
    at: new Date(now).toISOString()
  };

  return {
    ...receipt,
    signature: signBytes(recipientIdentity, canonicalReceiptData(receipt))
  };
}

export function verifyDeliveryReceipt(receipt, recipientSigningPublicKey) {
  if (!receipt?.signature || !RECEIPT_STATES.has(receipt.state)) return false;
  return verifyBytes(recipientSigningPublicKey, canonicalReceiptData(receipt), receipt.signature);
}
