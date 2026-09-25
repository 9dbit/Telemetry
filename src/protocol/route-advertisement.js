import {
  deviceIdFromSigningPublicKey,
  signBytes,
  verifyBytes
} from './identity.js';
import { MeshRouteTable } from './mesh.js';

export const ROUTE_ADVERTISEMENT_PROTOCOL = 'telemetry/mesh-route/0.1';
export const DEFAULT_ROUTE_AD_TTL_MS = 15_000;
export const MAX_ADVERTISED_ROUTES = 32;
export const MAX_ROUTE_AD_TTL_MS = 30_000;

function toMs(value) {
  const ms = value instanceof Date ? value.getTime() : new Date(value).getTime();
  if (!Number.isFinite(ms)) throw new Error('invalid time');
  return ms;
}

function clampQuality(value) {
  return Number.isFinite(value) ? Math.max(-100, Math.min(100, value)) : 0;
}

function canonicalAdvertisement(advertisement) {
  return JSON.stringify({
    protocol: advertisement.protocol,
    advertiserId: advertisement.advertiserId,
    sequence: advertisement.sequence,
    createdAt: advertisement.createdAt,
    expiresAt: advertisement.expiresAt,
    capabilities: advertisement.capabilities,
    routes: advertisement.routes
  });
}

function normalizeCapabilities(capabilities = []) {
  return [...new Set(capabilities)]
    .filter((value) => typeof value === 'string' && value.length > 0 && value.length <= 64)
    .sort()
    .slice(0, 16);
}

function normalizeAdvertisedRoute(route) {
  if (!route?.destinationId) throw new Error('route.destinationId is required');
  if (!Number.isInteger(route.hops) || route.hops < 0 || route.hops > 31) {
    throw new Error('route.hops must be between 0 and 31');
  }
  return {
    destinationId: route.destinationId,
    hops: route.hops,
    quality: clampQuality(route.quality),
    transport: typeof route.transport === 'string' && route.transport ? route.transport : 'ble'
  };
}

export class RouteAdvertisementGuard {
  constructor() {
    this.lastSequence = new Map();
  }

  accept(advertiserId, sequence) {
    if (!advertiserId) throw new Error('advertiserId is required');
    if (!Number.isSafeInteger(sequence) || sequence < 0) {
      return { accepted: false, reason: 'invalid-sequence' };
    }
    const previous = this.lastSequence.get(advertiserId);
    if (previous !== undefined && sequence <= previous) {
      return { accepted: false, reason: 'stale-sequence', previous };
    }
    this.lastSequence.set(advertiserId, sequence);
    return { accepted: true, reason: 'newer-sequence', previous: previous ?? null };
  }
}

export function routesForAdvertisement(routeTable, {
  localDeviceId,
  toPeerId = null,
  now = new Date(),
  maxRoutes = MAX_ADVERTISED_ROUTES
} = {}) {
  if (!(routeTable instanceof MeshRouteTable)) throw new Error('routeTable is required');
  if (!localDeviceId) throw new Error('localDeviceId is required');

  const learned = routeTable.list({ now })
    .filter((route) => route.destinationId !== localDeviceId)
    .filter((route) => !toPeerId || route.viaPeerId !== toPeerId)
    .map((route) => ({
      destinationId: route.destinationId,
      hops: route.hops,
      quality: route.quality,
      transport: route.transport,
      score: 100 - route.hops * 15 + route.quality
    }))
    .sort((a, b) => b.score - a.score || a.hops - b.hops)
    .slice(0, Math.max(0, maxRoutes - 1))
    .map(({ score, ...route }) => route);

  return [
    { destinationId: localDeviceId, hops: 0, quality: 100, transport: 'self' },
    ...learned
  ].slice(0, maxRoutes);
}

export function createRouteAdvertisement({
  identity,
  sequence,
  routes,
  capabilities = [],
  ttlMs = DEFAULT_ROUTE_AD_TTL_MS,
  now = new Date()
}) {
  if (!identity?.deviceId || !identity?.signing?.privateKey) throw new Error('identity is required');
  if (!Number.isSafeInteger(sequence) || sequence < 0) throw new Error('sequence must be a non-negative safe integer');
  if (!Array.isArray(routes)) throw new Error('routes must be an array');
  if (routes.length > MAX_ADVERTISED_ROUTES) throw new Error(`routes exceed max ${MAX_ADVERTISED_ROUTES}`);
  const boundedTtlMs = Math.max(1_000, Math.min(MAX_ROUTE_AD_TTL_MS, ttlMs));
  const nowMs = toMs(now);
  const advertisement = {
    protocol: ROUTE_ADVERTISEMENT_PROTOCOL,
    advertiserId: identity.deviceId,
    sequence,
    createdAt: new Date(nowMs).toISOString(),
    expiresAt: new Date(nowMs + boundedTtlMs).toISOString(),
    capabilities: normalizeCapabilities(capabilities),
    routes: routes.map(normalizeAdvertisedRoute)
  };
  return {
    ...advertisement,
    signature: signBytes(identity, canonicalAdvertisement(advertisement))
  };
}

