import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const contractUrl = new URL('../mobile/contracts/mobile-contract-v1.json', import.meta.url);

test('mobile contract keeps M1A offline and privacy preserving', async () => {
  const contract = JSON.parse(await readFile(contractUrl, 'utf8'));
  assert.equal(contract.version, 'telemetry-mobile/1');
  assert.equal(contract.milestone, 'M1A-offline-peer-discovery');
  assert.equal(contract.principles.offlineFirst, true);
  assert.equal(contract.principles.cloudRequiredForDiscovery, false);
  assert.equal(contract.principles.stableIdentityInAdvertisement, false);
  assert.equal(contract.android.targetSdk, 36);
  assert.equal(contract.android.m1aTransport, 'ble');
  for (const required of ['IdentityStore', 'TrustStore', 'ReplayStore', 'MessageStore']) {
    assert.ok(contract.stores.includes(required));
  }
  for (const required of ['TransportAdapter', 'PeerDiscovery', 'PeerSession', 'DeliveryReceipt']) {
    assert.ok(contract.interfaces.includes(required));
  }
});
