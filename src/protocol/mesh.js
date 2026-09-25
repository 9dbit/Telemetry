import { forwardEnvelope } from './envelope.js';

function toMs(value) {
  const ms = value instanceof Date ? value.getTime() : new Date(value).getTime();
  if (!Number.isFinite(ms)) throw new Error('invalid time');
  return ms;
}

function iso(value) {
  return new Date(value).toISOString();
}

export class RelayDedupe {
  constructor() {
    this.seen = new Map();
  }

  accept(messageId, { now = new Date(), ttlMs = 5 * 60_000 } = {}) {
    if (!messageId) throw new Error('messageId is required');
    const nowMs = toMs(now);
    this.prune({ now });
    const existing = this.seen.get(messageId);
    if (existing && existing > nowMs) {
      return { accepted: false, reason: 'duplicate' };
    }
    this.seen.set(messageId, nowMs + ttlMs);
    return { accepted: true, reason: 'first-seen' };
  }

  prune({ now = new Date() } = {}) {
    const nowMs = toMs(now);
    for (const [messageId, expiresAt] of this.seen) {
      if (expiresAt <= nowMs) this.seen.delete(messageId);
    }
  }
}

export function createRelayFrame(envelope, {
  now = new Date(),
  ttlMs = 10 * 60_000
} = {}) {
  if (!envelope?.messageId) throw new Error('envelope.messageId is required');
  if (!envelope?.senderId) throw new Error('envelope.senderId is required');
  if (!envelope?.recipientId) throw new Error('envelope.recipientId is required');
  const nowMs = toMs(now);
  return {
    messageId: envelope.messageId,
    envelope,
    relayPath: [envelope.senderId],
    createdAt: iso(nowMs),
    expiresAt: iso(nowMs + ttlMs),
    lastForwardedAt: null
  };
}

export function ingestRelayFrame(frame, {
  localDeviceId,
  dedupe,
  now = new Date()
}) {
  if (!frame?.envelope?.messageId) return { accepted: false, action: 'drop', reason: 'invalid-frame' };
  if (!localDeviceId) throw new Error('localDeviceId is required');
  if (!(dedupe instanceof RelayDedupe)) throw new Error('dedupe is required');

  const nowMs = toMs(now);
  if (toMs(frame.expiresAt) <= nowMs) {
    return { accepted: false, action: 'drop', reason: 'expired' };
  }
  if (frame.relayPath?.includes(localDeviceId)) {
    return { accepted: false, action: 'drop', reason: 'relay-loop' };
  }
  if (frame.envelope.hopCount >= frame.envelope.hopLimit && frame.envelope.recipientId !== localDeviceId) {
    return { accepted: false, action: 'drop', reason: 'hop-limit' };
  }

  const duplicate = dedupe.accept(frame.envelope.messageId, {
    now,
    ttlMs: Math.max(1, toMs(frame.expiresAt) - nowMs)
  });
  if (!duplicate.accepted) {
    return { accepted: false, action: 'drop', reason: duplicate.reason };
  }

  return {
    accepted: true,
    action: frame.envelope.recipientId === localDeviceId ? 'deliver-local' : 'relay',
    reason: frame.envelope.recipientId === localDeviceId ? 'recipient-local' : 'relay-eligible'
  };
}

export function forwardRelayFrame(frame, {
  relayDeviceId,
  now = new Date()
}) {
  if (!relayDeviceId) throw new Error('relayDeviceId is required');
  if (!frame?.envelope) throw new Error('frame.envelope is required');
  if (toMs(frame.expiresAt) <= toMs(now)) throw new Error('relay frame expired');
  if (frame.relayPath?.includes(relayDeviceId)) throw new Error('relay loop detected');

  return {
    ...frame,
    envelope: forwardEnvelope(frame.envelope),
    relayPath: [...(frame.relayPath || []), relayDeviceId],
    lastForwardedAt: iso(toMs(now))
  };
}

export class MeshRouteTable {
  constructor() {
    this.routes = new Map();
  }

  observe({
    destinationId,
    viaPeerId,
    hops = 1,
    quality = 0,
    transport = 'ble',
    ttlMs = 30_000,
    now = new Date()
  }) {
    if (!destinationId || !viaPeerId) throw new Error('destinationId and viaPeerId are required');
    if (!Number.isInteger(hops) || hops < 1 || hops > 32) throw new Error('hops must be between 1 and 32');
    const nowMs = toMs(now);
    const route = {
      destinationId,
      viaPeerId,
      hops,
      quality: Number.isFinite(quality) ? Math.max(-100, Math.min(100, quality)) : 0,
      transport,
      observedAt: iso(nowMs),
      expiresAt: iso(nowMs + ttlMs)
    };
    const key = `${destinationId}|${viaPeerId}|${transport}`;
    this.routes.set(key, route);
    return route;
  }

  best(destinationId, {
    excludePeers = [],
    now = new Date()
  } = {}) {
    this.prune({ now });
    const excluded = new Set(excludePeers);
    const candidates = [...this.routes.values()]
      .filter((route) => route.destinationId === destinationId && !excluded.has(route.viaPeerId))
      .map((route) => ({
        ...route,
        score: 100 - route.hops * 15 + route.quality
      }))
      .sort((a, b) => b.score - a.score || a.hops - b.hops);

    return candidates[0] || null;
  }

  prune({ now = new Date() } = {}) {
    const nowMs = toMs(now);
    for (const [key, route] of this.routes) {
      if (toMs(route.expiresAt) <= nowMs) this.routes.delete(key);
    }
  }

  list({ now = new Date() } = {}) {
    this.prune({ now });
    return [...this.routes.values()];
  }
}

export class RelayStore {
  constructor() {
    this.items = new Map();
  }

  put(frame, { now = new Date() } = {}) {
    if (!frame?.messageId) throw new Error('frame.messageId is required');
    const existing = this.items.get(frame.messageId);
    if (existing) return existing;
    const record = {
      messageId: frame.messageId,
      frame,
      state: 'stored',
      attempts: 0,
      storedAt: iso(toMs(now)),
      updatedAt: iso(toMs(now)),
      lastError: null
    };
    this.items.set(frame.messageId, record);
    return record;
  }

  ready({ now = new Date() } = {}) {
    this.prune({ now });
    return [...this.items.values()].filter((record) => record.state === 'stored' || record.state === 'retry');
  }

  markInflight(messageId, { now = new Date() } = {}) {
    const record = this.#require(messageId);
    record.state = 'inflight';
    record.attempts += 1;
    record.updatedAt = iso(toMs(now));
    return record;
  }

  markRetry(messageId, error, { now = new Date() } = {}) {
    const record = this.#require(messageId);
    record.state = 'retry';
    record.lastError = String(error || 'relay failed');
    record.updatedAt = iso(toMs(now));
    return record;
  }

  remove(messageId) {
    return this.items.delete(messageId);
  }

  prune({ now = new Date() } = {}) {
    const nowMs = toMs(now);
    for (const [messageId, record] of this.items) {
      if (toMs(record.frame.expiresAt) <= nowMs) this.items.delete(messageId);
    }
  }

  stats({ now = new Date() } = {}) {
    this.prune({ now });
    const result = { total: this.items.size, stored: 0, retry: 0, inflight: 0 };
    for (const record of this.items.values()) {
      if (Object.hasOwn(result, record.state)) result[record.state] += 1;
    }
    return result;
  }

  #require(messageId) {
    const record = this.items.get(messageId);
    if (!record) throw new Error(`relay message not found: ${messageId}`);
    return record;
  }
}
