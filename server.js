import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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
    timestamp: new Date().toISOString()
  });
});

app.get('/api/v1/capabilities', (_req, res) => {
  res.json({
    platform: 'Telemetry',
    transports: [
      { id: 'internet', state: 'foundation' },
      { id: 'ble', state: 'planned' },
      { id: 'wifi-direct', state: 'planned' },
      { id: 'lora', state: 'planned' },
      { id: 'satellite-gateway', state: 'planned' }
    ],
    principles: ['end-to-end-encryption', 'store-and-forward', 'multi-transport-routing', 'offline-first']
  });
});

app.use(express.static(path.join(__dirname, 'public')));
app.use((_req, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

app.listen(port, '0.0.0.0', () => {
  console.log(`Telemetry control plane listening on :${port}`);
});
