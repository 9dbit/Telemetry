import { randomUUID } from 'node:crypto';
import { sealPayload, openPayload } from './crypto.js';
import { createSignedEnvelope, verifyEnvelope } from './envelope.js';

export const CALL_SIGNAL_VERSION = 'telemetry/call-signal/0.1';
export const CALL_SIGNAL_CONTENT_TYPE = 'application/telemetry+call-signal';
export const CALL_MEDIA_KIND_AUDIO = 'audio';
export const CALL_DEFAULT_TIMEOUT_MS = 45_000;

export const REALTIME_DIRECT_TRANSPORTS = Object.freeze([
  'wifi-direct',
  'wifi-aware',
  'wifi-local'
]);

const SIGNAL_KINDS = new Set([
  'invite',
  'ringing',
  'accept',
  'candidate',
  'connected',
  'decline',
  'busy',
  'cancel',
  'end'
]);

const TERMINAL_SIGNAL_KINDS = new Set(['decline', 'busy', 'cancel', 'end']);
const TERMINAL_STATES = new Set(['declined', 'busy', 'cancelled', 'ended', 'timed-out']);

function toMs(value) {
  const ms = value instanceof Date ? value.getTime() : new Date(value).getTime();
  if (!Number.isFinite(ms)) throw new Error('invalid call time');
  return ms;
}

function validateDeviceId(value, label) {
  if (typeof value !== 'string' || value.length < 8 || value.length > 160) {
    throw new Error(`invalid ${label}`);
  }
}

function validateDirectTransport(value) {
  if (!REALTIME_DIRECT_TRANSPORTS.includes(value)) {
    throw new Error('live call transport must be a direct Wi-Fi-class path');
  }
  return value;
}

export function selectRealtimeTransport(capabilities = []) {
  if (!Array.isArray(capabilities)) return null;
  const normalized = new Set(capabilities.filter((value) => typeof value === 'string'));
  return REALTIME_DIRECT_TRANSPORTS.find((transport) => normalized.has(transport)) || null;
}

export function validateCallSignal(signal) {
  if (!signal || signal.version !== CALL_SIGNAL_VERSION) throw new Error('unsupported call signal version');
  if (!SIGNAL_KINDS.has(signal.kind)) throw new Error('unsupported call signal kind');
  if (typeof signal.callId !== 'string' || signal.callId.length < 8 || signal.callId.length > 128) {
    throw new Error('invalid callId');
  }
  validateDeviceId(signal.callerId, 'callerId');
  validateDeviceId(signal.calleeId, 'calleeId');
  validateDeviceId(signal.fromId, 'fromId');
  validateDeviceId(signal.toId, 'toId');
  if (signal.fromId === signal.toId) throw new Error('self call signal rejected');
  if (!new Set([signal.callerId, signal.calleeId]).has(signal.fromId) ||
      !new Set([signal.callerId, signal.calleeId]).has(signal.toId)) {
    throw new Error('call participant mismatch');
  }
  if (signal.fromId === signal.callerId && signal.toId !== signal.calleeId) throw new Error('wrong call signal recipient');
  if (signal.fromId === signal.calleeId && signal.toId !== signal.callerId) throw new Error('wrong call signal recipient');
  if (!Number.isInteger(signal.sequence) || signal.sequence < 0) throw new Error('invalid call signal sequence');
  if (!Number.isFinite(toMs(signal.createdAt))) throw new Error('invalid call signal createdAt');
  if (signal.media !== CALL_MEDIA_KIND_AUDIO) throw new Error('unsupported call media kind');

  if (signal.kind === 'invite') {
    if (signal.fromId !== signal.callerId) throw new Error('only caller may invite');
    if (!Array.isArray(signal.directTransports) || signal.directTransports.length < 1) {
      throw new Error('call invite requires direct transport capabilities');
    }
    signal.directTransports.forEach(validateDirectTransport);
    const expiryMs = toMs(signal.expiresAt);
    if (expiryMs <= toMs(signal.createdAt)) throw new Error('call invite expiry invalid');
    if (expiryMs - toMs(signal.createdAt) > 120_000) throw new Error('call invite expiry too long');
  }

  if (signal.kind === 'accept' || signal.kind === 'candidate' || signal.kind === 'connected') {
    validateDirectTransport(signal.transport);
  }

  if (signal.kind === 'candidate') {
    if (typeof signal.endpointToken !== 'string' || signal.endpointToken.length < 8 || signal.endpointToken.length > 512) {
      throw new Error('invalid direct candidate token');
    }
  }

  if (signal.reason != null && (typeof signal.reason !== 'string' || signal.reason.length > 160)) {
    throw new Error('invalid call reason');
  }
  return signal;
}

