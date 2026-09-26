import test from 'node:test';
import assert from 'node:assert/strict';
import { MobileRelayRuntime } from '../src/protocol/mobile-relay-runtime.js';
import { GatewayRegistry } from '../src/protocol/gateway.js';

test('mobile relay runtime learns direct and multi-hop routes then removes stale peer path', () => {
  const runtime = new MobileRelayRuntime({ localDeviceId: 'A' });
  runtime.observeDirectPeer({ deviceId: 'B', quality: 20 });
  runtime.observeLearnedRoute({ destinationId: 'C', viaPeerId: 'B', hops: 2, quality: 10 });
  assert.equal(runtime.routes.best('C').viaPeerId, 'B');
  assert.equal(runtime.snapshot().routes.length, 2);
  assert.equal(runtime.losePeer('B'), 2);
  assert.equal(runtime.routes.best('C'), null);
});

test('mobile relay runtime projects LoRa and satellite adapters into transport policy', () => {
  const gateways = new GatewayRegistry();
  gateways.register({ id: 'lora-1', kind: 'lora', quality: 5, maxPayloadBytes: 2000, isAvailable: () => true, send: async () => true });
  gateways.register({ id: 'sat-1', kind: 'satellite-gateway', quality: 5, metered: true, maxPayloadBytes: 64000, isAvailable: () => true, send: async () => true });
  const runtime = new MobileRelayRuntime({ localDeviceId: 'A', gatewayRegistry: gateways });
  assert.equal(runtime.selectGateway(500).selected, 'lora');
  assert.equal(runtime.selectGateway(32_000).selected, 'satellite-gateway');
});
