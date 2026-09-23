import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const contractUrl = new URL('../mobile/contracts/mobile-contract-v1.json', import.meta.url);

test('mobile contract keeps M1B offline, private, and cryptographically gated across Android and iOS preview', async () => {
  const contract = JSON.parse(await readFile(contractUrl, 'utf8'));
  assert.equal(contract.version, 'telemetry-mobile/1');
  assert.equal(contract.milestone, 'M1B-secure-offline-session-cross-platform-preview');
  assert.equal(contract.principles.offlineFirst, true);
  assert.equal(contract.principles.cloudRequiredForDiscovery, false);
  assert.equal(contract.principles.cloudRequiredForSession, false);
  assert.equal(contract.principles.stableIdentityInAdvertisement, false);
  assert.equal(contract.principles.radioAddressPersisted, false);
  assert.equal(contract.principles.privateKeyLeavesDevice, false);
  assert.equal(contract.android.targetSdk, 36);
  assert.equal(contract.android.m1bTransport, 'ble-gatt');
  assert.equal(contract.ios.m1Transport, 'core-bluetooth-gatt');
  assert.equal(contract.ios.nativeCrypto, 'CryptoKit');
  assert.equal(contract.ios.wifiAware.status, 'planned-m1c');
  assert.equal(contract.session.identitySigning, 'Ed25519');
  assert.equal(contract.session.keyAgreement, 'ephemeral-X25519');
  assert.equal(contract.session.payloadEncryption, 'AES-256-GCM');
  assert.equal(contract.session.trustGate, 'matching-safety-code');
  for (const required of ['IdentityStore', 'TrustStore', 'ReplayStore', 'MessageStore']) {
    assert.ok(contract.stores.includes(required));
  }
  for (const required of ['TransportAdapter', 'PeerDiscovery', 'PeerSession', 'DeliveryReceipt']) {
    assert.ok(contract.interfaces.includes(required));
  }
});
