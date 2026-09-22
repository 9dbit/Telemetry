# Telemetry Mobile

Telemetry Mobile targets Android and iOS with native shells around one protocol contract.

## Android shell

Planned stack: Kotlin, Jetpack Compose, platform BLE APIs, Wi-Fi Aware / Wi-Fi Direct adapters, encrypted local persistence and Android lifecycle services.

## iOS shell

Planned stack: Swift, SwiftUI, Core Bluetooth, Wi-Fi Aware / Network.framework adapters, Keychain-backed identity material and iOS background lifecycle integration.

## Shared contract

Both shells must produce and consume identical test vectors for:

- device identity derivation
- Ed25519 signatures
- X25519 + HKDF session derivation
- AES-256-GCM sealed payload format
- pairing QR payloads
- signed receipts
- envelope canonicalization

Transport discovery is native. Message meaning is shared.
