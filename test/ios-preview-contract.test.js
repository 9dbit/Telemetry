import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const contract = JSON.parse(readFileSync(new URL('../mobile/contracts/mobile-contract-v1.json', import.meta.url)));
const app = JSON.parse(readFileSync(new URL('../mobile/ios-preview/app.json', import.meta.url)));
const swift = readFileSync(new URL('../mobile/ios-preview/modules/telemetry-ios-native/ios/TelemetryIosNativeModule.swift', import.meta.url), 'utf8');

test('iOS preview preserves offline M1B protocol and uses a custom native build', () => {
  assert.equal(contract.principles.offlineFirst, true);
  assert.equal(contract.principles.cloudRequiredForDiscovery, false);
  assert.equal(contract.principles.cloudRequiredForSession, false);
  assert.equal(contract.ios.m1Transport, 'core-bluetooth-gatt');
  assert.equal(contract.ios.trialRuntime, 'expo-development-build');
  assert.equal(app.expo.ios.bundleIdentifier, 'com.telemetry.ios.preview');
  assert.match(swift, /CoreBluetooth/);
  assert.match(swift, /CryptoKit/);
  assert.match(swift, /AES\.GCM/);
  assert.match(swift, /Curve25519\.Signing/);
  assert.match(swift, /Curve25519\.KeyAgreement/);
  assert.match(swift, /f0a0c0de-7e1e-4e7f-9a11-54454c454d59/i);
});
