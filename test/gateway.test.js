import test from 'node:test';
import assert from 'node:assert/strict';
import { GatewayRegistry, assertGatewayPayload, createGatewayRouteTransports } from '../src/protocol/gateway.js';

test('gateway registry selects available LoRa adapter within payload limit', async () => {
  const registry = new GatewayRegistry();
  registry.register({ id: 'lora-a', kind: 'lora', maxPayloadBytes: 2000, quality: 12, isAvailable: () => true, send: async payload => ({ ok: true, bytes: payload.length }) });
  registry.register({ id: 'lora-b', kind: 'lora', maxPayloadBytes: 500, quality: 30, isAvailable: () => true, send: async () => ({ ok: true }) });
  const result = await registry.send('lora', Buffer.alloc(900));
  assert.deepEqual(result, { ok: true, bytes: 900 });
  assert.equal(registry.best('lora', 900)?.id, 'lora-a');
});

test('gateway transport projection exposes router-compatible capabilities', () => {
  const registry = new GatewayRegistry();
  registry.register({ id: 'sat-1', kind: 'satellite-gateway', metered: true, quality: -5, maxPayloadBytes: 64000, isAvailable: () => true, send: async () => true });
  assert.deepEqual(createGatewayRouteTransports(registry)[0], { id: 'satellite-gateway', available: true, metered: true, quality: -5, gatewayId: 'sat-1', maxPayloadBytes: 64000 });
});

test('gateway payload policy keeps LoRa narrow and satellite bounded', () => {
  assert.equal(assertGatewayPayload('lora', 160).allowed, true);
  assert.equal(assertGatewayPayload('lora', 3000).allowed, false);
  assert.equal(assertGatewayPayload('satellite-gateway', 32000).allowed, true);
  assert.equal(assertGatewayPayload('satellite-gateway', 128000).allowed, false);
});
