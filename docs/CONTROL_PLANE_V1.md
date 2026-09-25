# Telemetry Control Plane v1

## Architecture

Telemetry keeps communication traffic on the peer-to-peer data plane. The cloud control plane receives operational metadata only.

```text
Telemetry iOS / Android
  |\
  | \__ Offline P2P data plane: BLE / Wi-Fi Direct / future LoRa / satellite
  |      E2E encrypted messages, media and calls never transit the dashboard API
  |
  +---- HTTPS metadata ----> Cloudflare perimeter
                               | DNS / TLS / WAF / rate limit / Access
                               v
                            Railway
                               |-- telemetry-web / API
                               |-- telemetry-admin
                               |-- telemetry-update-controller
                               `-- Postgres

Optional public/profile assets: Cloudflare R2
```

## Cloud boundary

Allowed metadata:
- anonymous/pseudonymous install ID and app version
- app download / update events
- active session and heartbeat
- user-selected public profile fields when cloud sync is enabled
- paired contact count; no private local contact aliases
- aggregate message count and bytes by transport; no message text
- media kind, direction, size, duration and success state; no filename or payload
- voice/video call count and duration; no audio/video content
- Telemetry screen time aggregate
- CPU/battery/network resource measurements when enabled
- coarse location only after explicit user consent
- last-seen IP as observed by the perimeter, with short retention
- device/account state: active, suspended, released

Never accepted:
- message text or chat body
- user media/file contents
- filenames by default
- voice/video content
- ciphertext/plaintext dumps
- private identity keys or session keys
- safety codes
- local contact aliases/names

## Dashboard v1

Overview cards:
- total downloads, registered installs, active now, DAU / WAU / MAU
- iOS vs Android and app-version distribution
- total paired contacts and average contacts per active device
- messages and bytes by BLE / Wi-Fi / internet / future transports
- photos, videos and files sent; success / resumed / failed transfer counts
- voice/video call count and aggregate duration
- aggregate screen time
- bandwidth and application resource usage

Users / Devices:
- pseudonymous install/device ID
- public profile name/avatar if user elected to sync it
- platform, app version, first seen, last seen, last IP
- paired contact count
- recent aggregate usage
- active / suspended / released state

Field View:
- map only for users that explicitly enable location sharing to the control plane
- display coarse/stale location by default, never continuous exact tracking by default
- optional live operational mode can be separately consented and time-limited later

## Suspend / Release semantics

`Suspended` blocks cloud account/control-plane services and new cloud-mediated actions. It does not silently revoke a user's already-established offline P2P capability. A stricter managed-device policy would be a separate explicit product mode.

`Released` returns the cloud account/device to normal service.

## Storage proposal

Postgres tables:
- installs
- device_sessions
- device_profiles
- telemetry_events (short retention / partitioned)
- daily_device_rollups
- location_snapshots (opt-in, short retention)
- admin_actions
- app_downloads

Raw event retention should be short. Long-term charts should use daily rollups. IP and precise/coarse location retention should be independently configurable.

## API v1

Device:
- `POST /api/v1/device/register`
- `POST /api/v1/device/heartbeat`
- `POST /api/v1/telemetry/batch`
- `PUT /api/v1/profile`
- `GET /api/v1/device/status`

Admin, protected by Cloudflare Access plus app-level role:
- `GET /api/v1/admin/overview`
- `GET /api/v1/admin/devices`
- `GET /api/v1/admin/devices/:id`
- `GET /api/v1/admin/field`
- `POST /api/v1/admin/devices/:id/suspend`
- `POST /api/v1/admin/devices/:id/release`

## Recommended rollout

1. Railway API + Postgres first.
2. Add Cloudflare DNS/proxy/WAF/rate limiting before public launch.
3. Put admin dashboard behind Cloudflare Access.
4. Add R2 only for cloud-synced profile/public assets or app distribution; P2P media remains P2P by default.
5. Add mobile metadata batching with explicit telemetry/privacy controls.
