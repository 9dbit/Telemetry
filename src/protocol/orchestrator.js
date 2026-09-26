import { MessageQueue } from './queue.js';
import { createRelayFrame, forwardRelayFrame, ingestRelayFrame, RelayDedupe, RelayStore } from './mesh.js';
import { verifyDeliveryReceipt } from './receipt.js';

function defaultRouteResolver() {
  return null;
}

export class MeshDeliveryOrchestrator {
  constructor({
    localDeviceId,
    routeResolver = defaultRouteResolver,
    queue = new MessageQueue(),
    relayStore = new RelayStore(),
    dedupe = new RelayDedupe()
  }) {
    if (!localDeviceId) throw new Error('localDeviceId is required');
    if (typeof routeResolver !== 'function') throw new Error('routeResolver must be a function');
    this.localDeviceId = localDeviceId;
    this.routeResolver = routeResolver;
    this.queue = queue;
    this.relayStore = relayStore;
    this.dedupe = dedupe;
  }

  enqueue(envelope) {
    if (!envelope?.messageId) throw new Error('envelope is required');
    if (envelope.senderId !== this.localDeviceId) {
      throw new Error('origin envelope sender must match localDeviceId');
    }
    return this.queue.enqueue(envelope);
  }

  async attemptOrigin({ now = new Date(), sendToPeer }) {
    if (typeof sendToPeer !== 'function') throw new Error('sendToPeer is required');
    const record = this.queue.next();
    if (!record) return { action: 'idle' };

    const route = this.routeResolver(record.envelope.recipientId, {
      excludePeers: [this.localDeviceId],
      now
    });
    if (!route?.viaPeerId) {
      this.queue.markRetry(record.messageId, 'no route available');
      return { action: 'wait-route', messageId: record.messageId };
    }

    const frame = createRelayFrame(record.envelope, { now });
    try {
      const sent = await sendToPeer(route.viaPeerId, frame, { route, kind: 'origin' });
      if (!sent) throw new Error('transport rejected frame');
      return {
        action: 'await-receipt',
        messageId: record.messageId,
        viaPeerId: route.viaPeerId,
        route
      };
    } catch (error) {
      this.queue.markRetry(record.messageId, error);
      return {
        action: 'retry',
        messageId: record.messageId,
        viaPeerId: route.viaPeerId,
        error: String(error?.message || error)
      };
    }
  }

  ingest(frame, { now = new Date() } = {}) {
    const result = ingestRelayFrame(frame, {
      localDeviceId: this.localDeviceId,
      dedupe: this.dedupe,
      now
    });
    if (!result.accepted) return result;
    if (result.action === 'deliver-local') {
      return { ...result, frame };
    }

    this.relayStore.put(frame, { now });
    return { ...result, action: 'stored-relay', frame };
  }

  async flushRelays({ now = new Date(), sendToPeer }) {
    if (typeof sendToPeer !== 'function') throw new Error('sendToPeer is required');
    const results = [];

    for (const record of this.relayStore.ready({ now })) {
      const frame = record.frame;
      const excludePeers = [...new Set([this.localDeviceId, ...(frame.relayPath || [])])];
      const route = this.routeResolver(frame.envelope.recipientId, { excludePeers, now });
      if (!route?.viaPeerId) {
        results.push({ action: 'wait-route', messageId: record.messageId });
        continue;
      }

      this.relayStore.markInflight(record.messageId, { now });
      let forwarded;
      try {
        forwarded = forwardRelayFrame(frame, { relayDeviceId: this.localDeviceId, now });
        const sent = await sendToPeer(route.viaPeerId, forwarded, { route, kind: 'relay' });
        if (!sent) throw new Error('transport rejected frame');
        this.relayStore.remove(record.messageId);
        results.push({
          action: 'forwarded',
          messageId: record.messageId,
          viaPeerId: route.viaPeerId,
          route,
          frame: forwarded
        });
      } catch (error) {
        this.relayStore.markRetry(record.messageId, error, { now });
        results.push({
          action: 'retry',
          messageId: record.messageId,
          viaPeerId: route.viaPeerId,
          error: String(error?.message || error)
        });
      }
    }

    return results;
  }

  acceptFinalReceipt(receipt, recipientSigningPublicKey) {
    if (!receipt?.messageId) return { accepted: false, reason: 'invalid-receipt' };
    if (!verifyDeliveryReceipt(receipt, recipientSigningPublicKey)) {
      return { accepted: false, reason: 'invalid-signature' };
    }

    const record = this.queue.list().find((item) => item.messageId === receipt.messageId);
    if (!record) return { accepted: false, reason: 'unknown-message' };
    if (record.envelope.recipientId !== receipt.recipientId) {
      return { accepted: false, reason: 'recipient-mismatch' };
    }
    if (record.envelope.senderId !== receipt.originalSenderId) {
      return { accepted: false, reason: 'sender-mismatch' };
    }

    this.queue.markDelivered(receipt.messageId);
    return { accepted: true, reason: 'verified-final-receipt', receipt };
  }

  retryOrigin(messageId, reason = 'delivery timeout') {
    return this.queue.markRetry(messageId, reason);
  }

  stats({ now = new Date() } = {}) {
    return {
      origin: this.queue.stats(),
      relay: this.relayStore.stats({ now })
    };
  }
}
