# Telemetry Android UI v2

UI v2 turns the Android M1B radio/crypto prototype into a daily-use offline communication shell.

## Primary navigation

- **Messages** — default home, current trusted conversation, Nearby shortcut, and SOS entry.
- **Nearby** — filtered Telemetry BLE scanner with signal labels, raw RSSI, rescan, empty-state troubleshooting, and optional diagnostics.
- **Network** — local transport/session status, route, encryption, and key-agreement visibility.
- **Settings** — device identity, privacy boundary, cloud-dependency statement, and build information.

Trust verification and chat remain event-driven screens reached from the real M1B GATT handshake.

## SOS behavior in this milestone

The SOS screen uses a two-second press-and-hold guard. In UI v2 the action sends an encrypted emergency message to the **currently active trusted direct peer** through the existing M1B `TelemetryGattTransport`.

It is deliberately not described as proven multi-hop emergency broadcast yet. Fan-out, priority queues, relay TTL, node escalation, and satellite escalation belong to the later mesh/emergency routing milestone.

## Nearby privacy

BLE advertisements keep the stable device identity out of the radio payload. The scanner exposes ephemeral peer labels and signal diagnostics until the authenticated secure handshake reveals the trusted identity.

## Acceptance gate

CI must pass Android unit tests and `assembleDebug`. A green build does not replace physical two-phone BLE/GATT acceptance testing.
