export const GATEWAY_KINDS = ['lora', 'satellite-gateway'];

export class GatewayRegistry {
  constructor() { this.adapters = new Map(); }

  register(adapter) {
    if (!adapter?.id || !GATEWAY_KINDS.includes(adapter.kind)) throw new Error('invalid gateway adapter');
    if (typeof adapter.isAvailable !== 'function' || typeof adapter.send !== 'function') throw new Error('gateway adapter contract is incomplete');
    this.adapters.set(adapter.id, adapter);
    return adapter;
  }

  unregister(id) { return this.adapters.delete(id); }

  list() {
    return [...this.adapters.values()].map(adapter => ({
      id: adapter.id,
      kind: adapter.kind,
      available: Boolean(adapter.isAvailable()),
      metered: Boolean(adapter.metered),
      quality: Number.isFinite(adapter.quality) ? adapter.quality : null,
      maxPayloadBytes: Number.isFinite(adapter.maxPayloadBytes) ? adapter.maxPayloadBytes : null
    }));
  }

  best(kind, payloadBytes = 0) {
    return [...this.adapters.values()]
      .filter(adapter => adapter.kind === kind && adapter.isAvailable())
      .filter(adapter => !Number.isFinite(adapter.maxPayloadBytes) || payloadBytes <= adapter.maxPayloadBytes)
      .sort((a, b) => (Number(b.quality || 0) - Number(a.quality || 0)))[0] || null;
  }

  async send(kind, payload, context = {}) {
    const bytes = payload?.byteLength ?? payload?.length ?? 0;
    const adapter = this.best(kind, bytes);
    if (!adapter) throw new Error(`no ${kind} gateway available for ${bytes} bytes`);
    return adapter.send(payload, context);
  }
}

export function createGatewayRouteTransports(registry) {
  if (!(registry instanceof GatewayRegistry)) throw new Error('registry is required');
  return registry.list().map(item => ({
    id: item.kind,
    available: item.available,
    metered: item.metered,
    quality: item.quality,
    gatewayId: item.id,
    maxPayloadBytes: item.maxPayloadBytes
  }));
}

export function assertGatewayPayload(kind, payloadBytes) {
  if (!GATEWAY_KINDS.includes(kind)) throw new Error('unsupported gateway kind');
  if (!Number.isFinite(payloadBytes) || payloadBytes < 0) throw new Error('payloadBytes must be non-negative');
  if (kind === 'lora' && payloadBytes > 2_000) return { allowed: false, reason: 'lora-payload-too-large' };
  if (kind === 'satellite-gateway' && payloadBytes > 64_000) return { allowed: false, reason: 'satellite-payload-too-large' };
  return { allowed: true, reason: 'gateway-payload-allowed' };
}
