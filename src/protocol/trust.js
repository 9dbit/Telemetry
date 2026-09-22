import { createHash, randomBytes } from 'node:crypto';
import {
  deviceIdFromSigningPublicKey,
  publicIdentity,
  signBytes,
  verifyBytes
} from './identity.js';

export const PAIRING_VERSION = 'telemetry/pairing/0.2';

function normalizeCapabilities(capabilities) {
  return [...new Set((capabilities || []).filter((value) => typeof value === 'string'))]
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean)
    .sort();
}

function canonicalPairingData(offer) {
  return JSON.stringify({
    version: offer.version,
    issuer: offer.issuer,
    deviceName: offer.deviceName,
    capabilities: offer.capabilities,
    nonce: offer.nonce,
    issuedAt: offer.issuedAt,
    expiresAt: offer.expiresAt
  });
}

export function pairingFingerprint(signingPublicKey) {
  const digest = createHash('sha256')
    .update(Buffer.from(signingPublicKey, 'base64url'))
    .digest('hex');
  return digest.match(/.{1,4}/g).slice(0, 8).join('-');
}

export function createPairingOffer({
  identity,
  deviceName = 'Telemetry Device',
  capabilities = ['ble', 'wifi-aware', 'wifi-direct'],
  ttlSeconds = 300,
  now = new Date()
}) {
  if (!identity?.deviceId) throw new Error('identity is required');
  if (!Number.isInteger(ttlSeconds) || ttlSeconds < 30 || ttlSeconds > 900) {
    throw new Error('ttlSeconds must be between 30 and 900');
  }

  const issuedAt = new Date(now).toISOString();
  const expiresAt = new Date(new Date(now).getTime() + ttlSeconds * 1000).toISOString();
  const offer = {
    version: PAIRING_VERSION,
    issuer: publicIdentity(identity),
    deviceName: String(deviceName).trim().slice(0, 80) || 'Telemetry Device',
    capabilities: normalizeCapabilities(capabilities),
    nonce: randomBytes(16).toString('base64url'),
    issuedAt,
    expiresAt
  };

  return {
    ...offer,
    signature: signBytes(identity, canonicalPairingData(offer))
  };
}

export function encodePairingQr(offer) {
  const data = Buffer.from(JSON.stringify(offer), 'utf8').toString('base64url');
  return `telemetry://pair?v=2&data=${encodeURIComponent(data)}`;
}

export function decodePairingQr(value) {
  const url = new URL(value);
  if (url.protocol !== 'telemetry:' || url.hostname !== 'pair' || url.searchParams.get('v') !== '2') {
    throw new Error('unsupported pairing QR');
  }
  const data = url.searchParams.get('data');
  if (!data) throw new Error('pairing QR has no data');
  return JSON.parse(Buffer.from(data, 'base64url').toString('utf8'));
}

export function verifyPairingOffer(offer, { now = new Date() } = {}) {
  try {
    if (!offer || offer.version !== PAIRING_VERSION) return { ok: false, reason: 'unsupported-version' };
    if (!offer.issuer?.signingPublicKey || !offer.issuer?.exchangePublicKey) {
      return { ok: false, reason: 'missing-public-identity' };
    }
    const expectedDeviceId = deviceIdFromSigningPublicKey(offer.issuer.signingPublicKey);
    if (offer.issuer.deviceId !== expectedDeviceId) return { ok: false, reason: 'device-id-mismatch' };

    const issuedAt = Date.parse(offer.issuedAt);
    const expiresAt = Date.parse(offer.expiresAt);
    const nowMs = new Date(now).getTime();
    if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt) || expiresAt <= issuedAt) {
      return { ok: false, reason: 'invalid-time-window' };
    }
    if (nowMs > expiresAt) return { ok: false, reason: 'expired' };
    if (issuedAt - nowMs > 60_000) return { ok: false, reason: 'issued-in-future' };

    const signatureValid = verifyBytes(
      offer.issuer.signingPublicKey,
      canonicalPairingData(offer),
      offer.signature
    );
    if (!signatureValid) return { ok: false, reason: 'invalid-signature' };

    return {
      ok: true,
      reason: 'verified',
      deviceId: offer.issuer.deviceId,
      fingerprint: pairingFingerprint(offer.issuer.signingPublicKey),
      expiresAt: offer.expiresAt
    };
  } catch {
    return { ok: false, reason: 'malformed-offer' };
  }
}

export class ReplayGuard {
  constructor({ maxEntries = 10_000 } = {}) {
    this.maxEntries = maxEntries;
    this.entries = new Map();
  }

  prune(now = new Date()) {
    const nowMs = new Date(now).getTime();
    for (const [key, expiresAt] of this.entries) {
      if (expiresAt <= nowMs) this.entries.delete(key);
    }
  }

  checkAndRemember(key, { expiresAt, now = new Date() } = {}) {
    if (!key) return { accepted: false, reason: 'missing-key' };
    const nowMs = new Date(now).getTime();
    this.prune(now);
    if (this.entries.has(key)) return { accepted: false, reason: 'duplicate' };

    const expiryMs = expiresAt ? new Date(expiresAt).getTime() : nowMs + 15 * 60_000;
    if (!Number.isFinite(expiryMs) || expiryMs <= nowMs) return { accepted: false, reason: 'expired' };

    this.entries.set(key, expiryMs);
    while (this.entries.size > this.maxEntries) {
      this.entries.delete(this.entries.keys().next().value);
    }
    return { accepted: true, reason: 'new' };
  }
}

export class TrustRegistry {
  constructor() {
    this.peers = new Map();
  }

  trust(offer, { now = new Date() } = {}) {
    const verification = verifyPairingOffer(offer, { now });
    if (!verification.ok) throw new Error(`pairing offer rejected: ${verification.reason}`);

    const peer = {
      deviceId: offer.issuer.deviceId,
      displayName: offer.deviceName,
      fingerprint: verification.fingerprint,
      identity: offer.issuer,
      capabilities: [...offer.capabilities],
      trustedAt: new Date(now).toISOString(),
      revokedAt: null,
      revokeReason: null
    };
    this.peers.set(peer.deviceId, peer);
    return structuredClone(peer);
  }

  revoke(deviceId, reason = 'user-revoked', { now = new Date() } = {}) {
    const peer = this.peers.get(deviceId);
    if (!peer) return false;
    peer.revokedAt = new Date(now).toISOString();
    peer.revokeReason = String(reason).slice(0, 120);
    return true;
  }

  isTrusted(deviceId) {
    const peer = this.peers.get(deviceId);
    return Boolean(peer && !peer.revokedAt);
  }

  get(deviceId) {
    const peer = this.peers.get(deviceId);
    return peer ? structuredClone(peer) : null;
  }

  list() {
    return [...this.peers.values()].map((peer) => structuredClone(peer));
  }
}
