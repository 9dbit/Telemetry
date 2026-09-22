import {
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  sign,
  verify
} from 'node:crypto';

function toB64Url(buffer) {
  return Buffer.from(buffer).toString('base64url');
}

function fromB64Url(value) {
  return Buffer.from(value, 'base64url');
}

function exportDer(key, type) {
  return key.export({ format: 'der', type });
}

export function fingerprintPublicKey(publicKeyDer) {
  return createHash('sha256').update(publicKeyDer).digest('hex');
}

export function deviceIdFromSigningPublicKey(signingPublicKey) {
  const fingerprint = fingerprintPublicKey(fromB64Url(signingPublicKey));
  return `tlm:device:${fingerprint.slice(0, 32)}`;
}

export function generateDeviceIdentity() {
  const signing = generateKeyPairSync('ed25519');
  const exchange = generateKeyPairSync('x25519');

  const signingPublicDer = exportDer(signing.publicKey, 'spki');
  const exchangePublicDer = exportDer(exchange.publicKey, 'spki');
  const signingPublicKey = toB64Url(signingPublicDer);

  return {
    version: 1,
    deviceId: deviceIdFromSigningPublicKey(signingPublicKey),
    signing: {
      publicKey: signingPublicKey,
      privateKey: toB64Url(exportDer(signing.privateKey, 'pkcs8'))
    },
    exchange: {
      publicKey: toB64Url(exchangePublicDer),
      privateKey: toB64Url(exportDer(exchange.privateKey, 'pkcs8'))
    }
  };
}

export function publicIdentity(identity) {
  return {
    version: identity.version,
    deviceId: identity.deviceId,
    signingPublicKey: identity.signing.publicKey,
    exchangePublicKey: identity.exchange.publicKey
  };
}

export function signBytes(identity, bytes) {
  const privateKey = createPrivateKey({
    key: fromB64Url(identity.signing.privateKey),
    format: 'der',
    type: 'pkcs8'
  });
  return toB64Url(sign(null, Buffer.from(bytes), privateKey));
}

export function verifyBytes(signingPublicKey, bytes, signature) {
  const publicKey = createPublicKey({
    key: fromB64Url(signingPublicKey),
    format: 'der',
    type: 'spki'
  });
  return verify(null, Buffer.from(bytes), publicKey, fromB64Url(signature));
}

export function deriveSessionKey(identity, peerExchangePublicKey, context = 'telemetry/v0.1/session') {
  const privateKey = createPrivateKey({
    key: fromB64Url(identity.exchange.privateKey),
    format: 'der',
    type: 'pkcs8'
  });
  const publicKey = createPublicKey({
    key: fromB64Url(peerExchangePublicKey),
    format: 'der',
    type: 'spki'
  });
  const sharedSecret = diffieHellman({ privateKey, publicKey });
  return Buffer.from(hkdfSync('sha256', sharedSecret, Buffer.alloc(0), Buffer.from(context), 32));
}
