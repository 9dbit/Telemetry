const PRIORITY = ['wifi-direct', 'wifi-aware', 'ble', 'internet', 'lora', 'satellite-gateway'];

export function selectTransport({ payloadBytes = 0, transports = [] }) {
  const available = transports.filter((transport) => transport?.available);
  if (available.length === 0) {
    return { selected: null, reason: 'no-transport-available', candidates: [] };
  }

  const candidates = available
    .map((transport) => {
      let score = 100 - Math.max(0, PRIORITY.indexOf(transport.id)) * 10;

      if (transport.id === 'ble' && payloadBytes > 32_000) score -= 45;
      if (transport.id === 'lora' && payloadBytes > 2_000) score -= 55;
      if (transport.id === 'satellite-gateway') score -= 20;
      if (transport.metered) score -= 15;
      if (Number.isFinite(transport.quality)) score += Math.max(-20, Math.min(20, transport.quality));

      return { ...transport, score };
    })
    .sort((a, b) => b.score - a.score);

  return {
    selected: candidates[0].id,
    reason: 'highest-policy-score',
    candidates: candidates.map(({ id, score, metered = false, quality = null }) => ({ id, score, metered, quality }))
  };
}
