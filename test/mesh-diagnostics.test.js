import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope } from '../src/protocol/envelope.js';
import { createRelayFrame, forwardRelayFrame } from '../src/protocol/mesh.js';
import { MeshDiagnostics, relayDiagnosticView } from '../src/protocol/mesh-diagnostics.js';

function fixture() {
  const alice = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const carol = generateDeviceIdentity();
  const key = deriveSessionKey(alice, carol.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: alice,
    recipientId: carol.deviceId,
    sealedPayload: sealPayload(key, { text: 'mesh diagnostic secret' }),
    hopLimit: 4
  });
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T05:30:00Z') });
  return { alice, relay, carol, envelope, frame };
}

test('relay diagnostics expose route evidence without ciphertext or plaintext', () => {
  const { relay, envelope, frame } = fixture();
  const diagnostics = new MeshDiagnostics();
  const route = {
    destinationId: envelope.recipientId,
    viaPeerId: relay.deviceId,
    hops: 2,
    quality: 42,
    transport: 'wifi'
  };

  diagnostics.record('route-selected', { frame, route, transportId: 'wifi' });
  const forwarded = forwardRelayFrame(frame, {
    relayDeviceId: relay.deviceId,
    now: new Date('2026-09-25T05:30:01Z')
  });
  diagnostics.record('relay-forwarded', { frame: forwarded, route, transportId: 'wifi' });

  const snapshot = diagnostics.snapshot();
  assert.equal(snapshot.length, 2);
  assert.equal(snapshot[1].relay.hopCount, 1);
  assert.deepEqual(snapshot[1].relay.relayPath, [envelope.senderId, relay.deviceId]);
  assert.equal(snapshot[1].route.viaPeerId, relay.deviceId);
  assert.equal(snapshot[1].transportId, 'wifi');

  const serialized = JSON.stringify(snapshot);
  assert.equal(serialized.includes('mesh diagnostic secret'), false);
  assert.equal(serialized.includes(envelope.payload.ciphertext), false);
  assert.equal(serialized.includes('"ciphertext"'), false);
  assert.equal(serialized.includes('"payload":'), false);
});

test('diagnostic view keeps only bounded relay metadata', () => {
  const { envelope, frame } = fixture();
  const view = relayDiagnosticView(frame);

  assert.equal(view.messageId, envelope.messageId);
  assert.equal(view.senderId, envelope.senderId);
  assert.equal(view.recipientId, envelope.recipientId);
  assert.equal(view.hopCount, 0);
  assert.equal(view.hopLimit, 4);
  assert.equal(view.payloadBytes > 0, true);
  assert.deepEqual(Object.keys(view).sort(), [
    'contentType', 'createdAt', 'expiresAt', 'hopCount', 'hopLimit',
    'messageId', 'payloadBytes', 'recipientId', 'relayPath', 'senderId'
  ]);
});

test('mesh diagnostics stay bounded and reject unknown event types', () => {
  const { frame } = fixture();
  const diagnostics = new MeshDiagnostics({ maxEvents: 2 });

  diagnostics.record('relay-stored', { frame, at: new Date('2026-09-25T05:30:00Z') });
  diagnostics.record('retry-scheduled', { frame, reason: 'next hop unavailable', at: new Date('2026-09-25T05:30:01Z') });
  diagnostics.record('relay-forwarded', { frame, at: new Date('2026-09-25T05:30:02Z') });

  assert.deepEqual(diagnostics.snapshot().map((event) => event.type), ['retry-scheduled', 'relay-forwarded']);
  assert.throws(() => diagnostics.record('plaintext-dump', { frame }), /unsupported mesh diagnostic event/);
});
