# Telemetry Active Tasks

Status values: `TODO`, `IN_PROGRESS`, `READY_FOR_DEVICE_ACCEPTANCE`, `BLOCKED_EXTERNAL`, `DONE`.

## P0.1 iOS two-device media acceptance
Status: `IN_PROGRESS`

Primary devices:
- iPhone 15 Pro
- iPhone 11 Pro

Acceptance:
- photo can be selected and appears immediately as a local preview
- visible send progress while transfer is incomplete
- receiving device shows the real received image preview, not a permanent placeholder/progress row
- tap opens the received media
- final received bytes pass SHA-256 integrity verification
- failed transfer exposes a retry path
- interrupted transfer can resume without silently duplicating/corrupting the asset
- peer/contact avatar synchronizes and renders on both devices
- `+` media picker remains functional
- Light Mode and Nearby controls have no regression

Do not mark DONE without physical two-iPhone evidence.

## P0.2 Media reliability and cross-platform acceptance
Status: `TODO`
Dependency: P0.1

Acceptance:
- photo, generic document and short video transfer
- Wi-Fi loss and resume using the existing encrypted transfer contract
- 10 MB and 100 MB probes
- duplicate/conflict rejection
- receiver reconstruction and final hash verification
- mixed iOS <-> Android acceptance when the iOS media runtime is ready

Related foundation: PR #23.

## P0.3 Voice call
Status: `IN_PROGRESS`

Acceptance:
- call controls/status integrated into primary Chat UI
- microphone permission requested only when needed
- invite -> ring -> accept -> connect -> two-way audio -> hangup works on supported direct Wi-Fi path
- mute, speaker, decline, busy and timeout behavior verified
- BLE-only/mesh-only routes do not pretend to support realtime audio; fallback remains available
- iOS implementation reaches parity with the accepted voice architecture before P0 voice is complete

Related foundation: PR #24 currently contains Android voice groundwork.

## P0.4 Video call
Status: `TODO`
Dependency: P0.3

Acceptance:
- camera + microphone permissions and lifecycle are correct
- two-device local/direct Wi-Fi video call works
- front/back camera switching and hangup are stable
- loss of realtime path fails cleanly
- no claim that BLE/LoRa/satellite store-and-forward paths carry realtime video

## P0.5 Android self-update acceptance
Status: `READY_FOR_DEVICE_ACCEPTANCE`

Acceptance:
- promote first approved Preview release
- Railway returns the promoted manifest
- version A installed once manually
- Settings -> Check for Update -> download -> integrity/package/signing checks -> Android installer -> version B
- corrupted hash/APK, downgrade and offline cases fail safely

Related foundation: PR #26.

## P0.6 UX regression sweep
Status: `TODO`

Acceptance:
- Nearby List and Field views both remain available
- Field view is utility-oriented, not game-like
- peer rows/cards show profile photo, alias/id, distance/signal and useful network capability state
- chat opens at latest message from direct navigation and notifications
- BLE vs Wi-Fi test actions are unmistakably distinct
- header badges/controls do not overlap
- all user-facing copy is English
- ocean-blue brand color remains consistent

## After P0
Next major sequence:
1. mesh relay production UX and mixed-device acceptance
2. LoRa gateway
3. satellite gateway
4. broader social/reporting layer only after communications reliability is stable
