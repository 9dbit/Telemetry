# Telemetry Protocol v0.1

Telemetry Protocol v0.1 defines the first transport-independent message boundary for the platform.

## Goals

- Work without cloud availability.
- Keep relay nodes unable to read message content.
- Allow the same envelope to move over BLE, Wi-Fi peer links, internet gateways, LoRa gateways or satellite gateways.
- Support store-and-forward delivery and bounded multi-hop forwarding.
- Separate device identity, cryptography, routing and transport implementations.

## Device identity

Each device owns two key pairs:

- Ed25519 for signing envelopes.
- X25519 for key agreement.

The current device ID is derived from the SHA-256 fingerprint of the Ed25519 public key. Private keys are never part of the public identity object.

> v0.1 is a protocol foundation. Production mobile builds must store private keys in the Android Keystore / iOS Keychain or secure hardware when available.

## Session key

Two peers derive a shared secret with X25519 and derive a 32-byte session key through HKDF-SHA256 using the context `telemetry/v0.1/session`.

## Payload encryption

Message payloads use AES-256-GCM with a fresh 96-bit nonce. The encrypted payload contains only algorithm metadata, nonce, ciphertext and authentication tag.

## Envelope

A signed envelope contains:

- protocol version
- message ID
- conversation ID
- sender device ID
- recipient device ID
- creation timestamp
- hop count
- hop limit
- content type
- encrypted payload
- sender signature

Default hop limit: 8. Current validation accepts hop limits from 1 to 32.

## Relay behavior

A relay is expected to inspect only routing metadata. It must not require the plaintext content to decide how the message travels. `envelopeRelayView()` demonstrates the intended minimum metadata view.

## Store-and-forward lifecycle

The initial queue lifecycle is:

`queued -> inflight -> delivered`

A failed attempt can enter `retry`, then return to `inflight` on the next dispatch attempt.

The current Node implementation is in-memory for protocol testing. Device applications will replace this adapter with durable local storage.

## Transport selection

The v0.1 policy engine can score:

- Wi-Fi Direct
- Wi-Fi Aware
- BLE
- Internet
- LoRa
- Satellite gateway

It prefers local direct links by default and penalizes transports that are metered or poorly suited to larger payloads. This policy will later incorporate peer reachability, battery, latency, cost and gateway trust.

## Simulator

`POST /api/v1/simulate/message` creates two temporary identities, derives the same session key independently on both peers, encrypts the payload, signs the envelope, selects a transport, runs a queue lifecycle and decrypts only at the destination.

The simulator is a development instrument. Temporary private keys are not returned by the API.

## Next protocol work

1. Persistent key storage adapters for mobile/device environments.
2. Pairing and trust handshake with QR / out-of-band verification.
3. Replay protection and message expiry.
4. Delivery receipts and acknowledgement envelopes.
5. Fragmentation/reassembly for constrained transports.
6. Peer discovery abstraction.
7. BLE transport spike.
8. Wi-Fi Direct / Wi-Fi Aware payload transport.
9. Multi-hop routing table and duplicate suppression.
10. Gateway protocol for LoRa and satellite bridges.
