# Telemetry M1.1 Seamless UX Plan

Status: approved for implementation after M1 iPhone↔iPhone direct BLE hardware validation.

## Product principle

Connection is invisible. Delivery is visible.

Users should not need to understand connect/disconnect state. Sending a message creates a message intent. The transport manager decides whether to send immediately, auto-connect, scan, queue, retry, or upgrade transport.

## M1.1 scope

### 1. Seamless send flow

- Remove manual Connect from the normal chat UX.
- Send always accepts the message into a local outbound queue first.
- If a secure peer session is active, transmit immediately.
- If trusted peer is disconnected but recently seen, reconnect automatically.
- If peer is not currently visible, start discovery automatically and keep the message queued.
- Trusted identity persists across reconnects.
- Reconnect creates a fresh ephemeral X25519 session.
- Safety-code verification is only shown for first trust or identity-key change.

### 2. Delivery states

Message states:
- Queued
- Finding peer
- Connecting
- Securing
- Sending
- Delivered
- Retry pending
- Failed

Signed delivery receipt remains the authority for Delivered.

### 3. Chat composer

- Composer must remain directly above the software keyboard.
- Use keyboard-aware layout on iOS.
- Auto-grow multiline input within a bounded height.
- Preserve latest message visibility when keyboard opens.
- Keep Send button reachable at all times.

### 4. Bottom navigation redesign

Target tabs:
- Chats
- Nearby
- Network
- SOS
- Settings

Each tab uses a flat icon above a readable label.

Guideline:
- icon 22–24 pt
- label 12–13 pt, medium weight
- touch target minimum 44×44 pt
- clear active/inactive state
- respect iPhone safe areas

### 5. Nearby UX

Nearby is discovery and diagnostics, not the primary connect workflow.

Show:
- device alias
- signal quality / RSSI
- trusted or new identity
- last seen
- available transports
- relay/node capability later

Manual Connect stays available only in diagnostics/developer controls.

### 6. Network screen

Show current capability and route readiness for:
- BLE
- Wi‑Fi / Wi‑Fi Aware
- Relay mesh
- LoRa gateway
- Satellite gateway

M1.1 keeps BLE as the only working transport but establishes the UI/state model for future transports.

## Transport roadmap

### BLE

Role:
- discovery
- identity/bootstrap
- low-bandwidth direct chat
- fallback transport

### Wi‑Fi

Next transport milestone.

Expected flow:
BLE discovery → secure bootstrap → automatic Wi‑Fi transport upgrade.

Use for higher throughput, larger message bursts, files, images and voice in later milestones.

### LoRa

Initial architecture is gateway/node based, not phone-to-phone LoRa.

Phone ↔ BLE/Wi‑Fi ↔ Telemetry Node ↔ LoRa ↔ Telemetry Node ↔ Phone

Prioritize text, SOS, coordinates and receipts.

### Satellite

Initial architecture is gateway based.

Phone ↔ Telemetry Node/Gateway ↔ Satellite uplink ↔ remote gateway/cloud/operator

Satellite is a route option, not a chat mode visible to users.

## Next milestones

### M1.1 Seamless UX
- auto-connect on send
- message intent queue
- reconnect state machine
- trusted-peer reconnect
- keyboard-safe composer
- bottom nav icon+label redesign
- improved delivery state UI

### M1.2 Reliability
- retry/backoff
- queue persistence
- reconnect after range loss
- Bluetooth off/on recovery
- foreground/background tests
- screen lock tests
- 50–100 message endurance test
- battery-impact measurements

### M2 Wi‑Fi transport upgrade
- BLE bootstrap
- Wi‑Fi transport negotiation
- route upgrade/fallback

### M3 Store-and-forward
- delayed delivery
- peer reappearance delivery
- encrypted local queues

### M4 Multi-hop mesh
- 3-device relay
- hop metadata
- relay cannot decrypt end-to-end payload

### M5 Telemetry Node
- BLE/Wi‑Fi bridge
- relay/gateway role

### M6 LoRa
- node-to-node long-range transport
- SOS/text/coordinates

### M7 Satellite gateway
- remote backhaul
- emergency escalation

## M1.1 acceptance criteria

- User can send without manually tapping Connect.
- Disconnected trusted peer auto-connects when reachable.
- Message queues while peer is unavailable and retries when peer reappears.
- Existing trusted identity does not request safety-code verification on normal reconnect.
- Identity-key change forces re-verification.
- Chat composer is never hidden by the iOS keyboard.
- Bottom nav has flat icons and readable labels.
- UI never claims active BLE session after native disconnect.
- Both iPhone directions can send and receive with signed Delivered receipts.
