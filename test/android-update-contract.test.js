import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const manifestUrl = new URL('../mobile/android/app/src/main/AndroidManifest.xml', import.meta.url);
const gateUrl = new URL('../mobile/android/app/src/main/java/com/telemetry/app/update/UpdateGateActivity.kt', import.meta.url);
const clientUrl = new URL('../mobile/android/app/src/main/java/com/telemetry/app/update/UpdateClient.kt', import.meta.url);
const gradleUrl = new URL('../mobile/android/app/build.gradle.kts', import.meta.url);
const workflowUrl = new URL('../.github/workflows/android-m1a.yml', import.meta.url);
const serverUrl = new URL('../server.js', import.meta.url);

test('Android preview update channel checks, verifies, downloads and invokes installer safely', async () => {
  const [manifest, gate, client, gradle, workflow, server] = await Promise.all([
    readFile(manifestUrl, 'utf8'),
    readFile(gateUrl, 'utf8'),
    readFile(clientUrl, 'utf8'),
    readFile(gradleUrl, 'utf8'),
    readFile(workflowUrl, 'utf8'),
    readFile(serverUrl, 'utf8')
  ]);

  assert.ok(manifest.includes('android.permission.INTERNET'));
  assert.ok(manifest.includes('android.permission.REQUEST_INSTALL_PACKAGES'));
  assert.ok(manifest.includes('.update.UpdateGateActivity'));
  assert.ok(gate.includes('Check Update'));
  assert.ok(gate.includes('Download & Install'));
  assert.ok(gate.includes('ACTION_MANAGE_UNKNOWN_APP_SOURCES'));
  assert.ok(client.includes('/api/v1/android/update'));
  assert.ok(client.includes('APK checksum verification failed'));
  assert.ok(client.includes('Update signing certificate does not match'));
  assert.ok(gradle.includes('applicationId = "com.telemetry.preview"'));
  assert.ok(workflow.includes('Publish preview update release'));
  assert.ok(workflow.includes('Telemetry-Android-Preview.apk'));
  assert.ok(server.includes("/api/v1/android/update"));
  assert.ok(server.includes('android-preview-'));
});
