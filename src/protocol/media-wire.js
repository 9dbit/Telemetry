import { validateMediaChunk } from './media.js';

const MAGIC = Buffer.from('TMC1', 'ascii');
const MAX_ASSET_ID_BYTES = 128;
const MAX_CHUNK_BYTES = 1024 * 1024;

function writeString(bufferParts, value) {
  const bytes = Buffer.from(value, 'utf8');
  if (bytes.length < 1 || bytes.length > MAX_ASSET_ID_BYTES) throw new Error('invalid media wire assetId length');
  const length = Buffer.allocUnsafe(2);
  length.writeUInt16BE(bytes.length);
  bufferParts.push(length, bytes);
}

function readString(buffer, offset) {
  if (offset + 2 > buffer.length) throw new Error('truncated media wire string length');
  const length = buffer.readUInt16BE(offset);
  offset += 2;
  if (length < 1 || length > MAX_ASSET_ID_BYTES || offset + length > buffer.length) {
    throw new Error('invalid media wire string length');
  }
  return {
    value: buffer.subarray(offset, offset + length).toString('utf8'),
    offset: offset + length
  };
}

export function encodeMediaChunkWire(chunk) {
  validateMediaChunk(chunk);
  const nonce = Buffer.from(chunk.nonce, 'base64url');
  const tag = Buffer.from(chunk.tag, 'base64url');
  const ciphertext = Buffer.from(chunk.ciphertext, 'base64url');
  if (ciphertext.length > MAX_CHUNK_BYTES) throw new Error('media wire ciphertext too large');

  const parts = [MAGIC];
  writeString(parts, chunk.assetId);
  const fixed = Buffer.allocUnsafe(4 + 4 + 4 + 12 + 16 + 4);
  let offset = 0;
  fixed.writeUInt32BE(chunk.index, offset); offset += 4;
  fixed.writeUInt32BE(chunk.count, offset); offset += 4;
  fixed.writeUInt32BE(chunk.plainBytes, offset); offset += 4;
  nonce.copy(fixed, offset); offset += 12;
  tag.copy(fixed, offset); offset += 16;
  fixed.writeUInt32BE(ciphertext.length, offset);
  parts.push(fixed, ciphertext);
  return Buffer.concat(parts);
}

export function decodeMediaChunkWire(bytes) {
  const buffer = Buffer.from(bytes);
  if (buffer.length < 4 + 2 + 4 + 4 + 4 + 12 + 16 + 4 + 1) throw new Error('media wire too short');
  if (!buffer.subarray(0, 4).equals(MAGIC)) throw new Error('unsupported media wire magic');
  let offset = 4;
  const decodedAsset = readString(buffer, offset);
  const assetId = decodedAsset.value;
  offset = decodedAsset.offset;
  if (offset + 44 > buffer.length) throw new Error('truncated media wire header');

  const index = buffer.readUInt32BE(offset); offset += 4;
  const count = buffer.readUInt32BE(offset); offset += 4;
  const plainBytes = buffer.readUInt32BE(offset); offset += 4;
  const nonce = buffer.subarray(offset, offset + 12); offset += 12;
  const tag = buffer.subarray(offset, offset + 16); offset += 16;
  const ciphertextLength = buffer.readUInt32BE(offset); offset += 4;
  if (ciphertextLength < 1 || ciphertextLength > MAX_CHUNK_BYTES) throw new Error('invalid media wire ciphertext length');
  if (offset + ciphertextLength !== buffer.length) {
    if (offset + ciphertextLength > buffer.length) throw new Error('truncated media wire ciphertext');
    throw new Error('unexpected trailing media wire bytes');
  }
  const ciphertext = buffer.subarray(offset, offset + ciphertextLength);

  return validateMediaChunk({
    version: 'telemetry/media-chunk/0.1',
    assetId,
    index,
    count,
    plainBytes,
    nonce: nonce.toString('base64url'),
    ciphertext: ciphertext.toString('base64url'),
    tag: tag.toString('base64url')
  });
}

export function createMediaTransportFrame(chunk) {
  validateMediaChunk(chunk);
  return {
    messageId: `media:${chunk.assetId}:${chunk.index}`,
    kind: 'media-chunk',
    assetId: chunk.assetId,
    index: chunk.index,
    wire: encodeMediaChunkWire(chunk)
  };
}
