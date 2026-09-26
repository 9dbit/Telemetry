import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { GatewayRegistry } from '../src/protocol/gateway.js';

const contract = JSON.parse(await readFile(new URL('../mobile/contracts/gateway-v1.json', import.meta.url), 'utf8'));

test('mobile gateway contract keeps LoRa and satellite opaque and bounded', async () => {
  assert.equal(contract.version, 'telemetry-gateway/1');
  assert.equal(contract.principles.payloadVisibility, 'opaque');
  assert.equal(contract.principles.endToEndEncryption, true);
  assert.equal(contract.gatewayKinds.lora.maxPayloadBytes, 2000);
  assert.equal(contract.gatewayKinds['satellite-gateway'].maxPayloadBytes, 64000);
  const registry = new GatewayRegistry();
  const adapter = { id: 'sim-lora', kind: 'lora', maxPayloadBytes: 2000, isAvailable: () => true, send: async bytes => bytes.length };
  registry.register(adapter);
  assert.equal(await registry.send('lora', Buffer.alloc(160)), 160);
});
