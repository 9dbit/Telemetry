import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const contract = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-relay-wire-v1.json', import.meta.url)));
const vector = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-relay-wire-v1-vector.json', import.meta.url)));

function u16(value) {
  const b = Buffer.alloc(2);
  b.writeUInt16BE(value);
  return b;
}

function i64(value) {
  const b = Buffer.alloc(8);
  b.writeBigInt64BE(BigInt(value));
  return b;
}

function u32(value) {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(value);
  return b;
}

function text(value) {
  const bytes = Buffer.from(value, 'utf8');
  return Buffer.concat([u16(bytes.length), bytes]);
}

function encode(frame) {
  const envelope = Buffer.from(frame.encodedEnvelopeHex, 'hex');
  return Buffer.concat([
    Buffer.from(contract.magicAscii, 'ascii'),
    text(frame.messageId),
    text(frame.senderId),
    text(frame.recipientId),
    Buffer.from([frame.hopCount, frame.hopLimit, frame.relayPath.length]),
    ...frame.relayPath.map(text),
    i64(frame.createdAtEpochMs),
    i64(frame.expiresAtEpochMs),
    u32(envelope.length),
    envelope
  ]);
}

test('mobile relay wire vector is deterministic across platform implementations', () => {
  assert.equal(contract.version, vector.version);
  assert.equal(contract.magicAscii, 'TMR1');
  assert.equal(contract.byteOrder, 'big-endian');
  assert.equal(encode(vector.frame).toString('hex'), vector.expectedWireHex);
});

test('relay wire contract keeps envelope opaque and immutable across hops', () => {
  assert.equal(contract.rules.encodedEnvelopeOpaque, true);
  assert.equal(contract.rules.encodedEnvelopeImmutableAcrossRelay, true);
  assert.equal(contract.rules.messageIdStableAcrossRelay, true);
  assert.deepEqual(contract.rules.relayMayMutate, ['hopCount', 'relayPath']);
  assert.ok(contract.rules.relayMustNotMutate.includes('encodedEnvelope'));
});
