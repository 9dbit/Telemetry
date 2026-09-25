import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  randomUUID
} from 'node:crypto';
import { sealPayload, openPayload } from './crypto.js';
import { createSignedEnvelope, verifyEnvelope } from './envelope.js';

export const MEDIA_MANIFEST_VERSION = 'telemetry/media-manifest/0.1';
export const MEDIA_CHUNK_VERSION = 'telemetry/media-chunk/0.1';
export const MEDIA_MANIFEST_CONTENT_TYPE = 'application/telemetry+media-manifest';
export const DEFAULT_MEDIA_CHUNK_BYTES = 256 * 1024;
export const MAX_MEDIA_CHUNK_BYTES = 1024 * 1024;
export const MAX_MEDIA_ASSET_BYTES = 512 * 1024 * 1024;
export const MAX_MEDIA_CHUNKS = 8192;

const MEDIA_KINDS = new Set(['photo', 'video', 'file']);
const MAX_FILENAME_CHARS = 255;
const MAX_MIME_CHARS = 127;

function b64url(bytes) {
  return Buffer.from(bytes).toString('base64url');
}

function fromB64url(value) {
  return Buffer.from(value, 'base64url');
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function validateAssetId(assetId) {
  if (typeof assetId !== 'string' || assetId.length < 8 || assetId.length > 128) {
    throw new Error('invalid media assetId');
  }
}

function validateChunkSize(chunkBytes) {
  if (!Number.isInteger(chunkBytes) || chunkBytes < 16 * 1024 || chunkBytes > MAX_MEDIA_CHUNK_BYTES) {
    throw new Error(`chunkBytes must be between 16384 and ${MAX_MEDIA_CHUNK_BYTES}`);
  }
}

export function validateMediaManifest(manifest) {
  if (!manifest || manifest.version !== MEDIA_MANIFEST_VERSION) throw new Error('unsupported media manifest version');
  validateAssetId(manifest.assetId);
  if (!MEDIA_KINDS.has(manifest.kind)) throw new Error('unsupported media kind');
  if (typeof manifest.mimeType !== 'string' || manifest.mimeType.length < 1 || manifest.mimeType.length > MAX_MIME_CHARS) {
    throw new Error('invalid media mimeType');
  }
  if (typeof manifest.fileName !== 'string' || manifest.fileName.length < 1 || manifest.fileName.length > MAX_FILENAME_CHARS) {
    throw new Error('invalid media fileName');
  }
  if (!Number.isInteger(manifest.byteLength) || manifest.byteLength < 1 || manifest.byteLength > MAX_MEDIA_ASSET_BYTES) {
    throw new Error('invalid media byteLength');
  }
  validateChunkSize(manifest.chunkBytes);
  if (!Number.isInteger(manifest.chunkCount) || manifest.chunkCount < 1 || manifest.chunkCount > MAX_MEDIA_CHUNKS) {
    throw new Error('invalid media chunkCount');
  }
  const expectedCount = Math.ceil(manifest.byteLength / manifest.chunkBytes);
  if (manifest.chunkCount !== expectedCount) throw new Error('media chunkCount does not match byteLength');
  if (!/^[0-9a-f]{64}$/.test(manifest.sha256)) throw new Error('invalid media sha256');
  const contentKey = fromB64url(manifest.contentKey);
  if (contentKey.length !== 32) throw new Error('invalid media content key');
  if (!Number.isFinite(new Date(manifest.createdAt).getTime())) throw new Error('invalid media createdAt');
  return manifest;
}

function chunkAad({ assetId, index, count, plainBytes }) {
  return Buffer.from(JSON.stringify({
    version: MEDIA_CHUNK_VERSION,
    assetId,
    index,
    count,
    plainBytes
  }));
}

export function createMediaPackage({
  bytes,
  kind,
  mimeType,
  fileName,
  chunkBytes = DEFAULT_MEDIA_CHUNK_BYTES,
  assetId = randomUUID(),
  contentKey = randomBytes(32),
  now = new Date()
}) {
  const payload = Buffer.from(bytes || []);
  if (payload.length < 1 || payload.length > MAX_MEDIA_ASSET_BYTES) throw new Error('media asset size invalid');
  if (!MEDIA_KINDS.has(kind)) throw new Error('unsupported media kind');
  if (typeof mimeType !== 'string' || mimeType.length < 1 || mimeType.length > MAX_MIME_CHARS) throw new Error('invalid media mimeType');
  if (typeof fileName !== 'string' || fileName.length < 1 || fileName.length > MAX_FILENAME_CHARS) throw new Error('invalid media fileName');
  validateAssetId(assetId);
  validateChunkSize(chunkBytes);
  const key = Buffer.from(contentKey);
  if (key.length !== 32) throw new Error('contentKey must be 32 bytes');

  const count = Math.ceil(payload.length / chunkBytes);
  if (count > MAX_MEDIA_CHUNKS) throw new Error('media asset requires too many chunks');

  const manifest = validateMediaManifest({
    version: MEDIA_MANIFEST_VERSION,
    assetId,
    kind,
    mimeType,
    fileName,
    byteLength: payload.length,
    chunkBytes,
    chunkCount: count,
    sha256: sha256(payload),
    contentKey: b64url(key),
    createdAt: new Date(now).toISOString()
  });

  const chunks = [];
  for (let index = 0; index < count; index += 1) {
    const start = index * chunkBytes;
    const plain = payload.subarray(start, Math.min(start + chunkBytes, payload.length));
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(chunkAad({ assetId, index, count, plainBytes: plain.length }));
    const ciphertext = Buffer.concat([cipher.update(plain), cipher.final()]);
    const tag = cipher.getAuthTag();
    chunks.push({
      version: MEDIA_CHUNK_VERSION,
      assetId,
      index,
      count,
      plainBytes: plain.length,
      nonce: b64url(nonce),
      ciphertext: b64url(ciphertext),
      tag: b64url(tag)
    });
  }

  return { manifest, chunks };
}

export function validateMediaChunk(chunk) {
  if (!chunk || chunk.version !== MEDIA_CHUNK_VERSION) throw new Error('unsupported media chunk version');
  validateAssetId(chunk.assetId);
  if (!Number.isInteger(chunk.index) || chunk.index < 0) throw new Error('invalid media chunk index');
  if (!Number.isInteger(chunk.count) || chunk.count < 1 || chunk.count > MAX_MEDIA_CHUNKS || chunk.index >= chunk.count) {
    throw new Error('invalid media chunk count');
  }
  if (!Number.isInteger(chunk.plainBytes) || chunk.plainBytes < 1 || chunk.plainBytes > MAX_MEDIA_CHUNK_BYTES) {
    throw new Error('invalid media chunk plainBytes');
  }
  if (fromB64url(chunk.nonce).length !== 12) throw new Error('invalid media chunk nonce');
  if (fromB64url(chunk.tag).length !== 16) throw new Error('invalid media chunk tag');
  const ciphertext = fromB64url(chunk.ciphertext);
  if (ciphertext.length !== chunk.plainBytes) throw new Error('media chunk ciphertext length mismatch');
  return chunk;
}

export function decryptMediaChunk(manifest, chunk) {
  validateMediaManifest(manifest);
  validateMediaChunk(chunk);
  if (chunk.assetId !== manifest.assetId) throw new Error('media chunk asset mismatch');
  if (chunk.count !== manifest.chunkCount) throw new Error('media chunk count mismatch');
  const expectedPlainBytes = chunk.index === manifest.chunkCount - 1
    ? manifest.byteLength - chunk.index * manifest.chunkBytes
    : manifest.chunkBytes;
  if (chunk.plainBytes !== expectedPlainBytes) throw new Error('media chunk size does not match manifest');

  const key = fromB64url(manifest.contentKey);
  const decipher = createDecipheriv('aes-256-gcm', key, fromB64url(chunk.nonce));
  decipher.setAAD(chunkAad({
    assetId: chunk.assetId,
    index: chunk.index,
    count: chunk.count,
    plainBytes: chunk.plainBytes
  }));
  decipher.setAuthTag(fromB64url(chunk.tag));
  return Buffer.concat([
    decipher.update(fromB64url(chunk.ciphertext)),
    decipher.final()
  ]);
}

export function createMediaManifestEnvelope({
  senderIdentity,
  recipientId,
  sessionKey,
  manifest,
  conversationId,
  hopLimit = 8
}) {
  validateMediaManifest(manifest);
  const sealedPayload = sealPayload(sessionKey, {
    kind: 'media-manifest',
    manifest
  });
  return createSignedEnvelope({
    senderIdentity,
    recipientId,
    sealedPayload,
    conversationId,
    contentType: MEDIA_MANIFEST_CONTENT_TYPE,
    hopLimit
  });
}

export function openMediaManifestEnvelope({
  envelope,
  senderSigningPublicKey,
  sessionKey
}) {
  if (envelope?.contentType !== MEDIA_MANIFEST_CONTENT_TYPE) throw new Error('unsupported media manifest content type');
  if (!verifyEnvelope(envelope, senderSigningPublicKey)) throw new Error('invalid media manifest envelope signature');
  const payload = openPayload(sessionKey, envelope.payload);
  if (payload?.kind !== 'media-manifest' || !payload?.manifest) throw new Error('invalid media manifest payload');
  return validateMediaManifest(payload.manifest);
}

export class MediaChunkAssembler {
  constructor(manifest) {
    this.manifest = validateMediaManifest({ ...manifest });
    this.chunks = new Map();
  }

  ingest(chunk) {
    validateMediaChunk(chunk);
    if (chunk.assetId !== this.manifest.assetId || chunk.count !== this.manifest.chunkCount) {
      return { accepted: false, reason: 'manifest-mismatch' };
    }
    if (this.chunks.has(chunk.index)) return { accepted: false, reason: 'duplicate' };
    this.chunks.set(chunk.index, { ...chunk });
    return { accepted: true, reason: 'stored', index: chunk.index };
  }

  progress() {
    return {
      received: this.chunks.size,
      total: this.manifest.chunkCount,
      complete: this.chunks.size === this.manifest.chunkCount
    };
  }

  missingIndices() {
    const missing = [];
    for (let index = 0; index < this.manifest.chunkCount; index += 1) {
      if (!this.chunks.has(index)) missing.push(index);
    }
    return missing;
  }

  assemble() {
    if (this.chunks.size !== this.manifest.chunkCount) throw new Error('media chunks incomplete');
    const parts = [];
    for (let index = 0; index < this.manifest.chunkCount; index += 1) {
      const chunk = this.chunks.get(index);
      if (!chunk) throw new Error(`missing media chunk ${index}`);
      parts.push(decryptMediaChunk(this.manifest, chunk));
    }
    const bytes = Buffer.concat(parts);
    if (bytes.length !== this.manifest.byteLength) throw new Error('assembled media size mismatch');
    if (sha256(bytes) !== this.manifest.sha256) throw new Error('assembled media hash mismatch');
    return bytes;
  }
}

export class OpaqueMediaRelayStore {
  constructor() {
    this.items = new Map();
  }

  put(chunk, { expiresAt }) {
    validateMediaChunk(chunk);
    const expiryMs = new Date(expiresAt).getTime();
    if (!Number.isFinite(expiryMs)) throw new Error('invalid media relay expiry');
    const key = `${chunk.assetId}:${chunk.index}`;
    if (this.items.has(key)) return { accepted: false, reason: 'duplicate' };
    this.items.set(key, {
      key,
      chunk: { ...chunk },
      expiresAt: new Date(expiryMs).toISOString()
    });
    return { accepted: true, reason: 'stored', key };
  }

  ready({ now = new Date() } = {}) {
    this.prune({ now });
    return [...this.items.values()].map((record) => ({ ...record, chunk: { ...record.chunk } }));
  }

  remove(assetId, index) {
    return this.items.delete(`${assetId}:${index}`);
  }

  prune({ now = new Date() } = {}) {
    const nowMs = new Date(now).getTime();
    if (!Number.isFinite(nowMs)) throw new Error('invalid media relay time');
    for (const [key, record] of this.items) {
      if (new Date(record.expiresAt).getTime() <= nowMs) this.items.delete(key);
    }
  }

  stats({ now = new Date() } = {}) {
    this.prune({ now });
    return { storedChunks: this.items.size };
  }
}
