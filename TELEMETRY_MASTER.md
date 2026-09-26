# Telemetry Master State

## Mission
Telemetry is a resilient native communications platform for iOS and Android that keeps messaging and device connectivity useful beyond conventional internet availability.

Core layers:
- Telemetry App
- Telemetry Mesh
- Telemetry Protocol
- Telemetry Node
- Telemetry Control

Cloud services are optional control/distribution infrastructure. Offline local communication must continue when cloud connectivity is unavailable.

## Development model
- GitHub is the source of truth.
- Work through focused branches and draft PRs.
- Physical device evidence is required for hardware acceptance.
- Prefer parallel work only where branches do not violate dependency order.

## Current dependency chain
As of 2026-09-26, the active stacked line is:
- PR #15 `feature/m1.4-mesh-core`
- PR #22 `feature/m1.4e-multihop-contract`
- PR #23 `feature/m1.5a-secure-media-foundation`

Additional work stacked from the media foundation includes:
- PR #24 `feature/m1.6-p2p-voice-foundation`
- PR #26 `feature/m0.9-android-self-update`

These PRs remain draft until their stated physical acceptance gates are satisfied. Preserve their dependency order.

## Current product priorities
P0 focuses on completing real-device acceptance and closing the gap between implemented protocol/runtime foundations and actual user-visible behavior:
1. reliable media transfer and previews
2. voice call path
3. video call path
4. updater acceptance
5. regressions around Nearby, profile photos and chat UX

Mesh relay, LoRa gateway and satellite gateway continue after the current P0 acceptance gates are under control, unless they are required to unblock a P0 path.

## Command convention
When the user says `Telemetry: continue P0`:
1. read `ACTIVE_TASKS.md`
2. pick the first unblocked P0 item
3. inspect current code/PR state
4. implement and test without asking about minor engineering choices
5. commit/push to the correct branch
6. update task/test state
7. stop only at a true external/hardware/credential gate and record the exact next action

## UX baseline
- English UI
- ocean-blue brand color
- Nearby has List + Field views
- profile photos on peers/contacts
- chat opens at latest message
- BLE vs Wi-Fi tests are visibly distinct
- media panel should behave like a modern messenger gallery with trustworthy transfer state

## Security baseline
- end-to-end encryption and signatures remain transport-independent
- relays handle opaque ciphertext wherever possible
- trust/pairing and identity verification are explicit
- no cloud dependency for local decryption
- integrity verification is mandatory for received media
