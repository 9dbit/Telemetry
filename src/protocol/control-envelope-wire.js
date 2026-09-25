import { verifyEnvelope } from './envelope.js';

export const CONTROL_ENVELOPE_WIRE_VERSION = 'TCE1';
export const CONTROL_ENVELOPE_MAX_CIPHERTEXT_BYTES = 64 * 1024;

const MAGIC = Buffer.from(CONTROL_ENVELOPE_WIRE_VERSION, 'ascii');
const ALGORITHM = 'AES-256-GCM';
const KEY_AGREEMENT = 'X25519-HKDF-SHA256';
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const SIGNATURE_BYTES = 64;
const MAX_STRING_BYTES = 1024;

function b64url(bytes) {
  return Buffer.from(bytes).toString('base64url');
}

function fromB64url(value) {
  return Buffer.from(value, 'base64url');
}

function writeU16(value) {
  if (!Number.isInteger(value) || value < 0 || value > 0xffff) throw new Error('u16 out of range');
  const out = Buffer.allocUnsafe(2);
  out.writeUInt16BE(value, 0);
  return out;
}

function writeU32(value) {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) throw new Error('u32 out of range');
  const out = Buffer.allocUnsafe(4);
  out.writeUInt32BE(value, 0);
  return out;
}

function writeString(value) {
  if (typeof value !== 'string') throw new Error('control envelope string is required');
  const bytes = Buffer.from(value, 'utf8');
  if (bytes.length < 1 || bytes.length > MAX_STRING_BYTES) throw new Error('control envelope string length invalid');
  return Buffer.concat([writeU16(bytes.length), bytes]);
}

function createReader(bytes) {
  const source = Buffer.from(bytes);
  let offset = 0;
  function take(length) {
    if (!Number.isInteger(length) || length < 0 || offset + length > source.length) {
      throw new Error('truncated control envelope wire');
    }
    const result = source.subarray(offset, offset + length);
    offset += length;
    return result;
  }
  return {
    take,
    u8() {
      return take(1)[0];
    },
    u16() {
      return take(2).readUInt16BE(0);
    },
    u32() {
      return take(4).readUInt32BE(0);
    },
    string() {
      const length = this.u16();
      if (length < 1 || length > MAX_STRING_BYTES) throw new Error('control envelope string length invalid');
      return take(length).toString('utf8');
    },
    remaining() {
      return source.length - offset;
    }
  };
}

function validateEnvelopeForWire(envelope) {
  if (!envelope?.signature) throw new Error('signed control envelope is required');
  if (!Number.isInteger(envelope.hopLimit) || envelope.hopLimit < 1 || envelope.hopLimit > 32) {
    throw new Error('control envelope hopLimit invalid');
  }
  if (!Number.isFinite(new Date(envelope.createdAt).getTime())) throw new Error('control envelope createdAt invalid');
  if (envelope.payload?.algorithm !== ALGORITHM || envelope.payload?.keyAgreement !== KEY_AGREEMENT) {
    throw new Error('unsupported control envelope crypto suite');
  }
  const nonce = fromB64url(envelope.payload.nonce);
  const ciphertext = fromB64url(envelope.payload.ciphertext);
  const tag = fromB64url(envelope.payload.tag);
  const signature = fromB64url(envelope.signature);
  if (nonce.length !== NONCE_BYTES) throw new Error('control envelope nonce must be 12 bytes');
  if (tag.length !== TAG_BYTES) throw new Error('control envelope tag must be 16 bytes');
  if (signature.length !== SIGNATURE_BYTES) throw new Error('control envelope signature must be 64 bytes');
  if (ciphertext.length < 1 || ciphertext.length > CONTROL_ENVELOPE_MAX_CIPHERTEXT_BYTES) {
    throw new Error('control envelope ciphertext length invalid');
  }
  return { nonce, ciphertext, tag, signature };
}

export function encodeControlEnvelopeWire(envelope) {
  const { nonce, ciphertext, tag, signature } = validateEnvelopeForWire(envelope);
  return Buffer.concat([
    MAGIC,
    writeString(envelope.protocol),
    writeString(envelope.messageId),
    writeString(envelope.conversationId),
    writeString(envelope.senderId),
    writeString(envelope.recipientId),
    writeString(envelope.createdAt),
    Buffer.from([envelope.hopLimit]),
    writeString(envelope.contentType),
    nonce,
    writeU32(ciphertext.length),
    ciphertext,
    tag,
    signature
  ]);
}

export function decodeControlEnvelopeWire(bytes) {
  const reader = createReader(bytes);
  if (!reader.take(MAGIC.length).equals(MAGIC)) throw new Error('unsupported control envelope wire magic');
  const protocol = reader.string();
  const messageId = reader.string();
  const conversationId = reader.string();
  const senderId = reader.string();
  const recipientId = reader.string();
  const createdAt = reader.string();
  const hopLimit = reader.u8();
  if (hopLimit < 1 || hopLimit > 32) throw new Error('control envelope hopLimit invalid');
  const contentType = reader.string();
  const nonce = reader.take(NONCE_BYTES);
  const ciphertextLength = reader.u32();
  if (ciphertextLength < 1 || ciphertextLength > CONTROL_ENVELOPE_MAX_CIPHERTEXT_BYTES) {
    throw new Error('control envelope ciphertext length invalid');
  }
  const ciphertext = reader.take(ciphertextLength);
  const tag = reader.take(TAG_BYTES);
  const signature = reader.take(SIGNATURE_BYTES);
  if (reader.remaining() !== 0) throw new Error('unexpected trailing control envelope bytes');

  const envelope = {
    protocol,
    messageId,
    conversationId,
    senderId,
    recipientId,
    createdAt,
    hopCount: 0,
    hopLimit,
    contentType,
    payload: {
      algorithm: ALGORITHM,
      keyAgreement: KEY_AGREEMENT,
      nonce: b64url(nonce),
      ciphertext: b64url(ciphertext),
      tag: b64url(tag)
    },
    signature: b64url(signature)
  };
  validateEnvelopeForWire(envelope);
  return envelope;
}

export function verifyControlEnvelopeWire(bytes, signingPublicKey) {
  const envelope = decodeControlEnvelopeWire(bytes);
  return {
    envelope,
    verified: verifyEnvelope(envelope, signingPublicKey)
  };
}
