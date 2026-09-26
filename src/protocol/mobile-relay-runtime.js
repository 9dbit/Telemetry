import { GatewayRegistry, createGatewayRouteTransports } from './gateway.js';
import { MeshRouteTable } from './mesh.js';
import { MeshDeliveryOrchestrator } from './orchestrator.js';
import { selectTransport } from './router.js';

export class MobileRelayRuntime {
  constructor({ localDeviceId, gatewayRegistry = new GatewayRegistry() }) {
    if (!localDeviceId) throw new Error('localDeviceId is required');
    this.localDeviceId = localDeviceId;
    this.routes = new MeshRouteTable();
    this.gateways = gatewayRegistry;
    this.orchestrator = new MeshDeliveryOrchestrator({
      localDeviceId,
      routeResolver: (destinationId, options) => this.routes.best(destinationId, options)
    });
  }

  observeDirectPeer({ deviceId, peerId = deviceId, quality = 0, transport = 'ble', ttlMs = 30_000, now = new Date() }) {
    if (!deviceId || deviceId === this.localDeviceId) return null;
    return this.routes.observe({ destinationId: deviceId, viaPeerId: peerId, hops: 1, quality, transport, ttlMs, now });
  }

  observeLearnedRoute({ destinationId, viaPeerId, hops, quality = 0, transport = 'ble', ttlMs = 30_000, now = new Date() }) {
    if (destinationId === this.localDeviceId) return null;
    return this.routes.observe({ destinationId, viaPeerId, hops, quality, transport, ttlMs, now });
  }

  losePeer(peerId) { return this.routes.removeViaPeer(peerId); }

  enqueue(envelope) { return this.orchestrator.enqueue(envelope); }
  ingest(frame, options) { return this.orchestrator.ingest(frame, options); }
  acceptFinalReceipt(receipt, publicKey) { return this.orchestrator.acceptFinalReceipt(receipt, publicKey); }

  async attemptOrigin({ sendToPeer, now = new Date() }) {
    return this.orchestrator.attemptOrigin({ sendToPeer, now });
  }

  async flushRelays({ sendToPeer, now = new Date() }) {
    return this.orchestrator.flushRelays({ sendToPeer, now });
  }

  selectGateway(payloadBytes, directTransports = []) {
    return selectTransport({ payloadBytes, transports: [...directTransports, ...createGatewayRouteTransports(this.gateways)] });
  }

  snapshot({ now = new Date() } = {}) {
    return {
      routes: this.routes.list({ now }),
      delivery: this.orchestrator.stats({ now }),
      gateways: this.gateways.list()
    };
  }
}
