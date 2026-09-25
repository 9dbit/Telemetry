import express from 'express';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { CORE_VERSION, CORE_TARGETS, NATIVE_ADAPTERS } from './src/core/version.js';
import { PROTOCOL_VERSION } from './src/protocol/envelope.js';
import { generateDeviceIdentity } from './src/protocol/identity.js';
import { selectTransport } from './src/protocol/router.js';
import { simulateMessage } from './src/protocol/simulator.js';
import { validateTelemetryBatch, validateTelemetryEvent } from './src/control-plane/event-schema.js';
import { PostgresTelemetryStore } from './src/control-plane/postgres-store.js';
import {
  createPairingOffer,
  decodePairingQr,
  encodePairingQr,
  ReplayGuard,
  TrustRegistry,
  verifyPairingOffer
} from './src/protocol/trust.js';

const app = express();
const port = Number(process.env.PORT || 3000);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ANDROID_REPO = '9dbit/Telemetry';
const ANDROID_ASSET = 'Telemetry-Android-Preview.apk';

app.disable('x-powered-by');
app.use(express.json({ limit: '256kb' }));

const telemetryStore = process.env.DATABASE_URL
  ? new PostgresTelemetryStore({ connectionString: process.env.DATABASE_URL })
  : null;

function sourceIp(req) {
  const forwarded = req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for'];
  const value = Array.isArray(forwarded) ? forwarded[0] : String(forwarded || req.ip || '').split(',')[0];
  return value.trim() || null;
}

