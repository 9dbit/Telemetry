import { generateDeviceIdentity, publicIdentity, deriveSessionKey } from './identity.js';
import { sealPayload, openPayload } from './crypto.js';
import { createSignedEnvelope, verifyEnvelope, envelopeRelayView } from './envelope.js';
import { selectTransport } from './router.js';
import { MessageQueue } from './queue.js';

export function simulateMessage({ text = 'hello telemetry', transports } = {}) {
  const sender = generateDeviceIdentity();
  const recipient = generateDeviceIdentity();

  const senderKey = deriveSessionKey(sender, recipient.exchange.publicKey);
  const recipientKey = deriveSessionKey(recipient, sender.exchange.publicKey);
  const sealedPayload = sealPayload(senderKey, {
    type: 'text',
    text,
    sentAt: new Date().toISOString()
  });

  const envelope = createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload
  });

  const signatureValid = verifyEnvelope(envelope, sender.signing.publicKey);
  const route = selectTransport({
    payloadBytes: Buffer.byteLength(sealedPayload.ciphertext, 'base64url'),
    transports: transports || [
      { id: 'wifi-direct', available: true, quality: 12 },
      { id: 'ble', available: true, quality: 4 },
      { id: 'internet', available: true, metered: false, quality: 2 }
    ]
  });

  const queue = new MessageQueue();
  queue.enqueue(envelope);
  const inflight = queue.next();
  if (route.selected) queue.markDelivered(inflight.messageId);

  const decryptedAtDestination = openPayload(recipientKey, envelope.payload);

  return {
    protocol: envelope.protocol,
    sender: publicIdentity(sender),
    recipient: publicIdentity(recipient),
    relayView: envelopeRelayView(envelope),
    envelope,
    signatureValid,
    route,
    queue: queue.stats(),
    decryptedAtDestination
  };
}
