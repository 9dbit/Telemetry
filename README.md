# Telemetry

Telemetry is a resilient communications platform designed to keep people and devices connected beyond conventional internet availability.

## Product layers

- **Telemetry App** — human messaging, local identity, offline inbox, QR pairing.
- **Telemetry Mesh** — BLE discovery, Wi-Fi peer links, store-and-forward and multi-hop routing.
- **Telemetry Node** — gateway software/hardware bridging local mesh to long-range transports.
- **Telemetry Control** — provisioning, fleet health, routing observability and policy.
- **Telemetry Protocol** — transport-agnostic envelopes, delivery state, identity and cryptographic primitives.

## Transport roadmap

1. Internet transport for control-plane development and testing.
2. BLE discovery and encrypted nearby session establishment.
3. Wi-Fi Direct / Wi-Fi Aware payload transport.
4. Store-and-forward multi-hop mesh.
5. LoRa / long-range gateway integration.
6. Satellite gateway integration.

## Foundation architecture

The Railway deployment is the **control-plane foundation**, not the offline transport itself. Offline networking must continue to function independently of cloud availability.

```text
Telemetry App
   |  encrypted signed envelope
   v
Local Queue / Transport Router
   |-- BLE
   |-- Wi-Fi Direct / Wi-Fi Aware
   |-- Internet
   |-- LoRa Gateway
   `-- Satellite Gateway

Optional cloud control plane
   |-- provisioning
   |-- fleet/node status
   |-- public-key directory / trust metadata
   |-- observability
   `-- software/config distribution
```

## Telemetry Protocol v0.1

The first protocol foundation is implemented in `src/protocol/`.

Current primitives:

- Ed25519 envelope signing.
- X25519 peer key agreement.
- HKDF-SHA256 session-key derivation.
- AES-256-GCM encrypted payloads.
- Signed transport-independent envelopes.
- Hop count / hop limit metadata.
- Store-and-forward queue lifecycle.
- Transport policy scoring.
- End-to-end development simulator.

Full protocol notes: [`docs/PROTOCOL_V0_1.md`](docs/PROTOCOL_V0_1.md).

## Security principles

- End-to-end encryption between conversation endpoints.
- Relay nodes should handle opaque encrypted envelopes wherever possible.
- Offline-first identity and message persistence.
- Explicit trust/pairing model.
- No dependency on Telemetry cloud for local message decryption.
- Minimal metadata retention in the control plane.

The current Node implementation is a protocol simulator. Production phone/device builds must move private-key storage into secure platform facilities such as Android Keystore or iOS Keychain / Secure Enclave where appropriate.

## Current endpoints

- `GET /health`
- `GET /api/v1/capabilities`
- `GET /api/v1/protocol`
- `POST /api/v1/route`
- `POST /api/v1/simulate/message`

Example simulation request:

```json
{
  "text": "hello without internet"
}
```

## Run locally

```bash
npm install
npm test
npm start
```

The service listens on `PORT` when provided, otherwise port `3000`.

## Next milestones

- Pairing and trust handshake with QR verification.
- Replay protection and message expiry.
- Delivery-receipt envelopes.
- Fragmentation/reassembly for constrained transports.
- Build Android transport spike for BLE discovery.
- Add Wi-Fi Direct / Wi-Fi Aware peer payload channel.
- Replace simulator queue with durable mobile storage adapter.
- Create node/gateway simulator with multiple relay peers.
- Add control-plane authentication, PostgreSQL and node registry only when the protocol boundary is stable.
