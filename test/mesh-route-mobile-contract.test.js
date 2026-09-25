import fs from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { generateDeviceIdentity } from '../src/protocol/identity.js';
import {
  createRouteAdvertisement,
  DEFAULT_ROUTE_AD_TTL_MS,
  MAX_ADVERTISED_ROUTES,
  MAX_ROUTE_AD_TTL_MS,
  ROUTE_ADVERTISEMENT_PROTOCOL,
  verifyRouteAdvertisement
} from '../src/protocol/route-advertisement.js';

const contract = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/mesh-route-v1.json', import.meta.url), 'utf8'));

test('mobile route contract matches shared route protocol constants', () => {
  assert.equal(contract.wireProtocol, ROUTE_ADVERTISEMENT_PROTOCOL);
  assert.equal(contract.limits.defaultTtlMs, DEFAULT_ROUTE_AD_TTL_MS);
  assert.equal(contract.limits.maxTtlMs, MAX_ROUTE_AD_TTL_MS);
  assert.equal(contract.limits.maxRoutes, MAX_ADVERTISED_ROUTES);
  assert.equal(contract.rules.splitHorizon, true);
  assert.equal(contract.privacy.gpsRequired, false);
  assert.equal(contract.privacy.preciseLocationAllowed, false);
  assert.equal(contract.privacy.payloadAllowed, false);
  assert.equal(contract.privacy.plaintextAllowed, false);
});

test('signed advertisement exposes only the contract-approved route metadata', () => {
  const identity = generateDeviceIdentity();
  const now = new Date('2026-09-25T06:00:00Z');
  const advertisement = createRouteAdvertisement({
    identity,
    sequence: 1,
    capabilities: ['mesh-relay', 'wifi', 'ble', 'wifi'],
    routes: [
      { destinationId: identity.deviceId, hops: 0, quality: 100, transport: 'self' },
      { destinationId: 'tlm:device:peer-c', hops: 2, quality: 55, transport: 'wifi' }
    ],
    now
  });

  assert.equal(verifyRouteAdvertisement(advertisement, identity.signing.publicKey, { now }).ok, true);
  assert.deepEqual(Object.keys(advertisement).filter((key) => key !== 'signature').sort(), [...contract.signedFields].sort());
  assert.deepEqual(Object.keys(advertisement.routes[1]).sort(), [...contract.routeFields].sort());
  assert.deepEqual(advertisement.capabilities, ['ble', 'mesh-relay', 'wifi']);

  const serialized = JSON.stringify(advertisement);
  for (const forbidden of contract.mobileBoundary.nativeMustNotPublish) {
    assert.equal(serialized.toLowerCase().includes(forbidden.toLowerCase()), false);
  }
});

test('route metadata tampering breaks the identity-bound signature', () => {
  const identity = generateDeviceIdentity();
  const now = new Date('2026-09-25T06:00:00Z');
  const advertisement = createRouteAdvertisement({
    identity,
    sequence: 9,
    routes: [{ destinationId: identity.deviceId, hops: 0, quality: 100, transport: 'self' }],
    now
  });
  const tampered = {
    ...advertisement,
    routes: [{ ...advertisement.routes[0], quality: -100 }]
  };
  assert.equal(verifyRouteAdvertisement(tampered, identity.signing.publicKey, { now }).reason, 'bad-signature');
});
