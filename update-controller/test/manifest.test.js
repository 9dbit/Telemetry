import test from 'node:test';
import assert from 'node:assert/strict';
import { validateManifest } from '../server.js';

function manifest(overrides = {}) {
  return {
    schemaVersion: 1,
    channel: 'preview',
    versionName: '0.9.0-preview.1',
    versionCode: 20721001,
    minimumVersionCode: 0,
    mandatory: false,
    publishedAt: '2026-09-25T11:00:00Z',
    releaseNotes: 'Preview release',
    apkUrl: 'https://github.com/9dbit/Telemetry/releases/download/android-preview-20721001/Telemetry-Android-Preview.apk',
    sha256: 'a'.repeat(64),
    sizeBytes: 123456,
    packageName: 'com.telemetry.preview',
    signingCertificateSha256: 'B'.repeat(64),
    releaseTag: 'android-preview-20721001',
    sourceCommit: 'c'.repeat(40),
    ...overrides
  };
}

test('accepts a valid promoted preview manifest', () => {
  const value = manifest();
  assert.equal(validateManifest(value, 'preview', value.releaseTag), value);
});

test('rejects wrong package, downgrade metadata shape, and untrusted APK URL', () => {
  const value = manifest();
  assert.throws(() => validateManifest({ ...value, packageName: 'com.attacker.app' }, 'preview', value.releaseTag), /package-name-mismatch/);
  assert.throws(() => validateManifest({ ...value, versionCode: 0 }, 'preview', value.releaseTag), /invalid-version-code/);
  assert.throws(() => validateManifest({ ...value, apkUrl: 'https://example.com/fake.apk' }, 'preview', value.releaseTag), /untrusted-apk-url/);
});

test('rejects cross-channel and tag substitution', () => {
  const value = manifest();
  assert.throws(() => validateManifest(value, 'stable', value.releaseTag), /channel|package/i);
  assert.throws(() => validateManifest(value, 'preview', 'android-preview-999'), /tag-mismatch/);
});
