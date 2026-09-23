# Telemetry iOS Physical Trial

Status: build-ready foundation. Physical radio interoperability is not considered passed until this checklist is completed on real devices.

## Goal

Prove that Telemetry can establish and use an authenticated offline session on physical phones with mobile data and internet unavailable.

Required trial matrix:

1. iPhone A ↔ iPhone B
2. iPhone ↔ Android M1B device

The trial must exercise the real CoreBluetooth / BLE GATT path. Railway, Wi-Fi internet, and cellular data are not required for discovery, trust, session establishment, message encryption, or delivery receipt verification.

## Physical Trial build

The current physical-trial build is:

- App version: `0.1.1`
- iOS build number: `2`
- Protocol: `telemetry/m1b`
- Transport: CoreBluetooth BLE GATT
- Public deterministic interop vector: `mobile/contracts/m1b-interop-v1.json`

The app now includes an `M1 TRIAL` overlay. It is deliberately separate from the normal chat UI so trial instrumentation cannot silently alter chat behavior.

The overlay records only protocol milestones. It does **not** export message plaintext, private keys, public keys, or full stable Telemetry device IDs.

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

## Preflight before turning radios into the acceptance gate

Open `M1 TRIAL` on the iPhone and verify:

- `CryptoKit ↔ Android vector` is PASS.
- The deterministic self-test reports all vector checks passing.
- The vector safety code is `822 483`.
- No self-test failure is listed.

Android CI independently validates the same public vector through BouncyCastle. A physical pair must not be accepted if either platform's deterministic vector gate is red.

This self-test is a cryptographic compatibility preflight. It does not replace real BLE testing.

## Physical trial procedure

For every test pair:

1. Install the matching Telemetry preview build.
2. Turn off mobile data on both devices.
3. Disconnect both devices from Wi-Fi networks that provide internet.
4. Keep Bluetooth enabled.
5. Launch Telemetry and grant Bluetooth permission.
6. On iPhone, open `M1 TRIAL` and press `Reset leg` before each matrix leg.
7. Start offline discovery on both devices.
8. Confirm the peer appears without exposing a stable Telemetry identity in the public advertisement.
9. In `M1 TRIAL`, confirm `BLE peer discovered` becomes PASS.
10. Connect to the peer over BLE GATT.
11. Confirm `GATT connected` becomes PASS.
12. Confirm a signed peer hello is accepted and the secure session becomes available. `Signed hello + session key` must become PASS.
13. Compare the displayed six-digit safety code on both devices. The values must match exactly.
14. Explicitly trust the peer on both devices. `Safety code trusted` must become PASS.
15. On iPhone, press `Send “Hello Telemetry”`.
16. Confirm the recipient decrypts exactly `Hello Telemetry` locally.
17. Confirm `Hello Telemetry sent` is PASS on the sender.
18. Confirm `Encrypted message received` is PASS on the recipient when the recipient is an iPhone.
19. Confirm the sender transitions to delivered only after receiving a valid signed delivery receipt. `Signed DELIVERED receipt` must become PASS.
20. Send `Hello Telemetry` in the reverse direction using the normal chat UI and repeat the receive/delivery checks.
21. Repeat the entire leg with the other required device pairing.
22. Replay the same encrypted frame in a debug test and confirm it is rejected as a duplicate.
23. Revoke/clear trust, reconnect, and confirm sending is blocked until trust is established again.

## Required matrix

### Leg A: iPhone A → iPhone B

Required PASS indicators on sender:

- Crypto interop vector
- BLE peer discovered
- GATT connected
- Signed hello + session key
- Safety code trusted
- Hello Telemetry sent
- Signed DELIVERED receipt

Required recipient behavior:

- `Hello Telemetry` appears only after local AES-GCM decryption.
- Encrypted message received indicator becomes PASS.

Then reverse B → A.

### Leg B: iPhone ↔ Android

The iPhone must show the same sender indicators as above. Android must show:

- secure connection established,
- matching safety code,
- trusted session,
- decrypted `Hello Telemetry`,
- delivery confirmation backed by the signed receipt.

Then reverse Android → iPhone and confirm the iPhone's `Encrypted message received` milestone.

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

## Diagnostic export

Use `M1 TRIAL` → `Share diagnostics` after each matrix leg.

The report includes:

- platform and OS version,
- only the suffix of local/trusted Telemetry device IDs,
- interop self-test result,
- timestamped protocol milestones,
- native errors.

The report intentionally excludes:

- message plaintext,
- private keys,
- public keys,
- full stable device IDs.

Record separately for each test: device models, OS versions, app build number, peer direction, discovery time, connection result, safety-code match, send result, delivery receipt result, and any CoreBluetooth/Android GATT error.

## Known M1 boundary

M1 physical trial uses CoreBluetooth BLE GATT. Wi-Fi Aware remains an M1C transport upgrade and must not be reported as active until its native implementation and physical-device test are complete.
