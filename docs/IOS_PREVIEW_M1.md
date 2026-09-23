# Telemetry iOS Preview M1

Telemetry iOS Preview is an iPhone trial client built with React Native + Expo SDK 57 **development/internal builds**, not Expo Go and not a PWA.

## Why this shape

The Telemetry core needs custom native access to CoreBluetooth and CryptoKit. Expo Go cannot load project-specific native modules. The preview app therefore uses a local Expo Module written in Swift and is distributed with EAS Development Build or EAS Internal Distribution.

## M1 acceptance target

Two physical devices can operate with cellular data and internet Wi-Fi disconnected:

1. Open Telemetry iOS Preview / Telemetry Android Preview.
2. Start Offline on both devices.
3. Nearby discovery sees the Telemetry BLE service.
4. One device connects over GATT.
5. Both devices exchange signed `telemetry/m1b/hello` frames.
6. Both derive the same ephemeral X25519 + HKDF-SHA256 session key.
7. User compares the six-digit safety code and explicitly trusts the peer.
8. Text is encrypted with AES-256-GCM and sent through the M1B GATT message characteristic.
9. Recipient returns an Ed25519-signed delivery receipt.

The iOS wire format and UUIDs intentionally match Android M1B.

## Trial installation

A real iPhone development/internal build requires Apple code signing. With an Expo account and Apple Developer account available:

```bash
cd mobile/ios-preview
npm install
npx expo-doctor
npx eas-cli@latest login
npx eas-cli@latest build:configure
npx eas-cli@latest device:create
npx eas-cli@latest build --platform ios --profile preview
```

EAS will provide an install page/QR after the build finishes. Register the target iPhone before creating the internal build.

For a developer client instead, use `--profile development`, install the IPA, then run `npm start` while the phone and development machine can reach each other.

## Wi-Fi Aware

Apple Wi-Fi Aware is an M1C transport upgrade, not silently claimed as part of M1. It requires iOS 26+, supported hardware (iPhone 12+), the Wi-Fi Aware entitlement, declared services, and Network framework integration. BLE remains the discovery/bootstrap path until that slice is implemented and physically verified.

## Security boundary

Private identity material remains in the iPhone app container/Keychain path and is never sent to Railway. Railway is not required for discovery, session establishment, trust verification, message encryption/decryption, or delivery receipts.
