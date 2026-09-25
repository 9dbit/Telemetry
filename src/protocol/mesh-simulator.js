import { createRelayFrame, forwardRelayFrame, ingestRelayFrame, MeshRouteTable, RelayDedupe } from './mesh.js';
import {
  createRouteAdvertisement,
  ingestRouteAdvertisement,
  RouteAdvertisementGuard,
  routesForAdvertisement
} from './route-advertisement.js';

function linkKey(a, b) {
  return [a, b].sort().join('|');
}

function clampQuality(value) {
  return Number.isFinite(value) ? Math.max(-100, Math.min(100, value)) : 0;
}

export class MeshNetworkSimulator {
  constructor({ now = new Date('2026-09-25T00:00:00.000Z'), routeTtlMs = 15_000 } = {}) {
    this.nowMs = new Date(now).getTime();
    this.routeTtlMs = routeTtlMs;
    this.nodes = new Map();
    this.links = new Map();
  }

  now() {
    return new Date(this.nowMs);
  }

  advance(ms) {
    if (!Number.isFinite(ms) || ms < 0) throw new Error('advance ms must be non-negative');
    this.nowMs += ms;
    return this.now();
  }

  addNode({ identity, capabilities = [] }) {
    if (!identity?.deviceId) throw new Error('identity is required');
    if (this.nodes.has(identity.deviceId)) throw new Error(`duplicate node: ${identity.deviceId}`);
    const node = {
      identity,
      capabilities: [...capabilities],
      routes: new MeshRouteTable(),
      routeGuard: new RouteAdvertisementGuard(),
      relayDedupe: new RelayDedupe(),
      sequence: 0
    };
    this.nodes.set(identity.deviceId, node);
    return node;
  }

  connect(a, b, { quality = 50, transport = 'ble' } = {}) {
    this.#requireNode(a);
    this.#requireNode(b);
    if (a === b) throw new Error('self-link is not allowed');
    const link = { a, b, quality: clampQuality(quality), transport, active: true };
    this.links.set(linkKey(a, b), link);
    return link;
  }

  disconnect(a, b) {
    const link = this.links.get(linkKey(a, b));
    if (!link) return false;
    link.active = false;
    this.#requireNode(a).routes.removeViaPeer(b);
    this.#requireNode(b).routes.removeViaPeer(a);
    return true;
  }

  reconnect(a, b, options = {}) {
    const existing = this.links.get(linkKey(a, b));
    if (existing) {
      existing.active = true;
      if (Number.isFinite(options.quality)) existing.quality = clampQuality(options.quality);
      if (options.transport) existing.transport = options.transport;
      return existing;
    }
    return this.connect(a, b, options);
  }

  advertise(senderId, receiverId) {
    const sender = this.#requireNode(senderId);
    const receiver = this.#requireNode(receiverId);
    const link = this.#activeLink(senderId, receiverId);
    if (!link) return { accepted: false, reason: 'link-unavailable', routesAccepted: 0 };

    sender.sequence += 1;
    const routes = routesForAdvertisement(sender.routes, {
      localDeviceId: senderId,
      toPeerId: receiverId,
      now: this.now()
    });
    const advertisement = createRouteAdvertisement({
      identity: sender.identity,
      sequence: sender.sequence,
      routes,
      capabilities: sender.capabilities,
      ttlMs: this.routeTtlMs,
      now: this.now()
    });

    return ingestRouteAdvertisement(receiver.routes, advertisement, {
      fromPeerId: senderId,
      signingPublicKey: sender.identity.signing.publicKey,
      localDeviceId: receiverId,
      guard: receiver.routeGuard,
      linkQuality: link.quality,
      linkTransport: link.transport,
      now: this.now()
    });
  }

  converge({ rounds = 4 } = {}) {
    if (!Number.isInteger(rounds) || rounds < 1) throw new Error('rounds must be a positive integer');
    const results = [];
    for (let round = 0; round < rounds; round += 1) {
      for (const link of this.links.values()) {
        if (!link.active) continue;
        results.push(this.advertise(link.a, link.b));
        results.push(this.advertise(link.b, link.a));
      }
    }
    return results;
  }

  bestRoute(fromId, destinationId) {
    return this.#requireNode(fromId).routes.best(destinationId, { now: this.now() });
  }

  routePath(fromId, destinationId, { maxHops = 32 } = {}) {
    this.#requireNode(fromId);
    this.#requireNode(destinationId);
    const path = [fromId];
    let current = fromId;

    while (current !== destinationId && path.length <= maxHops) {
      const node = this.#requireNode(current);
      const route = node.routes.best(destinationId, {
        excludePeers: path,
        now: this.now()
      });
      if (!route) return null;
      if (!this.#activeLink(current, route.viaPeerId)) return null;
      current = route.viaPeerId;
      if (path.includes(current)) return null;
      path.push(current);
    }

    return current === destinationId ? path : null;
  }

  sendEnvelope(envelope, { fromId = envelope?.senderId } = {}) {
    if (!envelope?.messageId) throw new Error('envelope is required');
    const path = this.routePath(fromId, envelope.recipientId, { maxHops: envelope.hopLimit + 1 });
    if (!path) return { delivered: false, reason: 'no-route', path: [] };

    let frame = createRelayFrame(envelope, { now: this.now() });
    for (let index = 1; index < path.length; index += 1) {
      const nodeId = path[index];
      const node = this.#requireNode(nodeId);
      const ingress = ingestRelayFrame(frame, {
        localDeviceId: nodeId,
        dedupe: node.relayDedupe,
        now: this.now()
      });
      if (!ingress.accepted) {
        return { delivered: false, reason: ingress.reason, path: path.slice(0, index + 1), frame };
      }
      if (ingress.action === 'deliver-local') {
        return { delivered: true, reason: 'delivered-local', path, frame };
      }
      frame = forwardRelayFrame(frame, { relayDeviceId: nodeId, now: this.now() });
    }

    return { delivered: false, reason: 'path-ended-before-recipient', path, frame };
  }

  snapshot() {
    return [...this.nodes.entries()].map(([deviceId, node]) => ({
      deviceId,
      sequence: node.sequence,
      routes: node.routes.list({ now: this.now() })
    }));
  }

  #activeLink(a, b) {
    const link = this.links.get(linkKey(a, b));
    return link?.active ? link : null;
  }

  #requireNode(deviceId) {
    const node = this.nodes.get(deviceId);
    if (!node) throw new Error(`unknown node: ${deviceId}`);
    return node;
  }
}
