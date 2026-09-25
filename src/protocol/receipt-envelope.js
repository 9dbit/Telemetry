import { sealPayload, openPayload } from './crypto.js';
import { createSignedEnvelope, verifyEnvelope } from './envelope.js';
import { createDeliveryReceipt, verifyDeliveryReceipt } from './receipt.js';

export const RECEIPT_CONTROL_CONTENT_TYPE = 'application/telemetry+control';
const RECEIPT_CONTROL_KIND = 'delivery-receipt';

export function createEncryptedReceiptEnvelope({
  recipientIdentity,
  originalEnvelope,
  sessionKey,
  state = 'delivered',
  now = new Date()
}) {
  if (!recipientIdentity?.deviceId) throw new Error('recipientIdentity is required');
  if (!originalEnvelope?.messageId) throw new Error('originalEnvelope is required');

  const receipt = createDeliveryReceipt({
    recipientIdentity,
    originalEnvelope,
    state,
    now
  });
  const sealedPayload = sealPayload(sessionKey, {
    kind: RECEIPT_CONTROL_KIND,
    receipt
  });

  const envelope = createSignedEnvelope({
    senderIdentity: recipientIdentity,
    recipientId: originalEnvelope.senderId,
    sealedPayload,
    conversationId: originalEnvelope.conversationId,
    contentType: RECEIPT_CONTROL_CONTENT_TYPE,
    hopLimit: originalEnvelope.hopLimit
  });

  return { envelope, receipt };
}

export function openEncryptedReceiptEnvelope({
  envelope,
  receiptSignerPublicKey,
  sessionKey
}) {
  if (!envelope?.messageId) throw new Error('envelope is required');
  if (envelope.contentType !== RECEIPT_CONTROL_CONTENT_TYPE) {
    throw new Error('unsupported control envelope content type');
  }
  if (!verifyEnvelope(envelope, receiptSignerPublicKey)) {
    throw new Error('invalid control envelope signature');
  }

  const payload = openPayload(sessionKey, envelope.payload);
  if (payload?.kind !== RECEIPT_CONTROL_KIND || !payload?.receipt) {
    throw new Error('invalid receipt control payload');
  }

  const receipt = payload.receipt;
  if (!verifyDeliveryReceipt(receipt, receiptSignerPublicKey)) {
    throw new Error('invalid final receipt signature');
  }
  if (envelope.senderId !== receipt.recipientId) {
    throw new Error('receipt signer does not match control-envelope sender');
  }
  if (envelope.recipientId !== receipt.originalSenderId) {
    throw new Error('receipt target does not match control-envelope recipient');
  }

  return receipt;
}
