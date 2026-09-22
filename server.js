import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PROTOCOL_VERSION } from './src/protocol/envelope.js';
import { selectTransport } from './src/protocol/router.js';
import { simulateMessage } from './src/protocol/simulator.js';

const app = express();
const port = Number(process.env.PORT || 3000);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

app.disable('x-powered-by');
app.use(express.json({ limit: '256kb' }));

app.get('/health', (_req, res) => {
  res.status(200).json({
    ok: true,
    service: 'telemetry-control-plane',
    version: '0.1.0',
    protocol: PROTOCOL_VERSION,
    timestamp: new Date().toISOString()
  });
});

app.get('/api/v1/capabilities', (_req, res) => {
  res.json({
    platform: 'Telemetry',
    protocol: PROTOCOL_VERSION,
    transports: [
      { id: 'internet', state: 'foundation' },
      { id: 'ble', state: 'protocol-ready' },
      { id: 'wifi-direct', state: 'protocol-ready' },
      { id: 'wifi-aware', state: 'protocol-ready' },
      { id: 'lora', state: 'planned' },
      { id: 'satellite-gateway', state: 'planned' }
    ],
    principles: ['end-to-end-encryption', 'store-and-forward', 'multi-transport-routing', 'offline-first']
  });
});

app.get('/api/v1/protocol', (_req, res) => {
  res.json({
    version: PROTOCOL_VERSION,
    identity: {
      signing: 'Ed25519',
      keyAgreement: 'X25519-HKDF-SHA256'
    },
    payloadEncryption: 'AES-256-GCM',
    envelope: {
      signed: true,
      relayPayloadVisibility: 'opaque',
      hopLimitDefault: 8
    },
    deliveryModel: 'store-and-forward'
  });
});

app.post('/api/v1/route', (req, res) => {
  const payloadBytes = Number(req.body?.payloadBytes || 0);
  const transports = Array.isArray(req.body?.transports) ? req.body.transports : [];
  res.json(selectTransport({ payloadBytes, transports }));
});

app.post('/api/v1/simulate/message', (req, res) => {
  try {
    const text = typeof req.body?.text === 'string' ? req.body.text.slice(0, 4000) : 'hello telemetry';
    const transports = Array.isArray(req.body?.transports) ? req.body.transports : undefined;
    res.json(simulateMessage({ text, transports }));
  } catch (error) {
    res.status(400).json({ ok: false, error: error.message });
  }
});

app.use(express.static(path.join(__dirname, 'public')));
app.use((_req, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

app.listen(port, '0.0.0.0', () => {
  console.log(`Telemetry control plane listening on :${port}`);
});
