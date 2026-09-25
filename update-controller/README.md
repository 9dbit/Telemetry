# Telemetry Android Update Controller

This Railway service is a thin, read-only release discovery layer for Telemetry Android.

It does **not** build or sign APKs. It only exposes Android releases that were explicitly promoted through the GitHub `Android Preview Release` workflow.

## Endpoints

- `GET /health`
- `GET /api/mobile/android/latest?channel=preview`
- `GET /api/mobile/android/latest?channel=stable`

The controller fetches GitHub Releases, selects an explicitly promoted channel tag, downloads its release manifest, validates package/channel/tag/hash/signing-fingerprint metadata, and returns it with `Cache-Control: no-store`.

If no promoted release exists, the update endpoint returns `404 {"error":"no-promoted-release"}`. Telemetry treats this as "up to date / no promoted release" and offline messaging continues normally.

Stable releases are intentionally unsupported by the current Preview promotion workflow until a production signing and distribution policy is configured.
