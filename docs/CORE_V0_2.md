# Telemetry Core v0.2

Telemetry Core v0.2 defines the platform boundary required to ship the same communication protocol across Android, iOS, gateways and cloud-connected nodes.

## Rule: one protocol, native transports

The cryptographic identity, pairing contract, message envelope, replay rules, receipts, queue lifecycle and routing policy are platform-independent. BLE, Wi-Fi Aware, Wi-Fi Direct, Network.framework, LoRa and satellite integrations are transport adapters.

```text
Android Kotlin adapter ----\
iOS Swift adapter ----------> Telemetry Core contract -> encrypted envelope / trust / queue / routing
Node/Gateway adapter -------/
```

The current JavaScript implementation remains the executable reference implementation used by Railway and tests. `core/rust` is introduced as the portable contract skeleton that will become the shared mobile/gateway library after FFI bindings and audited cryptographic dependencies are selected.

## Trust and pairing

v0.2 adds a signed, short-lived pairing offer intended to be carried by QR code or another authenticated out-of-band channel.

A pairing offer contains only public identity material:

- deterministic device ID derived from the Ed25519 signing public key
- Ed25519 signing public key
- X25519 exchange public key
- display name
- advertised transport capabilities
- random nonce
- issue and expiry time
- sender signature

Private keys never enter the QR payload or control plane.

A receiver must verify the device-ID binding, validity window and signature before inserting the peer into its local trust registry.

## Replay protection

Pairing nonces and message IDs are remembered until their expiry. A repeated identifier is rejected while still inside the replay window. Persistent mobile implementations must back this guard with local encrypted storage instead of process memory.

## Delivery receipts

The message recipient can create a signed receipt with states `received`, `delivered`, `read` or `rejected`. A receipt is endpoint-generated and must be authenticated. For privacy-sensitive transport, the receipt object can itself be sealed inside an encrypted Telemetry envelope.

## Platform adapters

### Android

Initial adapter surface:

- BLE discovery and bootstrap
- Wi-Fi Aware when available
- Wi-Fi Direct fallback where appropriate
- local encrypted persistence
- foreground/background lifecycle integration

### iOS

Initial adapter surface:

- Core Bluetooth discovery/bootstrap
- Wi-Fi Aware on supported OS/hardware and entitlement
- Network.framework data plane
- Keychain-backed key storage
- background behavior constrained by iOS lifecycle rules

## Rust boundary

`core/rust` intentionally starts dependency-free and only defines portable contract types. Do not move production private-key operations into Rust until the FFI ownership model, secure key storage boundary and audited crypto crates have been reviewed.

## Next implementation slice

1. Android and iOS binding interface definition.
2. Persistent replay/trust store contract.
3. Transport adapter interface with capability negotiation.
4. Android BLE discovery spike.
5. iOS Core Bluetooth discovery spike.
6. Cross-platform interoperability vectors for identity, signatures, HKDF and AES-GCM.
