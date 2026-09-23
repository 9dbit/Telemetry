# Telemetry iOS Physical Trial

Status: build-ready foundation. Physical radio interoperability is not considered passed until this checklist is completed on real devices.

## Goal

Prove that Telemetry can establish and use an authenticated offline session on physical phones with mobile data and internet unavailable.

Required trial matrix:

1. iPhone A ↔ iPhone B
2. iPhone ↔ Android M1B device

The trial must exercise the real CoreBluetooth / BLE GATT path. Railway, Wi-Fi internet, and cellular data are not required for discovery, trust, session establishment, message encryption, or delivery receipt verification.

## Build profiles

Run commands from `mobile/ios-preview`.

### Development device build

Use this first because it includes the development client and is easiest to diagnose on a registered iPhone.

```bash
eas build --platform ios --profile development-device
```

### Preview device build

Use this after the development build passes. This is an internal distribution build without the development client UX.

```bash
eas build --platform ios --profile preview-device
```

### Simulator build

This is for UI/native compile smoke testing only. It does not replace physical Bluetooth testing.

```bash
eas build --platform ios --profile development-simulator
```

### TestFlight build

Use this only after the direct physical interoperability trial passes.

```bash
eas build --platform ios --profile testflight
```

Then submit the completed store build:

```bash
eas submit --platform ios --profile testflight
```

## One-time account prerequisites

The repository intentionally contains no Apple private credentials, signing certificates, provisioning profiles, App Store Connect API keys, or Expo access tokens.

Before the first physical build:

1. Authenticate EAS CLI with the Expo account that will own Telemetry.
2. Link `mobile/ios-preview` to an EAS project. EAS will write the project identifier into Expo app configuration.
3. Use an Apple Developer team that can sign `com.telemetry.ios.preview`.
4. Register each iPhone used by an ad hoc/internal build so its UDID is included in the provisioning profile.
5. On iOS 16+, enable Developer Mode for development/internal device builds.

Do not commit Apple private keys, `.p8` files, signing certificates, provisioning profiles, Expo tokens, or account passwords to Git.

## Physical trial procedure

For every test pair:

1. Install the matching Telemetry preview build.
2. Turn off mobile data on both devices.
3. Disconnect both devices from Wi-Fi networks that provide internet.
4. Keep Bluetooth enabled.
5. Launch Telemetry and grant Bluetooth permission.
6. Start offline discovery on both devices.
7. Confirm the peer appears without exposing a stable Telemetry identity in the public advertisement.
8. Connect to the peer over BLE GATT.
9. Confirm a signed peer hello is accepted and the secure session becomes available.
10. Compare the displayed six-digit safety code on both devices. The values must match.
11. Explicitly trust the peer on both devices.
12. Send `Hello Telemetry` from A to B.
13. Confirm B decrypts the text locally.
14. Confirm A transitions to delivered only after receiving a valid signed delivery receipt from B.
15. Repeat B to A.
16. Replay the same encrypted frame in a debug test and confirm it is rejected as a duplicate.
17. Revoke/clear trust, reconnect, and confirm sending is blocked until trust is established again.

## Acceptance criteria

A device pair passes only when all of these are true:

- Discovery works with internet unavailable.
- Stable long-term identity is not present in public BLE advertising.
- Signed hello validation succeeds.
- Both devices derive a compatible session and matching safety code.
- Message plaintext is never sent over BLE.
- `Hello Telemetry` decrypts correctly at the recipient.
- Duplicate/replayed message IDs are rejected.
- Delivery status is backed by the recipient's verifiable Ed25519 receipt.
- Railway can be unreachable without breaking the direct offline session.

Record for each test: device models, OS versions, app build number, peer direction, discovery time, connection result, safety-code match, send result, delivery receipt result, and any CoreBluetooth error.

## Known M1 boundary

M1 physical trial uses CoreBluetooth BLE GATT. Wi-Fi Aware remains an M1C transport upgrade and must not be reported as active until its native implementation and physical-device test are complete.
