import http from 'node:http';

const PORT = Number(process.env.PORT || 8080);
const REPOSITORY = process.env.TELEMETRY_GITHUB_REPOSITORY || '9dbit/Telemetry';
const CACHE_TTL_MS = Number(process.env.UPDATE_CACHE_TTL_MS || 60_000);
const GITHUB_API = 'https://api.github.com';
const ALLOWED_CHANNELS = new Set(['preview', 'stable']);
const EXPECTED_PACKAGE = {
  preview: 'com.telemetry.preview',
  stable: 'com.telemetry.app'
};

let cache = new Map();

function json(res, status, body, extraHeaders = {}) {
  const payload = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': payload.length,
    'cache-control': 'no-store',
    ...extraHeaders
  });
  res.end(payload);
}

function expectedTagPrefix(channel) {
  return channel === 'preview' ? 'android-preview-' : 'android-stable-';
}

function expectedManifestAsset(channel) {
  return channel === 'preview'
    ? 'telemetry-android-preview-manifest.json'
    : 'telemetry-android-stable-manifest.json';
}

function validateManifest(manifest, channel, releaseTag) {
  if (!manifest || typeof manifest !== 'object') throw new Error('manifest-not-object');
  if (manifest.schemaVersion !== 1) throw new Error('unsupported-manifest-schema');
  if (manifest.channel !== channel) throw new Error('manifest-channel-mismatch');
  if (manifest.releaseTag !== releaseTag) throw new Error('manifest-tag-mismatch');
  if (!Number.isInteger(manifest.versionCode) || manifest.versionCode <= 0) throw new Error('invalid-version-code');
  if (typeof manifest.versionName !== 'string' || !manifest.versionName) throw new Error('invalid-version-name');
  if (!Number.isInteger(manifest.minimumVersionCode) || manifest.minimumVersionCode < 0) throw new Error('invalid-minimum-version-code');
  if (typeof manifest.mandatory !== 'boolean') throw new Error('invalid-mandatory-flag');
  if (typeof manifest.sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(manifest.sha256)) throw new Error('invalid-apk-sha256');
  if (!Number.isInteger(manifest.sizeBytes) || manifest.sizeBytes <= 0) throw new Error('invalid-apk-size');
  if (manifest.packageName !== EXPECTED_PACKAGE[channel]) throw new Error('package-name-mismatch');
  if (typeof manifest.signingCertificateSha256 !== 'string' || !/^[A-F0-9]{64}$/.test(manifest.signingCertificateSha256)) {
    throw new Error('invalid-signing-certificate-fingerprint');
  }
  const allowedPrefix = `https://github.com/${REPOSITORY}/releases/download/${releaseTag}/`;
  if (typeof manifest.apkUrl !== 'string' || !manifest.apkUrl.startsWith(allowedPrefix)) throw new Error('untrusted-apk-url');
  if (typeof manifest.sourceCommit !== 'string' || !/^[a-f0-9]{40}$/i.test(manifest.sourceCommit)) throw new Error('invalid-source-commit');
  return manifest;
}

async function githubJson(path, init = {}) {
  const response = await fetch(`${GITHUB_API}${path}`, {
    ...init,
    headers: {
      accept: 'application/vnd.github+json',
      'user-agent': 'telemetry-update-controller/0.1',
      'x-github-api-version': '2022-11-28',
      ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
      ...(init.headers || {})
    }
  });
  if (!response.ok) throw new Error(`github-${response.status}`);
  return response.json();
}

async function fetchLatestPromotedManifest(channel) {
  const cached = cache.get(channel);
  const now = Date.now();
  if (cached && now - cached.at < CACHE_TTL_MS) return cached.value;

  const releases = await githubJson(`/repos/${REPOSITORY}/releases?per_page=50`);
  const prefix = expectedTagPrefix(channel);
  const release = releases.find((item) =>
    item &&
    item.draft === false &&
    typeof item.tag_name === 'string' &&
    item.tag_name.startsWith(prefix)
  );

  if (!release) {
    cache.set(channel, { at: now, value: null });
    return null;
  }

  const assetName = expectedManifestAsset(channel);
  const asset = Array.isArray(release.assets)
    ? release.assets.find((item) => item.name === assetName)
    : null;
  if (!asset?.browser_download_url) throw new Error('release-manifest-asset-missing');

  const response = await fetch(asset.browser_download_url, {
    headers: { 'user-agent': 'telemetry-update-controller/0.1' },
    redirect: 'follow'
  });
  if (!response.ok) throw new Error(`manifest-download-${response.status}`);
  const manifest = validateManifest(await response.json(), channel, release.tag_name);
  const value = {
    ...manifest,
    servedAt: new Date(now).toISOString(),
    source: 'github-promoted-release'
  };
  cache.set(channel, { at: now, value });
  return value;
}

export function createServer() {
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
      if (req.method === 'GET' && url.pathname === '/health') {
        return json(res, 200, { ok: true, service: 'telemetry-update-controller' });
      }
      if (req.method === 'GET' && url.pathname === '/api/mobile/android/latest') {
        const channel = url.searchParams.get('channel') || 'preview';
        if (!ALLOWED_CHANNELS.has(channel)) return json(res, 400, { error: 'invalid-channel' });
        const manifest = await fetchLatestPromotedManifest(channel);
        if (!manifest) return json(res, 404, { error: 'no-promoted-release', channel });
        return json(res, 200, manifest);
      }
      return json(res, 404, { error: 'not-found' });
    } catch (error) {
      console.error('[update-controller]', error);
      return json(res, 503, { error: 'update-service-unavailable' });
    }
  });
}

if (process.env.NODE_ENV !== 'test') {
  createServer().listen(PORT, '0.0.0.0', () => {
    console.log(`Telemetry update controller listening on :${PORT}`);
  });
}

export { validateManifest };
