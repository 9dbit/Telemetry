import { envelopeRelayView } from './envelope.js';

export const MESH_DIAGNOSTIC_EVENTS = Object.freeze([
  'route-selected',
  'relay-stored',
  'relay-forwarded',
  'duplicate-dropped',
  'delivered-local',
  'receipt-verified',
  'retry-scheduled'
]);

function safeRoute(route) {
  if (!route) return null;
  return {
    destinationId: route.destinationId ?? null,
    viaPeerId: route.viaPeerId ?? null,
    hops: Number.isInteger(route.hops) ? route.hops : null,
    quality: Number.isFinite(route.quality) ? route.quality : null,
    transport: route.transport ?? null
  };
}

export function relayDiagnosticView(frame) {
  if (!frame?.envelope?.messageId) throw new Error('relay frame is required');
  const envelope = envelopeRelayView(frame.envelope);
  return {
    messageId: envelope.messageId,
    senderId: envelope.senderId,
    recipientId: envelope.recipientId,
    createdAt: envelope.createdAt,
    hopCount: envelope.hopCount,
    hopLimit: envelope.hopLimit,
    contentType: envelope.contentType,
    payloadBytes: envelope.payloadBytes,
    relayPath: [...(frame.relayPath || [])],
    expiresAt: frame.expiresAt ?? null
  };
}

export class MeshDiagnostics {
  constructor({ maxEvents = 200 } = {}) {
    if (!Number.isInteger(maxEvents) || maxEvents < 1 || maxEvents > 10_000) {
      throw new Error('maxEvents must be between 1 and 10000');
    }
    this.maxEvents = maxEvents;
    this.events = [];
  }

  record(type, { frame, route = null, transportId = null, reason = null, at = new Date() } = {}) {
    if (!MESH_DIAGNOSTIC_EVENTS.includes(type)) throw new Error(`unsupported mesh diagnostic event: ${type}`);
    const event = {
      type,
      at: new Date(at).toISOString(),
      relay: relayDiagnosticView(frame),
      route: safeRoute(route),
      transportId: transportId ?? null,
      reason: reason ? String(reason).slice(0, 160) : null
    };
    this.events.push(event);
    if (this.events.length > this.maxEvents) this.events.splice(0, this.events.length - this.maxEvents);
    return event;
  }

  snapshot() {
    return this.events.map((event) => structuredClone(event));
  }

  clear() {
    this.events.length = 0;
  }
}
