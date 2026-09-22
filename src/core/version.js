export const CORE_VERSION = 'telemetry-core/0.2';

export const CORE_TARGETS = Object.freeze([
  'android',
  'ios',
  'node',
  'gateway'
]);

export const NATIVE_ADAPTERS = Object.freeze({
  android: ['ble', 'wifi-aware', 'wifi-direct', 'internet'],
  ios: ['ble', 'wifi-aware', 'network-framework', 'internet'],
  node: ['internet', 'lan', 'gateway-bridge'],
  gateway: ['lan', 'internet', 'lora', 'satellite-gateway']
});