export function verifyRouteAdvertisement(advertisement, signingPublicKey, {
  now = new Date(),
  maxRoutes = MAX_ADVERTISED_ROUTES,
  clockSkewMs = 5_000
} = {}) {
  if (!advertisement?.signature) return { ok: false, reason: 'missing-signature' };
  if (advertisement.protocol !== ROUTE_ADVERTISEMENT_PROTOCOL) return { ok: false, reason: 'unsupported-protocol' };
  if (!signingPublicKey) return { ok: false, reason: 'missing-signing-key' };
  if (deviceIdFromSigningPublicKey(signingPublicKey) !== advertisement.advertiserId) {
    return { ok: false, reason: 'identity-mismatch' };
  }
  if (!Number.isSafeInteger(advertisement.sequence) || advertisement.sequence < 0) {
    return { ok: false, reason: 'invalid-sequence' };
  }
  if (!Array.isArray(advertisement.routes) || advertisement.routes.length > maxRoutes) {
    return { ok: false, reason: 'route-count' };
  }

  try {
    advertisement.routes.forEach(normalizeAdvertisedRoute);
    const nowMs = toMs(now);
    const createdMs = toMs(advertisement.createdAt);
    const expiresMs = toMs(advertisement.expiresAt);
    if (createdMs > nowMs + clockSkewMs) return { ok: false, reason: 'created-in-future' };
    if (expiresMs <= nowMs) return { ok: false, reason: 'expired' };
    if (expiresMs - createdMs > MAX_ROUTE_AD_TTL_MS) return { ok: false, reason: 'ttl-too-large' };
    if (!verifyBytes(signingPublicKey, canonicalAdvertisement(advertisement), advertisement.signature)) {
      return { ok: false, reason: 'bad-signature' };
    }
    return { ok: true, reason: 'verified' };
  } catch {
    return { ok: false, reason: 'invalid-advertisement' };
  }
}

export function ingestRouteAdvertisement(routeTable, advertisement, {
  fromPeerId,
  signingPublicKey,
  localDeviceId,
  guard = null,
  linkQuality = null,
  linkTransport = 'ble',
  now = new Date()
}) {
  if (!(routeTable instanceof MeshRouteTable)) throw new Error('routeTable is required');
  if (!fromPeerId) throw new Error('fromPeerId is required');
  const verification = verifyRouteAdvertisement(advertisement, signingPublicKey, { now });
  if (!verification.ok) return { accepted: false, reason: verification.reason, routesAccepted: 0 };
  if (advertisement.advertiserId !== fromPeerId) {
    return { accepted: false, reason: 'peer-mismatch', routesAccepted: 0 };
  }

  if (guard) {
    if (!(guard instanceof RouteAdvertisementGuard)) throw new Error('guard must be RouteAdvertisementGuard');
    const sequence = guard.accept(advertisement.advertiserId, advertisement.sequence);
    if (!sequence.accepted) return { accepted: false, reason: sequence.reason, routesAccepted: 0 };
  }

  const nowMs = toMs(now);
  const remainingTtlMs = Math.max(1, Math.min(MAX_ROUTE_AD_TTL_MS, toMs(advertisement.expiresAt) - nowMs));
  let routesAccepted = 0;

  for (const advertisedRoute of advertisement.routes) {
    if (localDeviceId && advertisedRoute.destinationId === localDeviceId) continue;
    const hops = advertisedRoute.hops + 1;
    if (hops > 32) continue;
    const quality = Number.isFinite(linkQuality)
      ? Math.min(clampQuality(advertisedRoute.quality), clampQuality(linkQuality))
      : clampQuality(advertisedRoute.quality);
    routeTable.observe({
      destinationId: advertisedRoute.destinationId,
      viaPeerId: fromPeerId,
      hops,
      quality,
      transport: advertisedRoute.transport === 'self' ? linkTransport : advertisedRoute.transport,
      ttlMs: remainingTtlMs,
      now
    });
    routesAccepted += 1;
  }

  return {
    accepted: true,
    reason: 'routes-updated',
    routesAccepted,
    capabilities: advertisement.capabilities
  };
}
