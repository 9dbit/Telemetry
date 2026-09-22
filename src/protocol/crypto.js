import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

export function sealPayload(sessionKey, payload) {
  if (!Buffer.isBuffer(sessionKey) || sessionKey.length !== 32) {
    throw new Error('sessionKey must be a 32-byte Buffer');
  }

  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', sessionKey, nonce);
  const plaintext = Buffer.from(JSON.stringify(payload));
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();

  return {
    algorithm: 'AES-256-GCM',
    keyAgreement: 'X25519-HKDF-SHA256',
    nonce: nonce.toString('base64url'),
    ciphertext: ciphertext.toString('base64url'),
    tag: tag.toString('base64url')
  };
}

export function openPayload(sessionKey, sealed) {
  if (!Buffer.isBuffer(sessionKey) || sessionKey.length !== 32) {
    throw new Error('sessionKey must be a 32-byte Buffer');
  }

  const decipher = createDecipheriv(
    'aes-256-gcm',
    sessionKey,
    Buffer.from(sealed.nonce, 'base64url')
  );
  decipher.setAuthTag(Buffer.from(sealed.tag, 'base64url'));

  const plaintext = Buffer.concat([
    decipher.update(Buffer.from(sealed.ciphertext, 'base64url')),
    decipher.final()
  ]);

  return JSON.parse(plaintext.toString('utf8'));
}
