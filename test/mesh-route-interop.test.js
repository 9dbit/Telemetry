import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { deviceIdFromSigningPublicKey } from '../src/protocol/identity.js';
import { verifyRouteAdvertisement } from '../src/protocol/route-advertisement.js';

const vector = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-route-v1-vector.json', import.meta.url)));

function canonical(advertisement) {
  return JSON.stringify({
    protocol: advertisement.protocol,
    advertiserId: advertisement.advertiserId,
    sequence: advertisement.sequence,
    createdAt: advertisement.createdAt,
    expiresAt: advertisement.expiresAt,
    capabilities: advertisement.capabilities,
    routes: advertisement.routes
  });
}

test('shared identity derives the same device id as iOS and Android raw Ed25519 binding', () => {
  assert.equal(
    deviceIdFromSigningPublicKey(vector.identity.signingPublicSpkiBase64Url),
    vector.identity.deviceId
  );
});

test('route advertisement canonical bytes and Ed25519 signature match mobile vector', () => {
  assert.equal(canonical(vector.advertisement), vector.canonicalJson);
  const verification = verifyRouteAdvertisement(
    vector.advertisement,
    vector.identity.signingPublicSpkiBase64Url,
    { now: new Date('2026-09-25T06:30:01.000Z') }
  );
  assert.deepEqual(verification, { ok: true, reason: 'verified' });
});

test('route vector rejects identity or route tampering', () => {
  const wrongIdentity = {
    ...vector.advertisement,
    advertiserId: 'tlm:device:00000000000000000000000000000000'
  };
  assert.equal(
    verifyRouteAdvertisement(
      wrongIdentity,
      vector.identity.signingPublicSpkiBase64Url,
      { now: new Date('2026-09-25T06:30:01.000Z') }
    ).reason,
    'identity-mismatch'
  );

  const tampered = {
    ...vector.advertisement,
    routes: vector.advertisement.routes.map((route, index) =>
      index === 1 ? { ...route, quality: -72 } : route
    )
  };
  assert.equal(
    verifyRouteAdvertisement(
      tampered,
      vector.identity.signingPublicSpkiBase64Url,
      { now: new Date('2026-09-25T06:30:01.000Z') }
    ).reason,
    'bad-signature'
  );
});
