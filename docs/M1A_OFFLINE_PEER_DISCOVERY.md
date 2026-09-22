# Telemetry M1A: Offline Peer Discovery

M1A is the first on-device milestone. Its purpose is narrow: two Android phones running Telemetry should be able to discover that another Telemetry device is nearby without SIM, internet, Railway, or a central server.

## Scope

Implemented in this slice:

- Android app shell using Kotlin + Jetpack Compose
- runtime Nearby Devices permission flow
- BLE service advertising
- filtered BLE scanning for Telemetry peers
- in-memory ephemeral peer labels
- no stable Telemetry device ID in BLE advertisements
- machine-readable shared mobile contract
- Android CI build workflow

Not implemented yet:

- GATT connection
- identity exchange over radio
- QR trust pairing UI
- X25519 secure peer session on-device
- encrypted message transfer
- Wi-Fi Aware / Wi-Fi Direct handoff
- background relay service

Those belong to M1B and M1C.

## Privacy boundary

BLE advertising is presence-only. It advertises the Telemetry service UUID and does not publish the stable device ID, public signing key, contact name, phone number, or precise location. The local scanner assigns a short in-memory label such as `peer-001`; the label is not persisted.

Stable identity is revealed only after the future trust/session layer authorizes the exchange.

## Android baseline

- minSdk 26
- compileSdk 37
- targetSdk 36
- AGP 9.4.0
- Gradle 9.6.0 in CI
- Compose BOM 2026.09.00

`compileSdk` is intentionally newer than `targetSdk`: the current Compose artifacts require API 37 at compile time, while Telemetry keeps target API 36 for the current runtime compatibility baseline.

## M1A acceptance test

1. Install a debug build on Android phone A and B.
2. Disable mobile data and disconnect Wi-Fi from internet. Bluetooth remains enabled.
3. Open Telemetry on both phones.
4. Grant Nearby Devices permission.
5. Tap **Start discovery** on both phones.
6. Each compatible phone should eventually show at least one nearby Telemetry peer.
7. No stable identity should appear in the BLE advertisement or UI.

## Next milestone

M1B adds an authenticated peer session: QR trust pairing, GATT bootstrap, signed capability exchange, X25519 session derivation, replay protection, and the first encrypted text message.