function secureTokenEqual(actual, expected) {
  if (!actual || !expected) return false;
  const a = Buffer.from(String(actual));
  const b = Buffer.from(String(expected));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function requireIngest(req, res, next) {
  if (!process.env.TELEMETRY_INGEST_TOKEN) return res.status(503).json({ ok: false, error: 'Telemetry ingest is not configured' });
  if (!secureTokenEqual(req.get('x-telemetry-ingest-token'), process.env.TELEMETRY_INGEST_TOKEN)) {
    return res.status(401).json({ ok: false, error: 'Unauthorized' });
  }
  next();
}

function requireAdmin(req, res, next) {
  if (!process.env.ADMIN_API_TOKEN) return res.status(503).json({ ok: false, error: 'Admin API is not configured' });
  const bearer = String(req.get('authorization') || '').replace(/^Bearer\s+/i, '');
  if (!secureTokenEqual(bearer, process.env.ADMIN_API_TOKEN)) return res.status(401).json({ ok: false, error: 'Unauthorized' });
  next();
}

function requireStore(res) {
  if (telemetryStore) return true;
  res.status(503).json({ ok: false, error: 'Control-plane database is not configured' });
  return false;
}

function parseReleaseField(body, name) {
  const match = String(body || '').match(new RegExp(`^${name}:\\s*(.+)$`, 'mi'));
  return match?.[1]?.trim() || '';
}

async function latestAndroidPreviewRelease() {
  const response = await fetch(`https://api.github.com/repos/${ANDROID_REPO}/releases?per_page=20`, {
    headers: {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'Telemetry-Control-Plane'
    }
  });
  if (!response.ok) throw new Error(`GitHub release lookup failed (${response.status})`);
  const releases = await response.json();
  const release = releases.find((item) => /^android-preview-\d+$/.test(item.tag_name));
  if (!release) throw new Error('No Telemetry Android preview release is published yet');

  const versionCode = Number(parseReleaseField(release.body, 'Version-Code') || release.tag_name.split('-').at(-1));
  const versionName = parseReleaseField(release.body, 'Version-Name') || `preview-${versionCode}`;
  const sha256 = parseReleaseField(release.body, 'SHA-256').toLowerCase();
  const asset = release.assets?.find((item) => item.name === ANDROID_ASSET);

  if (!Number.isInteger(versionCode) || versionCode <= 0) throw new Error('Preview release version code is invalid');
  if (!asset?.browser_download_url) throw new Error('Preview release APK is missing');
  if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error('Preview release checksum is missing');

  return {
    channel: 'preview',
    versionCode,
    versionName,
    apkUrl: asset.browser_download_url,
    sha256,
    notes: release.name || `Telemetry Android Preview ${versionName}`,
    publishedAt: release.published_at,
    releaseUrl: release.html_url
  };
}

app.get('/health', (_req, res) => {
  res.status(200).json({
    ok: true,
    service: 'telemetry-control-plane',
    version: '0.2.0',
    core: CORE_VERSION,
    protocol: PROTOCOL_VERSION,
    timestamp: new Date().toISOString()
  });
});

app.get('/api/v1/capabilities', (_req, res) => {
  res.json({
    platform: 'Telemetry',
    core: CORE_VERSION,
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

app.get('/api/v1/core', (_req, res) => {
  res.json({
    version: CORE_VERSION,
    targets: CORE_TARGETS,
    nativeAdapters: NATIVE_ADAPTERS,
    boundary: 'protocol/trust/crypto remain transport-agnostic; radios are native adapters',
    portableCore: {
      rustContractSkeleton: true,
      mobileBindings: 'planned-next'
    }
  });
});

app.get('/api/v1/protocol', (_req, res) => {
  res.json({
    version: PROTOCOL_VERSION,
    core: CORE_VERSION,
    identity: {
      signing: 'Ed25519',
      keyAgreement: 'X25519-HKDF-SHA256'
    },
    payloadEncryption: 'AES-256-GCM',
    pairing: 'signed-expiring-qr-offer',
    replayProtection: 'nonce/message-id replay guard',
    deliveryReceipt: 'recipient-signed receipt',
    envelope: {
      signed: true,
      relayPayloadVisibility: 'opaque',
      hopLimitDefault: 8
    },
    deliveryModel: 'store-and-forward'
  });
});

app.get('/api/v1/android/update', async (req, res) => {
  try {
    const channel = typeof req.query.channel === 'string' ? req.query.channel : 'preview';
    if (channel !== 'preview') {
      return res.status(400).json({ ok: false, error: 'Only the preview channel is available during development' });
    }
    const currentVersionCode = Math.max(0, Number(req.query.versionCode || 0));
    const latest = await latestAndroidPreviewRelease();
    res.set('Cache-Control', 'no-store');
    return res.json({
      ok: true,
      ...latest,
      currentVersionCode,
      updateAvailable: latest.versionCode > currentVersionCode
    });
  } catch (error) {
    return res.status(503).json({ ok: false, error: error.message });
  }
});

app.post('/api/v1/telemetry/batch', requireIngest, async (req, res) => {
  if (!requireStore(res)) return;
  const validated = validateTelemetryBatch(req.body);
  if (!validated.ok) return res.status(400).json(validated);
  try {
    await telemetryStore.recordBatch(validated.events, sourceIp(req));
    return res.status(202).json({ ok: true, accepted: validated.events.length });
  } catch (error) {
    console.error('[telemetry] ingest failed', error);
    return res.status(503).json({ ok: false, error: 'Telemetry storage is temporarily unavailable' });
  }
});

app.put('/api/v1/profile', requireIngest, async (req, res) => {
  if (!requireStore(res)) return;
  const event = {
    type: 'profile.updated',
    installId: req.body?.installId,
    timestamp: new Date().toISOString(),
    platform: req.body?.platform,
    appVersion: req.body?.appVersion,
    data: {
      cloudSync: req.body?.cloudSync === true,
      displayName: typeof req.body?.displayName === 'string' ? req.body.displayName.slice(0, 48) : '',
      about: typeof req.body?.about === 'string' ? req.body.about.slice(0, 120) : '',
      avatarUrl: typeof req.body?.avatarUrl === 'string' ? req.body.avatarUrl.slice(0, 512) : undefined,
      avatarTemplateId: typeof req.body?.avatarTemplateId === 'string' ? req.body.avatarTemplateId.slice(0, 32) : undefined
    }
  };
  const validated = validateTelemetryEvent(event);
  if (!validated.ok) return res.status(400).json(validated);
  try {
    await telemetryStore.recordBatch([validated.event], sourceIp(req));
    return res.json({ ok: true });
  } catch (error) {
    console.error('[telemetry] profile sync failed', error);
    return res.status(503).json({ ok: false, error: 'Profile sync is temporarily unavailable' });
  }
});

app.get('/api/v1/download/android', async (req, res) => {
  try {
    const latest = await latestAndroidPreviewRelease();
    if (telemetryStore) {
      await telemetryStore.recordDownload({
        installId: typeof req.query.installId === 'string' ? req.query.installId.slice(0, 96) : null,
        platform: 'android',
        appVersion: latest.versionName,
        sourceIp: sourceIp(req)
      }).catch(error => console.error('[telemetry] download metric failed', error));
    }
    res.redirect(302, latest.apkUrl);
  } catch (error) {
    res.status(503).json({ ok: false, error: error.message });
  }
});

app.get('/api/v1/admin/overview', requireAdmin, async (_req, res) => {
  if (!requireStore(res)) return;
  try {
    return res.json({ ok: true, overview: await telemetryStore.overview() });
  } catch (error) {
    console.error('[telemetry] admin overview failed', error);
    return res.status(503).json({ ok: false, error: 'Dashboard data is temporarily unavailable' });
  }
});

app.get('/api/v1/admin/devices', requireAdmin, async (req, res) => {
  if (!requireStore(res)) return;
  try {
    const limit = Math.min(200, Math.max(1, Number(req.query.limit || 100)));
    const offset = Math.max(0, Number(req.query.offset || 0));
    return res.json({ ok: true, devices: await telemetryStore.listDevices({ limit, offset }) });
  } catch (error) {
    console.error('[telemetry] admin devices failed', error);
    return res.status(503).json({ ok: false, error: 'Dashboard data is temporarily unavailable' });
  }
});

app.get('/api/v1/admin/field', requireAdmin, async (_req, res) => {
  if (!requireStore(res)) return;
  try {
    return res.json({ ok: true, peers: await telemetryStore.fieldView() });
  } catch (error) {
    console.error('[telemetry] field view failed', error);
    return res.status(503).json({ ok: false, error: 'Field data is temporarily unavailable' });
  }
});

app.post('/api/v1/admin/devices/:installId/:action', requireAdmin, async (req, res) => {
  if (!requireStore(res)) return;
  const state = req.params.action === 'suspend' ? 'suspended' : req.params.action === 'release' ? 'released' : null;
  if (!state) return res.status(404).json({ ok: false, error: 'Unknown admin action' });
  try {
    const device = await telemetryStore.setDeviceStatus(req.params.installId, state, {
      actor: 'admin-api',
      reason: typeof req.body?.reason === 'string' ? req.body.reason.slice(0, 240) : ''
    });
    return res.json({ ok: true, device });
  } catch (error) {
    if (/not found/i.test(error.message)) return res.status(404).json({ ok: false, error: 'Device not found' });
    console.error('[telemetry] device action failed', error);
    return res.status(503).json({ ok: false, error: 'Device action is temporarily unavailable' });
  }
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

app.post('/api/v1/simulate/pairing', (req, res) => {
  try {
    const identity = generateDeviceIdentity();
    const offer = createPairingOffer({
      identity,
      deviceName: typeof req.body?.deviceName === 'string' ? req.body.deviceName : 'Telemetry Device',
      capabilities: Array.isArray(req.body?.capabilities) ? req.body.capabilities : undefined
    });
    const qr = encodePairingQr(offer);
    const decoded = decodePairingQr(qr);
    const verification = verifyPairingOffer(decoded);

    const registry = new TrustRegistry();
    const trustedPeer = registry.trust(decoded);
    const replay = new ReplayGuard();
    const firstSeen = replay.checkAndRemember(decoded.nonce, { expiresAt: decoded.expiresAt });
    const secondSeen = replay.checkAndRemember(decoded.nonce, { expiresAt: decoded.expiresAt });

    res.json({
      ok: true,
      core: CORE_VERSION,
      qr,
      offer: decoded,
      verification,
      trustedPeer,
      replay: { firstSeen, secondSeen }
    });
  } catch (error) {
    res.status(400).json({ ok: false, error: error.message });
  }
});

app.get('/admin', (_req, res) => res.sendFile(path.join(__dirname, 'public', 'admin.html')));
app.use(express.static(path.join(__dirname, 'public')));
app.use((_req, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

async function start() {
  if (telemetryStore) {
    await telemetryStore.init();
    console.log('[telemetry] Postgres control-plane storage ready');
  } else {
    console.warn('[telemetry] DATABASE_URL is not configured; analytics/admin storage is disabled');
  }
  app.listen(port, '0.0.0.0', () => {
    console.log(`Telemetry control plane listening on :${port}`);
  });
}

start().catch(error => {
  console.error('[telemetry] startup failed', error);
  process.exitCode = 1;
});
