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

The first Railway deployment is the **control-plane foundation**, not the offline transport itself. Offline networking must continue to function independently of cloud availability.

```text
Telemetry App
   |  local encrypted envelope
   v
Transport Router
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

## Security principles

- End-to-end encryption between conversation endpoints.
- Relay nodes should handle opaque encrypted envelopes wherever possible.
- Offline-first identity and message persistence.
- Explicit trust/pairing model.
- No dependency on Telemetry cloud for local message decryption.
- Minimal metadata retention in the control plane.

## Current endpoints

- `GET /health`
- `GET /api/v1/capabilities`

## Run locally

```bash
npm install
npm start
```

The service listens on `PORT` when provided, otherwise port `3000`.

## Next milestones

- Define protocol envelope and message lifecycle.
- Define device/node identity and cryptographic key model.
- Build Android transport spike for BLE discovery.
- Add Wi-Fi peer payload channel.
- Implement local message queue and delivery receipts.
- Create node/gateway simulator.
- Add control-plane authentication, PostgreSQL and node registry only when the protocol boundary is stable.