export function createCallSignal({
  kind,
  callId = randomUUID(),
  callerId,
  calleeId,
  fromId,
  sequence,
  now = new Date(),
  timeoutMs = CALL_DEFAULT_TIMEOUT_MS,
  directTransports,
  transport,
  endpointToken,
  reason
}) {
  const createdAt = new Date(toMs(now)).toISOString();
  const signal = {
    version: CALL_SIGNAL_VERSION,
    kind,
    callId,
    callerId,
    calleeId,
    fromId,
    toId: fromId === callerId ? calleeId : callerId,
    sequence,
    media: CALL_MEDIA_KIND_AUDIO,
    createdAt
  };
  if (kind === 'invite') {
    if (!Number.isInteger(timeoutMs) || timeoutMs < 5_000 || timeoutMs > 120_000) {
      throw new Error('invalid call timeout');
    }
    signal.expiresAt = new Date(toMs(now) + timeoutMs).toISOString();
    signal.directTransports = [...new Set(directTransports || [])];
  }
  if (transport != null) signal.transport = transport;
  if (endpointToken != null) signal.endpointToken = endpointToken;
  if (reason != null) signal.reason = reason;
  return validateCallSignal(signal);
}

export function createCallSignalEnvelope({
  senderIdentity,
  recipientId,
  sessionKey,
  signal,
  conversationId,
  hopLimit = 8
}) {
  validateCallSignal(signal);
  if (senderIdentity.deviceId !== signal.fromId) throw new Error('call signal sender identity mismatch');
  if (recipientId !== signal.toId) throw new Error('call signal envelope recipient mismatch');
  return createSignedEnvelope({
    senderIdentity,
    recipientId,
    sealedPayload: sealPayload(sessionKey, signal),
    conversationId,
    contentType: CALL_SIGNAL_CONTENT_TYPE,
    hopLimit
  });
}

export function openCallSignalEnvelope({
  envelope,
  senderSigningPublicKey,
  sessionKey,
  expectedRecipientId
}) {
  if (envelope?.contentType !== CALL_SIGNAL_CONTENT_TYPE) throw new Error('unsupported call signal content type');
  if (!verifyEnvelope(envelope, senderSigningPublicKey)) throw new Error('invalid call signal envelope signature');
  if (expectedRecipientId && envelope.recipientId !== expectedRecipientId) throw new Error('wrong call signal envelope recipient');
  const signal = validateCallSignal(openPayload(sessionKey, envelope.payload));
  if (signal.fromId !== envelope.senderId || signal.toId !== envelope.recipientId) {
    throw new Error('call signal identity binding mismatch');
  }
  return signal;
}

export class CallSessionStateMachine {
  constructor({
    callId,
    callerId,
    calleeId,
    localDeviceId,
    inviteExpiresAt = null
  }) {
    if (typeof callId !== 'string' || callId.length < 8) throw new Error('invalid callId');
    validateDeviceId(callerId, 'callerId');
    validateDeviceId(calleeId, 'calleeId');
    if (callerId === calleeId) throw new Error('call participants must differ');
    if (![callerId, calleeId].includes(localDeviceId)) throw new Error('local device is not a call participant');
    this.callId = callId;
    this.callerId = callerId;
    this.calleeId = calleeId;
    this.localDeviceId = localDeviceId;
    this.inviteExpiresAtMs = inviteExpiresAt == null ? null : toMs(inviteExpiresAt);
    this.state = localDeviceId === callerId ? 'outgoing' : 'idle';
    this.lastSequenceBySender = new Map();
    this.selectedTransport = null;
    this.connectedAt = null;
    this.endedReason = null;
  }

