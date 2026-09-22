# Telemetry M1B: Secure Offline Session

M1B turns M1A presence discovery into a short-message peer session that does not depend on SIM, internet, Railway, or a central identity service.

## Flow

1. Both Android devices start a connectable Telemetry BLE advertisement and GATT server.
2. A device discovers a nearby ephemeral peer and taps **Connect**.
3. The initiator requests a larger BLE MTU, discovers the Telemetry service and exchanges signed session hellos.
4. Each hello binds the stable Telemetry device ID, Ed25519 signing key, stable X25519 pairing key, fresh ephemeral X25519 public key, nonce and timestamp.
5. Both devices verify the Ed25519 signature and derive a fresh session key from the ephemeral X25519 exchange with HKDF-SHA256.
6. Both devices calculate the same six-digit safety code. The users compare the code out-of-band and explicitly trust the peer.
7. Short text is encrypted with AES-256-GCM and written over the GATT message characteristic.
8. The recipient rejects unknown, untrusted, wrong-recipient or duplicate packets, decrypts locally, then signs a delivery receipt.
9. The sender verifies the recipient's Ed25519 receipt before showing **Delivered**.

## Privacy and key handling

- BLE advertisements expose only the Telemetry service UUID. Stable device ID and public identity are not advertised.
- The current BLE radio address is used only as an in-memory connection locator and is not persisted by Telemetry.
- Ed25519 and stable X25519 private seeds are sealed locally with an AES-GCM wrapping key held by Android Keystore.
- Ephemeral X25519 private material exists only for the active peer session.
- Session keys and message plaintext are never sent to Railway.
- Cloud availability is irrelevant to M1B message exchange.

## Cryptographic construction

M1B uses Bouncy Castle 1.86 for Ed25519 and X25519 primitives, HKDF-SHA256 for session derivation and the Android platform AES-GCM implementation for payload encryption.

This is a project protocol under active development, not a claim of Signal-equivalent maturity. Before public production use, the protocol needs independent security review, persistent replay state, formal key-rotation rules and adversarial interoperability tests.

## Current transport limits

- One initiating GATT connection carries the session.
- Text payload is limited to 160 UTF-8 bytes.
- No fragmentation/reassembly yet.
- No Wi-Fi Aware handoff yet.
- Replay memory is in-process for this milestone.
- Manual safety-code comparison is the trust gate. QR-camera pairing remains a UX improvement to add without changing the signed identity model.

## Physical acceptance test

M1B is accepted only after a two-phone test, not merely a CI build:

1. Install the same debug APK on two Android phones.
2. Disable mobile data and disconnect both devices from internet Wi-Fi.
3. Keep Bluetooth enabled and grant Nearby Devices permission.
4. Start offline mode on both phones.
5. On phone A, connect to the peer shown nearby.
6. Confirm both screens show the same safety code.
7. Tap **Codes match · Trust peer** on both devices.
8. From phone A send `Hello Telemetry`.
9. Phone B must display the plaintext locally.
10. Phone A must show `Delivered · signed receipt verified`.

A passing GitHub build proves compile/unit correctness only. It does not prove physical BLE interoperability until this checklist passes on real hardware.
