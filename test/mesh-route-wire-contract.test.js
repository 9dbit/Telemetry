import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const contract = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-route-wire-v1.json', import.meta.url)));
const routeVector = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-route-v1-vector.json', import.meta.url)));
const wireVector = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-route-wire-v1-vector.json', import.meta.url)));

function stringField(value) {
  const bytes = Buffer.from(value, 'utf8');
  const prefix = Buffer.alloc(2);
  prefix.writeUInt16BE(bytes.length);
  return Buffer.concat([prefix, bytes]);
}

function i64(value) {
  const bytes = Buffer.alloc(8);
  bytes.writeBigInt64BE(BigInt(value));
  return bytes;
}

function encode(advertisement) {
  const createdAt = Date.parse(advertisement.createdAt);
  const expiresAt = Date.parse(advertisement.expiresAt);
  const signature = Buffer.from(advertisement.signature, 'base64url');
  const parts = [
    Buffer.from(contract.magicAscii, 'ascii'),
    stringField(advertisement.advertiserId),
    i64(advertisement.sequence),
    i64(createdAt),
    i64(expiresAt),
    Buffer.from([advertisement.capabilities.length]),
    ...advertisement.capabilities.map(stringField),
    Buffer.from([advertisement.routes.length])
  ];

  for (const route of advertisement.routes) {
    const quality = Buffer.alloc(2);
    quality.writeInt16BE(route.quality);
    parts.push(
      stringField(route.destinationId),
      Buffer.from([route.hops]),
      quality,
      stringField(route.transport)
    );
  }
  parts.push(signature);
  return Buffer.concat(parts);
}

test('TMA1 route wire vector is deterministic across platform implementations', () => {
  assert.equal(contract.version, wireVector.version);
  assert.equal(contract.magicAscii, 'TMA1');
  assert.equal(contract.byteOrder, 'big-endian');
  assert.equal(Buffer.from(routeVector.advertisement.signature, 'base64url').length, 64);
  assert.equal(encode(routeVector.advertisement).toString('hex'), wireVector.expectedWireHex);
});

test('TMA1 contract requires verification before route ingest and forbids location metadata', () => {
  assert.equal(contract.rules.verifySignatureBeforeRouteIngest, true);
  assert.equal(contract.rules.advertiserIdBoundToRawEd25519PublicKey, true);
  assert.equal(contract.rules.rejectTrailingBytes, true);
  assert.equal(contract.rules.gpsCoordinatesAllowed, false);
});
