const EVENT_TYPES = new Set([
  'app.download',
  'app.session',
  'device.heartbeat',
  'profile.updated',
  'contacts.snapshot',
  'transport.usage',
  'communication.summary',
  'resource.usage',
  'media.transfer',
  'call.summary',
  'screen.time',
  'location.snapshot',
  'device.status'
]);

const MEDIA_KINDS = new Set(['photo', 'video', 'file']);
const CALL_KINDS = new Set(['voice', 'video']);
const TRANSPORTS = new Set(['internet', 'wifi', 'wifi-direct', 'ble', 'lora', 'satellite']);
const DEVICE_STATES = new Set(['active', 'suspended', 'released']);

const forbiddenKeys = new Set([
  'message', 'text', 'body', 'ciphertext', 'plaintext', 'filename', 'fileName',
  'audio', 'videoData', 'privateKey', 'signingPrivateKey', 'exchangePrivateKey',
  'safetyCode', 'contactAlias', 'contactName'
]);

export function validateTelemetryEvent(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return fail('event must be an object');
  if (!EVENT_TYPES.has(input.type)) return fail('unsupported event type');
  if (!validId(input.installId, 96)) return fail('invalid installId');
  if (!validTimestamp(input.timestamp)) return fail('invalid timestamp');
  if (containsForbiddenKey(input)) return fail('payload contains private-content field');

  const data = input.data && typeof input.data === 'object' && !Array.isArray(input.data) ? input.data : {};
  const error = validateData(input.type, data);
  if (error) return fail(error);

  return {
    ok: true,
    event: {
      type: input.type,
      installId: input.installId,
      timestamp: input.timestamp,
      platform: cleanString(input.platform, 24),
      appVersion: cleanString(input.appVersion, 32),
      data
    }
  };
}

export function validateTelemetryBatch(body, maxEvents = 200) {
  if (!body || !Array.isArray(body.events)) return fail('events must be an array');
  if (body.events.length < 1 || body.events.length > maxEvents) return fail(`batch must contain 1-${maxEvents} events`);
  const events = [];
  for (const item of body.events) {
    const result = validateTelemetryEvent(item);
    if (!result.ok) return result;
    events.push(result.event);
  }
  return { ok: true, events };
}

function validateData(type, data) {
  if (type === 'contacts.snapshot' && !nonNegativeInt(data.pairedCount)) return 'pairedCount must be a non-negative integer';
  if (type === 'transport.usage') {
    if (!TRANSPORTS.has(data.transport)) return 'invalid transport';
    if (!nonNegativeInt(data.bytesSent) || !nonNegativeInt(data.bytesReceived)) return 'invalid transport byte counters';
  }
  if (type === 'communication.summary') {
    if (!nonNegativeInt(data.messagesSent) || !nonNegativeInt(data.messagesReceived) || !nonNegativeInt(data.activeConversations)) return 'invalid communication counters';
  }
  if (type === 'resource.usage') {
    if (!nonNegativeInt(data.cpuMs) || !nonNegativeInt(data.radioSeconds)) return 'invalid resource counters';
    if (data.batteryPermille != null && (!nonNegativeInt(data.batteryPermille) || data.batteryPermille > 1000)) return 'invalid battery usage';
  }
  if (type === 'media.transfer') {
    if (!MEDIA_KINDS.has(data.kind)) return 'invalid media kind';
    if (!nonNegativeInt(data.bytes)) return 'invalid media bytes';
  }
  if (type === 'call.summary') {
    if (!CALL_KINDS.has(data.kind)) return 'invalid call kind';
    if (!nonNegativeInt(data.durationSeconds)) return 'invalid call duration';
  }
  if (type === 'screen.time' && !nonNegativeInt(data.seconds)) return 'invalid screen time';
  if (type === 'location.snapshot') {
    if (data.consent !== true) return 'location requires explicit consent';
    if (!validLatLng(data.latitude, data.longitude)) return 'invalid location';
    if (data.accuracyMeters != null && (!Number.isFinite(data.accuracyMeters) || data.accuracyMeters < 0)) return 'invalid location accuracy';
  }
  if (type === 'device.status' && !DEVICE_STATES.has(data.state)) return 'invalid device state';
  return '';
}

function containsForbiddenKey(value) {
  if (!value || typeof value !== 'object') return false;
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenKeys.has(key)) return true;
    if (containsForbiddenKey(child)) return true;
  }
  return false;
}

function cleanString(value, max) {
  return typeof value === 'string' ? value.slice(0, max) : undefined;
}
function validId(value, max) { return typeof value === 'string' && value.length >= 8 && value.length <= max; }
function validTimestamp(value) { return typeof value === 'string' && Number.isFinite(Date.parse(value)); }
function nonNegativeInt(value) { return Number.isInteger(value) && value >= 0; }
function validLatLng(lat, lng) { return Number.isFinite(lat) && lat >= -90 && lat <= 90 && Number.isFinite(lng) && lng >= -180 && lng <= 180; }
function fail(error) { return { ok: false, error }; }
