import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, verifyEnvelope } from '../src/protocol/envelope.js';
import { createRelayFrame, forwardRelayFrame } from '../src/protocol/mesh.js';

const contractPath = new URL('../mobile/contracts/mesh-relay-v1.json', import.meta.url);
const contract = JSON.parse(await readFile(contractPath, 'utf8'));

test('M1.4E mobile relay contract stays aligned with shared mesh core', () => {
  const sender = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const recipient = generateDeviceIdentity();
  const key = deriveSessionKey(sender, recipient.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: sender,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(key, { text: 'native bridge contract' }),
    hopLimit: 8
  });
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T00:00:00Z') });

  assert.equal(contract.version, 'telemetry-mesh-relay/1');
  for (const field of contract.relayFrame.requiredFields) assert.ok(Object.hasOwn(frame, field), `missing relay frame field: ${field}`);
  assert.equal(contract.routeAdvertisement.protocol, 'telemetry/mesh-route/0.1');
  assert.equal(contract.routeAdvertisement.maxRoutes, 32);
  assert.equal(contract.relayFrame.maxHopLimit, 32);

  const forwarded = forwardRelayFrame(frame, { relayDeviceId: relay.deviceId, now: new Date('2026-09-25T00:00:01Z') });
  assert.equal(forwarded.messageId, frame.messageId);
  assert.equal(forwarded.envelope.messageId, envelope.messageId);
  assert.equal(forwarded.envelope.signature, envelope.signature);
  assert.equal(forwarded.envelope.hopCount, envelope.hopCount + 1);
  assert.deepEqual(forwarded.relayPath, [sender.deviceId, relay.deviceId]);
  assert.equal(verifyEnvelope(forwarded.envelope, sender.signing.publicKey), true);
  assert.equal(contract.principles.relayPayloadVisibility, 'opaque');
});
