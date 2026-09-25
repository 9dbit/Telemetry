import test from 'node:test';
import assert from 'node:assert/strict';
import { generateDeviceIdentity } from '../src/protocol/identity.js';
import {
  CALL_SIGNAL_CONTENT_TYPE,
  CallSessionStateMachine,
  createCallSignal,
  createCallSignalEnvelope,
  openCallSignalEnvelope,
  selectRealtimeTransport,
  validateCallSignal
} from '../src/protocol/call.js';
import { encodeControlEnvelopeWire, decodeControlEnvelopeWire } from '../src/protocol/control-envelope-wire.js';

test('call signaling is signed, encrypted, TCE1-compatible and identity-bound', () => {
  const caller = generateDeviceIdentity();
  const callee = generateDeviceIdentity();
  const sessionKey = Buffer.alloc(32, 0x42);
  const callId = 'call-encrypted-0001';
  const signal = createCallSignal({
    kind: 'invite',
    callId,
    callerId: caller.deviceId,
    calleeId: callee.deviceId,
    fromId: caller.deviceId,
    sequence: 1,
    now: new Date('2026-09-25T09:00:00.000Z'),
    directTransports: ['wifi-local', 'wifi-direct']
  });

  const envelope = createCallSignalEnvelope({
    senderIdentity: caller,
    recipientId: callee.deviceId,
    sessionKey,
    signal,
    conversationId: `call:${callId}`
  });
  assert.equal(envelope.contentType, CALL_SIGNAL_CONTENT_TYPE);

  const wire = encodeControlEnvelopeWire(envelope);
  assert.equal(wire.subarray(0, 4).toString('ascii'), 'TCE1');
  const decoded = decodeControlEnvelopeWire(wire);
  const opened = openCallSignalEnvelope({
    envelope: decoded,
    senderSigningPublicKey: caller.signing.publicKey,
    sessionKey,
    expectedRecipientId: callee.deviceId
  });
  assert.deepEqual(opened, signal);

  assert.throws(() => openCallSignalEnvelope({
    envelope: decoded,
    senderSigningPublicKey: callee.signing.publicKey,
    sessionKey,
    expectedRecipientId: callee.deviceId
  }), /signature/i);
});

test('realtime transport policy only accepts direct Wi-Fi-class paths', () => {
  assert.equal(selectRealtimeTransport(['ble', 'lora', 'mesh-relay']), null);
  assert.equal(selectRealtimeTransport(['ble', 'wifi-local']), 'wifi-local');
  assert.equal(selectRealtimeTransport(['wifi-local', 'wifi-aware']), 'wifi-aware');
  assert.equal(selectRealtimeTransport(['satellite-gateway', 'wifi-direct']), 'wifi-direct');

  assert.throws(() => createCallSignal({
    kind: 'accept',
    callId: 'call-no-ble-live-audio',
    callerId: 'tlm:device:caller-00000001',
    calleeId: 'tlm:device:callee-00000001',
    fromId: 'tlm:device:callee-00000001',
    sequence: 2,
    transport: 'ble'
  }), /direct Wi-Fi-class/i);
});

test('caller lifecycle supports encrypted WebRTC offer answer ICE connected and end', () => {
  const callerId = 'tlm:device:caller-00000002';
  const calleeId = 'tlm:device:callee-00000002';
  const callId = 'call-lifecycle-0001';
  const now = new Date('2026-09-25T09:00:00.000Z');
  const session = new CallSessionStateMachine({ callId, callerId, calleeId, localDeviceId: callerId });

  let result = session.apply(createCallSignal({
    kind: 'ringing', callId, callerId, calleeId, fromId: calleeId, sequence: 1, now
  }), { now });
  assert.equal(result.accepted, true);
  assert.equal(session.snapshot().state, 'outgoing-ringing');

  result = session.apply(createCallSignal({
    kind: 'accept', callId, callerId, calleeId, fromId: calleeId, sequence: 2, now,
    transport: 'wifi-local'
  }), { now });
  assert.equal(result.accepted, true);
  assert.equal(session.snapshot().state, 'negotiating');

  result = session.apply(createCallSignal({
    kind: 'answer', callId, callerId, calleeId, fromId: calleeId, sequence: 3, now,
    transport: 'wifi-local', sessionDescription: 'v=0\r\no=- answer-sdp-for-telemetry-call\r\n'
  }), { now });
  assert.equal(result.accepted, true);

  result = session.apply(createCallSignal({
    kind: 'ice-candidate', callId, callerId, calleeId, fromId: calleeId, sequence: 4, now,
    transport: 'wifi-local',
    iceCandidate: 'candidate:1 1 UDP 2122260223 192.168.1.20 54321 typ host',
    sdpMid: '0', sdpMLineIndex: 0
  }), { now });
  assert.equal(result.accepted, true);

  result = session.apply(createCallSignal({
    kind: 'connected', callId, callerId, calleeId, fromId: calleeId, sequence: 5, now,
    transport: 'wifi-local'
  }), { now });
  assert.equal(result.accepted, true);
  assert.equal(session.snapshot().state, 'active');

  result = session.apply(createCallSignal({
    kind: 'end', callId, callerId, calleeId, fromId: calleeId, sequence: 6, now,
    reason: 'remote-hangup'
  }), { now });
  assert.equal(result.accepted, true);
  assert.equal(session.snapshot().state, 'ended');
});

