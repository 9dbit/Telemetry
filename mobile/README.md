# Telemetry Mobile

Telemetry Mobile targets Android and iOS with native shells around one protocol contract.

## Current milestone

**M1A: Offline Peer Discovery** is now scaffolded under `mobile/android`.

The Android shell can request Nearby Devices permissions, advertise Telemetry presence over BLE, scan only for Telemetry peers and surface ephemeral peer labels without exposing a stable device identity in advertisements.

## Android shell

Stack: Kotlin, Jetpack Compose, platform BLE APIs, future Wi-Fi Aware / Wi-Fi Direct adapters, encrypted local persistence and Android lifecycle services.

The current shell targets API 36 and keeps BLE discovery independent of Railway or internet availability.

## iOS shell

Planned stack: Swift, SwiftUI, Core Bluetooth, Wi-Fi Aware / Network.framework adapters, Keychain-backed identity material and iOS background lifecycle integration.

## Shared contract

`mobile/contracts/mobile-contract-v1.json` is the machine-readable boundary both platforms must honor.

Both shells must produce and consume identical test vectors for:

- device identity derivation
- Ed25519 signatures
- X25519 + HKDF session derivation
- AES-256-GCM sealed payload format
- pairing QR payloads
- signed receipts
- envelope canonicalization

Transport discovery is native. Message meaning is shared.
