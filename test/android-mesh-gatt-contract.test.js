import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const contract = JSON.parse(fs.readFileSync(new URL('../mobile/contracts/android-mesh-gatt-v1.json', import.meta.url)));

test('Android mesh GATT extends the existing service without changing direct M1B channels', () => {
  assert.equal(contract.serviceUuid, 'f0a0c0de-7e1e-4e7f-9a11-54454c454d59');
  assert.equal(contract.characteristics.directMessage.endsWith('4d02'), true);
  assert.equal(contract.characteristics.directReceipt.endsWith('4d03'), true);
  assert.equal(contract.characteristics.meshRelay.endsWith('4d04'), true);
  assert.equal(contract.characteristics.meshRouteAdvertisement.endsWith('4d05'), true);
  assert.equal(contract.invariants.directMessageCharacteristicUnchanged, true);
});

test('BLE mesh frames fail closed on MTU and never silently fragment or truncate', () => {
  assert.equal(contract.meshRelay.nominalMaxWireBytes, 480);
  assert.equal(contract.meshRelay.fragmentation, false);
  assert.equal(contract.mtu.silentTruncationAllowed, false);
  assert.match(contract.meshRelay.oversizePolicy, /TransportCoordinator/);
});

test('BLE mesh control stays trusted, opaque and location-minimal', () => {
  assert.equal(contract.meshRelay.requiresSecureHello, true);
  assert.equal(contract.meshRelay.requiresTrustedPeer, true);
  assert.equal(contract.meshRouteAdvertisement.signatureVerificationBeforeIngest, true);
  assert.equal(contract.privacy.plaintextAllowed, false);
  assert.equal(contract.privacy.gpsAllowed, false);
  assert.equal(contract.privacy.stableRadioAddressAsIdentity, false);
  assert.equal(contract.privacy.routingIdentity, 'Telemetry deviceId');
  assert.equal(contract.invariants.relayMayDecryptEncodedEnvelope, false);
});
