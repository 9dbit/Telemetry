import { selectTransport } from './router.js';

function frameBytes(frame) {
  if (Buffer.isBuffer(frame?.wire)) return frame.wire.length;
  if (frame?.wire instanceof Uint8Array) return frame.wire.byteLength;
  return Buffer.byteLength(JSON.stringify(frame || {}), 'utf8');
}

export class TransportAdapter {
  constructor({ id, metered = false, maxPayloadBytes = Infinity } = {}) {
    if (!id) throw new Error('transport id is required');
    this.id = id;
    this.metered = Boolean(metered);
    this.maxPayloadBytes = Number.isFinite(maxPayloadBytes) ? maxPayloadBytes : Infinity;
  }

  describePeer() {
    throw new Error(`${this.id}.describePeer() not implemented`);
  }

  async send() {
    throw new Error(`${this.id}.send() not implemented`);
  }
}

export function assertTransportAdapter(adapter) {
  if (!adapter?.id) throw new Error('adapter.id is required');
  if (typeof adapter.describePeer !== 'function') throw new Error(`${adapter.id}.describePeer must be a function`);
  if (typeof adapter.send !== 'function') throw new Error(`${adapter.id}.send must be a function`);
  return adapter;
}

export class TransportCoordinator {
  constructor({ adapters = [] } = {}) {
    this.adapters = new Map();
    for (const adapter of adapters) this.register(adapter);
  }

  register(adapter) {
    assertTransportAdapter(adapter);
    if (this.adapters.has(adapter.id)) throw new Error(`duplicate transport adapter: ${adapter.id}`);
    this.adapters.set(adapter.id, adapter);
    return adapter;
  }

  unregister(id) {
    return this.adapters.delete(id);
  }

  candidates(peerId, frame, { excludeTransports = [] } = {}) {
    if (!peerId) throw new Error('peerId is required');
    const payloadBytes = frameBytes(frame);
    const excluded = new Set(excludeTransports);
    const transports = [];

    for (const adapter of this.adapters.values()) {
      if (excluded.has(adapter.id)) continue;
      const peer = adapter.describePeer(peerId) || {};
      const withinLimit = payloadBytes <= (adapter.maxPayloadBytes ?? Infinity);
      transports.push({
        id: adapter.id,
        available: Boolean(peer.available) && withinLimit,
        quality: Number.isFinite(peer.quality) ? peer.quality : null,
        metered: adapter.metered || Boolean(peer.metered),
        payloadBytes,
        maxPayloadBytes: adapter.maxPayloadBytes ?? Infinity
      });
    }

    const decision = selectTransport({ payloadBytes, transports });
    return {
      payloadBytes,
      selected: decision.selected,
      reason: decision.reason,
      candidates: decision.candidates
    };
  }

  async send(peerId, frame, { excludeTransports = [], context = {} } = {}) {
    if (!frame?.messageId && !frame?.envelope?.messageId) throw new Error('frame with messageId is required');
    const decision = this.candidates(peerId, frame, { excludeTransports });
    if (!decision.selected) {
      return {
        sent: false,
        reason: 'no-transport-available',
        transportId: null,
        attempts: [],
        payloadBytes: decision.payloadBytes
      };
    }

    const attempts = [];
    for (const candidate of decision.candidates) {
      const adapter = this.adapters.get(candidate.id);
      if (!adapter) continue;
      try {
        const accepted = await adapter.send(peerId, frame, {
          ...context,
          payloadBytes: decision.payloadBytes,
          transportId: adapter.id
        });
        attempts.push({ transportId: adapter.id, accepted: Boolean(accepted) });
        if (accepted) {
          return {
            sent: true,
            reason: attempts.length === 1 ? 'selected-transport' : 'fallback-transport',
            transportId: adapter.id,
            attempts,
            payloadBytes: decision.payloadBytes
          };
        }
      } catch (error) {
        attempts.push({
          transportId: adapter.id,
          accepted: false,
          error: String(error?.message || error)
        });
      }
    }

    return {
      sent: false,
      reason: 'all-transports-failed',
      transportId: null,
      attempts,
      payloadBytes: decision.payloadBytes
    };
  }
}

export class MockTransportAdapter extends TransportAdapter {
  constructor({ id, metered = false, maxPayloadBytes = Infinity } = {}) {
    super({ id, metered, maxPayloadBytes });
    this.peers = new Map();
    this.transmissions = [];
    this.failures = [];
  }

  setPeer(peerId, { available = true, quality = 0, metered = false } = {}) {
    this.peers.set(peerId, { available: Boolean(available), quality, metered: Boolean(metered) });
    return this.describePeer(peerId);
  }

  setAvailable(peerId, available) {
    const current = this.peers.get(peerId) || { quality: 0, metered: false };
    return this.setPeer(peerId, { ...current, available });
  }

  failNext(peerId, error = 'mock transport failure') {
    this.failures.push({ peerId, error: String(error) });
  }

  describePeer(peerId) {
    return this.peers.get(peerId) || { available: false, quality: null, metered: this.metered };
  }

  async send(peerId, frame, context = {}) {
    const peer = this.describePeer(peerId);
    if (!peer.available) return false;
    const payloadBytes = frameBytes(frame);
    if (payloadBytes > this.maxPayloadBytes) return false;

    const failureIndex = this.failures.findIndex((failure) => failure.peerId === peerId);
    if (failureIndex >= 0) {
      const [failure] = this.failures.splice(failureIndex, 1);
      throw new Error(failure.error);
    }

    this.transmissions.push({
      peerId,
      frame,
      context,
      payloadBytes,
      at: new Date().toISOString()
    });
    return true;
  }

  clear() {
    this.transmissions.length = 0;
    this.failures.length = 0;
  }
}

export function serializedFrameBytes(frame) {
  return frameBytes(frame);
}