test('callee state accepts encrypted offer and ICE only on selected direct transport', () => {
  const callerId = 'tlm:device:caller-00000005';
  const calleeId = 'tlm:device:callee-00000005';
  const callId = 'call-callee-webrtc-0001';
  const now = new Date('2026-09-25T09:00:00.000Z');
  const session = new CallSessionStateMachine({ callId, callerId, calleeId, localDeviceId: calleeId });
  const invite = createCallSignal({
    kind: 'invite', callId, callerId, calleeId, fromId: callerId, sequence: 1, now,
    directTransports: ['wifi-local']
  });
  assert.equal(session.apply(invite, { now }).accepted, true);

  const offer = createCallSignal({
    kind: 'offer', callId, callerId, calleeId, fromId: callerId, sequence: 2, now,
    transport: 'wifi-local', sessionDescription: 'v=0\r\no=- offer-sdp-for-telemetry-call\r\n'
  });
  assert.equal(session.apply(offer, { now }).accepted, true);
  assert.equal(session.snapshot().state, 'negotiating');
  assert.equal(session.snapshot().selectedTransport, 'wifi-local');

  const badIce = createCallSignal({
    kind: 'ice-candidate', callId, callerId, calleeId, fromId: callerId, sequence: 3, now,
    transport: 'wifi-aware',
    iceCandidate: 'candidate:2 1 UDP 2122260223 10.0.0.2 55555 typ host',
    sdpMid: '0', sdpMLineIndex: 0
  });
  const rejected = session.apply(badIce, { now });
  assert.equal(rejected.accepted, false);
  assert.equal(rejected.reason, 'transport-mismatch');
});

test('callee invite expires deterministically and stale/replayed signaling is rejected', () => {
  const callerId = 'tlm:device:caller-00000003';
  const calleeId = 'tlm:device:callee-00000003';
  const callId = 'call-timeout-0001';
  const created = new Date('2026-09-25T09:00:00.000Z');
  const session = new CallSessionStateMachine({ callId, callerId, calleeId, localDeviceId: calleeId });
  const invite = createCallSignal({
    kind: 'invite', callId, callerId, calleeId, fromId: callerId, sequence: 10,
    now: created, timeoutMs: 30_000, directTransports: ['wifi-aware', 'wifi-local']
  });

  const accepted = session.apply(invite, { now: new Date('2026-09-25T09:00:05.000Z') });
  assert.equal(accepted.accepted, true);
  const replay = session.apply(invite, { now: new Date('2026-09-25T09:00:06.000Z') });
  assert.equal(replay.reason, 'replay-or-stale-sequence');
  assert.equal(session.expire({ now: new Date('2026-09-25T09:00:31.000Z') }), true);
  assert.equal(session.snapshot().state, 'timed-out');
});

test('call state rejects participant mismatch, transport mismatch and malformed SDP/ICE', () => {
  const callerId = 'tlm:device:caller-00000004';
  const calleeId = 'tlm:device:callee-00000004';
  const callId = 'call-guard-0001';
  const now = new Date('2026-09-25T09:00:00.000Z');
  const session = new CallSessionStateMachine({ callId, callerId, calleeId, localDeviceId: callerId });

  const connectedTooEarly = createCallSignal({
    kind: 'connected', callId, callerId, calleeId, fromId: calleeId, sequence: 1, now,
    transport: 'wifi-local'
  });
  assert.equal(session.apply(connectedTooEarly, { now }).reason, 'unexpected-connected');

  assert.throws(() => createCallSignal({
    kind: 'offer', callId, callerId, calleeId, fromId: callerId, sequence: 1, now,
    transport: 'wifi-local', sessionDescription: 'x'
  }), /session description/i);

  assert.throws(() => createCallSignal({
    kind: 'ice-candidate', callId, callerId, calleeId, fromId: callerId, sequence: 1, now,
    transport: 'wifi-local', iceCandidate: '', sdpMid: '0', sdpMLineIndex: 0
  }), /ICE candidate/i);

  assert.throws(() => validateCallSignal({
    version: 'telemetry/call-signal/0.1',
    kind: 'ringing',
    callId,
    callerId,
    calleeId,
    fromId: 'tlm:device:intruder-000000',
    toId: calleeId,
    sequence: 99,
    media: 'audio',
    createdAt: now.toISOString()
  }), /participant mismatch/i);
});
