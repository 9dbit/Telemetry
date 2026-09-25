import test from 'node:test';
import assert from 'node:assert/strict';
import { validateTelemetryBatch, validateTelemetryEvent } from '../src/control-plane/event-schema.js';

const base = {
  installId: 'install_123456789',
  timestamp: '2026-09-26T01:00:00.000Z',
  platform: 'ios',
  appVersion: '0.1.1'
};

test('accepts metadata-only transport usage', () => {
  const result = validateTelemetryEvent({ ...base, type: 'transport.usage', data: { transport: 'wifi-direct', bytesSent: 4096, bytesReceived: 1024 } });
  assert.equal(result.ok, true);
});

test('rejects message text and filenames anywhere in telemetry payload', () => {
  assert.equal(validateTelemetryEvent({ ...base, type: 'media.transfer', data: { kind: 'photo', bytes: 20, fileName: 'private.jpg' } }).ok, false);
  assert.equal(validateTelemetryEvent({ ...base, type: 'app.session', data: { nested: { text: 'hello' } } }).ok, false);
});

test('location requires explicit consent', () => {
  assert.equal(validateTelemetryEvent({ ...base, type: 'location.snapshot', data: { latitude: -6.2, longitude: 106.8 } }).ok, false);
  assert.equal(validateTelemetryEvent({ ...base, type: 'location.snapshot', data: { consent: true, latitude: -6.2, longitude: 106.8, accuracyMeters: 120 } }).ok, true);
});

test('validates aggregate call/media/contact metrics', () => {
  const events = [
    { ...base, type: 'contacts.snapshot', data: { pairedCount: 4 } },
    { ...base, type: 'media.transfer', data: { kind: 'video', bytes: 1048576, direction: 'outgoing' } },
    { ...base, type: 'call.summary', data: { kind: 'voice', durationSeconds: 73 } },
    { ...base, type: 'screen.time', data: { seconds: 480 } }
  ];
  const result = validateTelemetryBatch({ events });
  assert.equal(result.ok, true);
  assert.equal(result.events.length, 4);
});


test('accepts aggregate communication and resource usage without content', () => {
  assert.equal(validateTelemetryEvent({ ...base, type: 'communication.summary', data: { messagesSent: 12, messagesReceived: 9, activeConversations: 3 } }).ok, true);
  assert.equal(validateTelemetryEvent({ ...base, type: 'resource.usage', data: { cpuMs: 3200, radioSeconds: 42, batteryPermille: 17 } }).ok, true);
});
