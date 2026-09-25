import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveSessionKey, generateDeviceIdentity } from '../src/protocol/identity.js';
import { openPayload, sealPayload } from '../src/protocol/crypto.js';
import { createSignedEnvelope, verifyEnvelope } from '../src/protocol/envelope.js';
import {
  createRelayFrame,
  forwardRelayFrame,
  ingestRelayFrame,
  RelayDedupe,
  RelayStore
} from '../src/protocol/mesh.js';

const contract = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-relay-v1.json', import.meta.url), 'utf8'));

test('M1.4E mobile relay contract stays aligned with shared mesh core', () => {
  assert.equal(contract.version, 'telemetry-mesh-relay/1');
  assert.equal(contract.principles.relayMayDecryptPayload, false);
  assert.equal(contract.principles.stableMessageIdAcrossHops, true);
  assert.equal(contract.relayFrame.maxHopLimit, 32);
  assert.equal(contract.routeAdvertisement.maxRoutes, 32);

  const origin = generateDeviceIdentity();
  const relay = generateDeviceIdentity();
  const recipient = generateDeviceIdentity();
  const originKey = deriveSessionKey(origin, recipient.exchange.publicKey);
  const envelope = createSignedEnvelope({
    senderIdentity: origin,
    recipientId: recipient.deviceId,
    sealedPayload: sealPayload(originKey, { text: 'opaque relay contract' }),
    hopLimit: 4
  });
  const frame = createRelayFrame(envelope, { now: new Date('2026-09-25T00:00:00Z') });

  const relayIngress = ingestRelayFrame(frame, {
    localDeviceId: relay.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T00:00:01Z')
  });
  assert.equal(relayIngress.action, 'relay');

  const relayWrongKey = deriveSessionKey(relay, recipient.exchange.publicKey);
  assert.throws(() => openPayload(relayWrongKey, envelope.payload));

  const forwarded = forwardRelayFrame(frame, {
    relayDeviceId: relay.deviceId,
    now: new Date('2026-09-25T00:00:02Z')
  });
  assert.equal(forwarded.messageId, frame.messageId);
  assert.equal(forwarded.envelope.messageId, frame.envelope.messageId);
  assert.equal(forwarded.envelope.signature, frame.envelope.signature);
  assert.deepEqual(forwarded.envelope.payload, frame.envelope.payload);
  assert.equal(forwarded.envelope.hopCount, frame.envelope.hopCount + 1);
  assert.deepEqual(forwarded.relayPath, [origin.deviceId, relay.deviceId]);
  assert.equal(verifyEnvelope(forwarded.envelope, origin.signing.publicKey), true);

  const store = new RelayStore();
  store.put(frame, { now: new Date('2026-09-25T00:00:01Z') });
  store.put(frame, { now: new Date('2026-09-25T00:00:02Z') });
  assert.equal(store.stats({ now: new Date('2026-09-25T00:00:03Z') }).total, 1);

  const recipientIngress = ingestRelayFrame(forwarded, {
    localDeviceId: recipient.deviceId,
    dedupe: new RelayDedupe(),
    now: new Date('2026-09-25T00:00:03Z')
  });
  assert.equal(recipientIngress.action, 'deliver-local');
  const recipientKey = deriveSessionKey(recipient, origin.exchange.publicKey);
  assert.deepEqual(openPayload(recipientKey, forwarded.envelope.payload), { text: 'opaque relay contract' });
});
