import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const sourceUrl = new URL('../mobile/android/app/src/main/java/com/telemetry/app/MainActivity.kt', import.meta.url);

test('Android UI polish keeps the offline product flow visible', async () => {
  const source = await readFile(sourceUrl, 'utf8');
  for (const screen of ['HOME', 'DISCOVER', 'VERIFY', 'CHAT']) {
    assert.match(source, new RegExp(`AppScreen\\.${screen}`));
  }
  for (const copy of [
    'Start Offline',
    'Discover Peers',
    'Verify Identity',
    'Codes match · Trust peer',
    'Offline · Encrypted',
    'signed receipt verified'
  ]) {
    assert.ok(source.includes(copy), `missing UI contract copy: ${copy}`);
  }
});
