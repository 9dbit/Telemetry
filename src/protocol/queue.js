export class MessageQueue {
  constructor() {
    this.items = new Map();
  }

  enqueue(envelope) {
    if (!envelope?.messageId) throw new Error('envelope.messageId is required');
    if (this.items.has(envelope.messageId)) return this.items.get(envelope.messageId);

    const record = {
      messageId: envelope.messageId,
      envelope,
      state: 'queued',
      attempts: 0,
      queuedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      lastError: null
    };
    this.items.set(envelope.messageId, record);
    return record;
  }

  next() {
    const record = [...this.items.values()].find((item) => item.state === 'queued' || item.state === 'retry');
    if (!record) return null;
    record.state = 'inflight';
    record.attempts += 1;
    record.updatedAt = new Date().toISOString();
    return record;
  }

  markDelivered(messageId) {
    const record = this.#require(messageId);
    record.state = 'delivered';
    record.updatedAt = new Date().toISOString();
    return record;
  }

  markRetry(messageId, error) {
    const record = this.#require(messageId);
    record.state = 'retry';
    record.lastError = String(error || 'delivery failed');
    record.updatedAt = new Date().toISOString();
    return record;
  }

  list({ state } = {}) {
    const items = [...this.items.values()];
    return state ? items.filter((item) => item.state === state) : items;
  }

  stats() {
    const result = { total: this.items.size, queued: 0, inflight: 0, retry: 0, delivered: 0 };
    for (const item of this.items.values()) {
      if (Object.hasOwn(result, item.state)) result[item.state] += 1;
    }
    return result;
  }

  #require(messageId) {
    const record = this.items.get(messageId);
    if (!record) throw new Error(`message not found: ${messageId}`);
    return record;
  }
}