  apply(signal, { now = new Date() } = {}) {
    validateCallSignal(signal);
    if (signal.callId !== this.callId || signal.callerId !== this.callerId || signal.calleeId !== this.calleeId) {
      return { accepted: false, reason: 'session-mismatch', state: this.state };
    }
    if (TERMINAL_STATES.has(this.state)) return { accepted: false, reason: 'session-terminal', state: this.state };

    const previousSequence = this.lastSequenceBySender.get(signal.fromId);
    if (previousSequence != null && signal.sequence <= previousSequence) {
      return { accepted: false, reason: 'replay-or-stale-sequence', state: this.state };
    }

    const nowMs = toMs(now);
    const signalMs = toMs(signal.createdAt);
    if (Math.abs(nowMs - signalMs) > 5 * 60_000) {
      return { accepted: false, reason: 'signal-time-window', state: this.state };
    }

    if (signal.kind === 'invite') {
      if (this.localDeviceId !== this.calleeId || signal.fromId !== this.callerId) {
        return { accepted: false, reason: 'unexpected-invite', state: this.state };
      }
      const expiresAtMs = toMs(signal.expiresAt);
      if (expiresAtMs <= nowMs) {
        this.state = 'timed-out';
        this.endedReason = 'invite-expired';
        return { accepted: false, reason: 'invite-expired', state: this.state };
      }
      this.inviteExpiresAtMs = expiresAtMs;
      this.state = 'incoming-ringing';
    } else if (signal.kind === 'ringing') {
      if (this.localDeviceId !== this.callerId || signal.fromId !== this.calleeId || !['outgoing', 'outgoing-ringing'].includes(this.state)) {
        return { accepted: false, reason: 'unexpected-ringing', state: this.state };
      }
      this.state = 'outgoing-ringing';
    } else if (signal.kind === 'accept') {
      if (this.localDeviceId !== this.callerId || signal.fromId !== this.calleeId || !['outgoing', 'outgoing-ringing'].includes(this.state)) {
        return { accepted: false, reason: 'unexpected-accept', state: this.state };
      }
      this.selectedTransport = signal.transport;
      this.state = 'negotiating';
    } else if (signal.kind === 'candidate') {
      if (!['incoming-ringing', 'outgoing', 'outgoing-ringing', 'negotiating'].includes(this.state)) {
        return { accepted: false, reason: 'unexpected-candidate', state: this.state };
      }
      this.selectedTransport = signal.transport;
      this.state = 'negotiating';
    } else if (signal.kind === 'connected') {
      if (this.state !== 'negotiating') return { accepted: false, reason: 'unexpected-connected', state: this.state };
      if (this.selectedTransport && signal.transport !== this.selectedTransport) {
        return { accepted: false, reason: 'transport-mismatch', state: this.state };
      }
      this.selectedTransport = signal.transport;
      this.connectedAt = new Date(nowMs).toISOString();
      this.state = 'active';
    } else if (TERMINAL_SIGNAL_KINDS.has(signal.kind)) {
      const terminal = {
        decline: 'declined',
        busy: 'busy',
        cancel: 'cancelled',
        end: 'ended'
      }[signal.kind];
      this.state = terminal;
      this.endedReason = signal.reason || signal.kind;
    }

    this.lastSequenceBySender.set(signal.fromId, signal.sequence);
    return { accepted: true, reason: 'signal-applied', state: this.state };
  }

  expire({ now = new Date() } = {}) {
    if (TERMINAL_STATES.has(this.state) || this.state === 'active') return false;
    if (this.inviteExpiresAtMs != null && toMs(now) >= this.inviteExpiresAtMs) {
      this.state = 'timed-out';
      this.endedReason = 'invite-timeout';
      return true;
    }
    return false;
  }

  snapshot() {
    return {
      callId: this.callId,
      callerId: this.callerId,
      calleeId: this.calleeId,
      localDeviceId: this.localDeviceId,
      state: this.state,
      selectedTransport: this.selectedTransport,
      connectedAt: this.connectedAt,
      endedReason: this.endedReason
    };
  }
}
