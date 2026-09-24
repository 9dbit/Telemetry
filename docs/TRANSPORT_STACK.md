# Telemetry Adaptive Transport Stack

## Principle

Connection is invisible. Delivery is visible.

A user creates a message intent. Telemetry owns discovery, route selection, secure session establishment, retries, transport upgrades, fallback, and signed delivery receipts.

The encrypted message envelope must not depend on BLE, Wi-Fi, LoRa, satellite, or cloud connectivity.

## Logical pipeline

Message Intent
→ Local Queue
→ Peer / Destination Resolver
→ Route Resolver
→ Secure Session
→ Transport Adapter
→ Remote Transport Adapter
→ Destination
→ Signed Delivery Receipt

## Route preference

Initial policy:

1. Direct high-bandwidth local link
2. Direct BLE
3. Nearby relay / mesh
4. Telemetry Node long-range link
5. Internet gateway
6. Satellite gateway

The route resolver may choose differently for SOS, battery constraints, payload size, or link quality.

## Common transport contract

Every adapter should expose equivalent lifecycle semantics:

- capability
- discover / advertise where applicable
- connect or acquire route
- secure-session bootstrap support
- maximum payload / MTU
- send frame
- receive frame
- link-quality metadata
- disconnect / release
- route health

Recommended logical model:

```text
TransportKind
  ble
  wifi
  relay
  lora
  internet
  satellite

TransportState
  unavailable
  ready
  discovering
  connecting
  active
  degraded
  disconnected

RouteCandidate
  transport
  destination
  nextHop
  hopCount
  estimatedLatency
  payloadLimit
  linkQuality
  batteryCost
  monetaryCost
  available
```

## BLE

Current validated transport.

Role:
- nearby discovery
- identity/bootstrap
- direct low-bandwidth messaging
- fallback transport
- gateway discovery

Security:
- Ed25519 identity
- ephemeral X25519 session
- HKDF-SHA256
- AES-256-GCM message payload
- signed delivery receipt

M1.1 goal:
- no manual Connect in normal messaging
- reconnect when a queued message needs the route
- keep trust across sessions while creating fresh ephemeral session keys

## Wi-Fi

Wi-Fi is the next direct transport upgrade after M1 reliability.

Preferred model:

BLE discovery/bootstrap
→ negotiate Wi-Fi capability
→ establish local Wi-Fi route
→ continue the same secure conversation over higher bandwidth
→ fall back to BLE if Wi-Fi route disappears

Use cases:
- message bursts
- images
- files
- voice notes
- future live data

The chat must not change identity or conversation when the underlying transport upgrades.

## Relay / Mesh

Store-and-forward relay is transport-independent.

A relay may know:
- next hop
- message routing metadata
- expiry / TTL
- delivery state

A relay must not need conversation plaintext or end-to-end decryption keys.

Initial milestone:
- three devices A → B → C
- C is destination
- B forwards ciphertext
- signed destination receipt returns toward A

## Telemetry Node

A Telemetry Node is a dedicated gateway/relay device that can expose transports not built into a phone.

Initial node roles:
- BLE/Wi-Fi link to phones
- persistent relay
- LoRa radio
- Internet uplink when available
- future satellite modem

Candidate hardware can be evaluated after phone-to-phone reliability is mature.

## LoRa

LoRa is not treated as native phone-to-phone mesh.

Initial architecture:

Phone
↕ BLE / Wi-Fi
Telemetry Node
↕ LoRa
Remote Telemetry Node
↕ BLE / Wi-Fi
Phone

Prioritized payloads:
- short text
- SOS
- coordinates
- route beacons
- delivery receipts

Large payloads must remain queued for a higher-bandwidth route.

The protocol should support fragmentation only after payload and duty-cycle limits are explicitly modeled.

## Satellite

Initial satellite support is gateway-based.

Architecture:

Phone
↕ BLE / Wi-Fi
Telemetry Gateway
↕ satellite modem / provider
Remote gateway / control plane / destination route

Primary uses:
- emergency escalation
- remote backhaul
- disaster recovery
- areas without terrestrial coverage

Satellite must be modeled as expensive/high-latency compared with local transports. Route selection should be policy-driven rather than automatic for large payloads.

## Message queue policy

M1.1:
- memory queue
- auto-connect and retry while app is active

M1.2:
- persistent encrypted queue
- restart-safe retries
- backoff
- expiry
- duplicate suppression
- battery-aware scheduling

Future queue records should contain only the minimum routing metadata needed outside the encrypted envelope.

## Trust and reconnect

Identity trust and transport session are separate concepts.

Trusted identity:
- persists until revoked or identity key changes

Transport session:
- ephemeral
- may disappear at any time
- reconnect creates fresh ephemeral X25519 material

Normal reconnect to the same trusted signing identity does not require repeating the safety-code ceremony.

Identity-key change requires explicit re-verification.

## Delivery UI

User-facing route states:
- Queued
- Finding peer
- Connecting
- Sending
- Delivered
- Retry pending

Optional route detail:
- Direct · BLE
- Direct · Wi-Fi
- Via 1 relay
- Long range · LoRa
- Gateway · Satellite

Transport details belong in Network/Diagnostics. Normal chat should focus on delivery state.

## SOS policy

SOS will eventually use a different routing policy:
- priority queue
- all eligible direct routes
- relay fan-out
- location attachment when explicitly enabled
- aggressive retry
- escalation to long-range gateway
- satellite route when policy permits

M1 does not claim multi-hop SOS yet.

## Implementation order

1. M1.1 seamless send and reconnect UX
2. M1.2 persistent queue and reliability tests
3. M2 Wi-Fi transport upgrade
4. M3 store-and-forward
5. M4 three-device multi-hop
6. M5 Telemetry Node
7. M6 LoRa adapter
8. M7 satellite gateway adapter
