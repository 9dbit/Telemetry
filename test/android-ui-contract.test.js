import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const sourceUrl = new URL('../mobile/android/app/src/main/java/com/telemetry/app/MainActivity.kt', import.meta.url);

test('Android UI v2 exposes messages, nearby diagnostics, network and SOS without weakening offline security', async () => {
  const source = await readFile(sourceUrl, 'utf8');

  for (const screen of ['MESSAGES', 'NEARBY', 'VERIFY', 'CHAT', 'NETWORK', 'SETTINGS', 'SOS']) {
    assert.match(source, new RegExp(`AppScreen\\.${screen}`));
  }

  for (const copy of [
    'Messages',
    'Nearby Devices',
    'Potential Telemetry Devices',
    'Network Diagnostics',
    'Verify Identity',
    'Codes match · Trust peer',
    'Offline · Encrypted',
    'signed receipt verified',
    'SOS Broadcast',
    'HOLD 2 SECONDS TO SEND SOS',
    'Stable identity is never placed in BLE advertisements.',
    'Find Telemetry peers without internet.'
  ]) {
    assert.ok(source.includes(copy), `missing UI v2 contract copy: ${copy}`);
  }

  assert.ok(source.includes('sendEmergency()'), 'SOS must be wired to a real action');
  assert.ok(source.includes('transport?.sendMessage(address, text)'), 'SOS/chat must use the encrypted M1B transport');
  assert.ok(source.includes('RSSI'), 'Nearby scanner should expose radio diagnostics');
  assert.ok(source.includes('AES-256-GCM'), 'Network UI should state the active payload encryption');
});

test('Android chat attachment composer is wired to the shared encrypted media runtime', async () => {
  const source = await readFile(sourceUrl, 'utf8');

  for (const token of [
    'AndroidMediaAppRuntime',
    'ActivityResultContracts.OpenDocument()',
    'onAttach = ::pickMedia',
    'mediaRuntime?.queueUri(',
    'mediaRuntime?.observeTrustedPeer(event.deviceId)',
    'NativeMediaTransferEvent.OutgoingProgress',
    'NativeMediaTransferEvent.IncomingReady',
    'Attachment delivered and verified',
    'Received attachment verified',
    '"image/*"',
    '"video/*"'
  ]) {
    assert.ok(source.includes(token), `missing secure media composer contract: ${token}`);
  }

  assert.ok(
    source.includes('enabled = state.trustedDeviceId != null'),
    'attachment action must stay disabled until a peer is trusted'
  );
  assert.ok(
    !source.includes('android.permission.READ_EXTERNAL_STORAGE'),
    'OpenDocument flow must not require broad legacy storage permission'
  );
});
