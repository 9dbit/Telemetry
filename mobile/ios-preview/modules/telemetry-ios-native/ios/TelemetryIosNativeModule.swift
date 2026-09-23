import ExpoModulesCore
import Foundation
import CoreBluetooth
import CryptoKit
import Security

private let serviceUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D59")
private let helloUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D01")
private let messageUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D02")
private let receiptUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D03")

private let helloVersion = "telemetry/m1b/hello"
private let sessionContext = "telemetry/m1b/session"
private let messageContext = "telemetry/m1b/message"
private let receiptContext = "telemetry/m1b/receipt"
private let maxClockSkewMs: Int64 = 15 * 60 * 1000
private let maxTextBytes = 160

private enum TelemetryNativeError: LocalizedError {
  case message(String)

  var errorDescription: String? {
    switch self {
    case .message(let value): return value
    }
  }
}

private struct SessionHello {
  let deviceId: String
  let signingPublicKey: Data
  let exchangePublicKey: Data
  let ephemeralPublicKey: Data
  let nonce: Data
  let issuedAt: Int64
  let signature: Data
}

private struct EncryptedMessage {
  let messageId: String
  let senderId: String
  let recipientId: String
  let createdAt: Int64
  let nonce: Data
  let ciphertext: Data
}

private struct DeliveryReceipt {
  let messageId: String
  let senderId: String
  let recipientId: String
  let receivedAt: Int64
  let status: String
  let signature: Data
}

private final class PeerSession {
  let peerId: String
  let ephemeralPrivate: Curve25519.KeyAgreement.PrivateKey
  let localHello: SessionHello
  var remoteHello: SessionHello?
  var sessionKey: SymmetricKey?
  var lastOutboundMessageId: String?

  init(peerId: String, ephemeralPrivate: Curve25519.KeyAgreement.PrivateKey, localHello: SessionHello) {
    self.peerId = peerId
    self.ephemeralPrivate = ephemeralPrivate
    self.localHello = localHello
  }
}

private enum KeychainStore {
  static let service = "com.telemetry.ios.preview.identity"

  static func load(account: String) -> Data? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
    return item as? Data
  }

  static func save(account: String, data: Data) throws {
    let key: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account
    ]
    SecItemDelete(key as CFDictionary)
    var item = key
    item[kSecValueData as String] = data
    item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    let status = SecItemAdd(item as CFDictionary, nil)
    guard status == errSecSuccess else {
      throw TelemetryNativeError.message("Unable to store Telemetry identity in Keychain (\(status))")
    }
  }
}

private final class TelemetryCryptoEngine {
  let signingPrivate: Curve25519.Signing.PrivateKey
  let exchangePrivate: Curve25519.KeyAgreement.PrivateKey

  init() throws {
    if let raw = KeychainStore.load(account: "ed25519") {
      signingPrivate = try Curve25519.Signing.PrivateKey(rawRepresentation: raw)
    } else {
      let key = Curve25519.Signing.PrivateKey()
      try KeychainStore.save(account: "ed25519", data: key.rawRepresentation)
      signingPrivate = key
    }

    if let raw = KeychainStore.load(account: "x25519") {
      exchangePrivate = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
    } else {
      let key = Curve25519.KeyAgreement.PrivateKey()
      try KeychainStore.save(account: "x25519", data: key.rawRepresentation)
      exchangePrivate = key
    }
  }

  var signingPublicKey: Data { signingPrivate.publicKey.rawRepresentation }
  var exchangePublicKey: Data { exchangePrivate.publicKey.rawRepresentation }
  var deviceId: String { Self.deviceId(signingPublicKey) }

  static func deviceId(_ signingPublicKey: Data) -> String {
    let digest = SHA256.hash(data: signingPublicKey)
    return "tlm:device:" + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
  }

  func createHello(ephemeral: Curve25519.KeyAgreement.PrivateKey) throws -> SessionHello {
    let unsigned = SessionHello(
      deviceId: deviceId,
      signingPublicKey: signingPublicKey,
      exchangePublicKey: exchangePublicKey,
      ephemeralPublicKey: ephemeral.publicKey.rawRepresentation,
      nonce: try Self.randomBytes(count: 16),
      issuedAt: Self.nowMs(),
      signature: Data()
    )
    let signature = try signingPrivate.signature(for: Self.helloCanonical(unsigned))
    return SessionHello(
      deviceId: unsigned.deviceId,
      signingPublicKey: unsigned.signingPublicKey,
      exchangePublicKey: unsigned.exchangePublicKey,
      ephemeralPublicKey: unsigned.ephemeralPublicKey,
      nonce: unsigned.nonce,
      issuedAt: unsigned.issuedAt,
      signature: signature
    )
  }

  static func verifyHello(_ hello: SessionHello, now: Int64 = nowMs()) -> Bool {
    guard hello.signingPublicKey.count == 32,
          hello.exchangePublicKey.count == 32,
          hello.ephemeralPublicKey.count == 32,
          hello.nonce.count == 16,
          hello.signature.count == 64,
          deviceId(hello.signingPublicKey) == hello.deviceId,
          Swift.abs(now - hello.issuedAt) <= maxClockSkewMs else { return false }
    do {
      let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: hello.signingPublicKey)
      return publicKey.isValidSignature(hello.signature, for: helloCanonical(hello))
    } catch {
      return false
    }
  }

  static func deriveSessionKey(localSession: PeerSession, remoteHello: SessionHello) throws -> SymmetricKey {
    guard verifyHello(localSession.localHello), verifyHello(remoteHello) else {
      throw TelemetryNativeError.message("Signed hello is invalid or expired")
    }
    let remoteEphemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remoteHello.ephemeralPublicKey)
    let secret = try localSession.ephemeralPrivate.sharedSecretFromKeyAgreement(with: remoteEphemeral)
    let ordered = [localSession.localHello, remoteHello].sorted { $0.deviceId < $1.deviceId }

    // Android M1B uses literal pipe separators for HKDF sharedInfo.
    // This must stay byte-for-byte identical across platforms.
    var transcript = sessionContext
    for hello in ordered {
      transcript += "|\(hello.deviceId)|\(hex(hello.nonce))|\(hex(hello.ephemeralPublicKey))"
    }

    return secret.hkdfDerivedSymmetricKey(
      using: SHA256.self,
      salt: Data(),
      sharedInfo: Data(transcript.utf8),
      outputByteCount: 32
    )
  }

  static func safetyCode(_ a: SessionHello, _ b: SessionHello) -> String {
    let ordered = [a, b].sorted { $0.deviceId < $1.deviceId }
    var material = Data()
    for hello in ordered {
      material.append(Data(hello.deviceId.utf8))
      material.append(hello.signingPublicKey)
      material.append(hello.ephemeralPublicKey)
      material.append(hello.nonce)
    }
    let digest = Array(SHA256.hash(data: material))
    let raw = (UInt32(digest[0]) << 24) | (UInt32(digest[1]) << 16) | (UInt32(digest[2]) << 8) | UInt32(digest[3])
    let value = raw % 1_000_000
    return String(format: "%03d %03d", value / 1000, value % 1000)
  }

  static func encryptText(key: SymmetricKey, senderId: String, recipientId: String, text: String) throws -> EncryptedMessage {
    guard let payload = text.data(using: .utf8), !payload.isEmpty, payload.count <= maxTextBytes else {
      throw TelemetryNativeError.message("M1B text must be 1-\(maxTextBytes) UTF-8 bytes")
    }
    let messageId = UUID().uuidString.lowercased()
    let createdAt = nowMs()
    let nonceData = try randomBytes(count: 12)
    let nonce = try AES.GCM.Nonce(data: nonceData)
    let aad = messageAAD(messageId: messageId, senderId: senderId, recipientId: recipientId, createdAt: createdAt)
    let box = try AES.GCM.seal(payload, using: key, nonce: nonce, authenticating: aad)
    var ciphertext = box.ciphertext
    ciphertext.append(box.tag)
    return EncryptedMessage(
      messageId: messageId,
      senderId: senderId,
      recipientId: recipientId,
      createdAt: createdAt,
      nonce: nonceData,
      ciphertext: ciphertext
    )
  }

  static func decryptText(key: SymmetricKey, message: EncryptedMessage) throws -> String {
    guard message.nonce.count == 12, message.ciphertext.count >= 16 else {
      throw TelemetryNativeError.message("Malformed AES-GCM frame")
    }
    let nonce = try AES.GCM.Nonce(data: message.nonce)
    let cipher = Data(message.ciphertext.dropLast(16))
    let tag = Data(message.ciphertext.suffix(16))
    let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipher, tag: tag)
    let aad = messageAAD(messageId: message.messageId, senderId: message.senderId, recipientId: message.recipientId, createdAt: message.createdAt)
    let clear = try AES.GCM.open(box, using: key, authenticating: aad)
    guard let text = String(data: clear, encoding: .utf8) else {
      throw TelemetryNativeError.message("Message plaintext is not UTF-8")
    }
    return text
  }

  func createReceipt(for message: EncryptedMessage) throws -> DeliveryReceipt {
    let unsigned = DeliveryReceipt(
      messageId: message.messageId,
      senderId: message.senderId,
      recipientId: deviceId,
      receivedAt: Self.nowMs(),
      status: "DELIVERED",
      signature: Data()
    )
    let signature = try signingPrivate.signature(for: Self.receiptCanonical(unsigned))
    return DeliveryReceipt(
      messageId: unsigned.messageId,
      senderId: unsigned.senderId,
      recipientId: unsigned.recipientId,
      receivedAt: unsigned.receivedAt,
      status: unsigned.status,
      signature: signature
    )
  }

  static func verifyReceipt(_ receipt: DeliveryReceipt, peerSigningKey: Data) -> Bool {
    guard deviceId(peerSigningKey) == receipt.recipientId else { return false }
    do {
      let key = try Curve25519.Signing.PublicKey(rawRepresentation: peerSigningKey)
      return key.isValidSignature(receipt.signature, for: receiptCanonical(receipt))
    } catch {
      return false
    }
  }

  private static func helloCanonical(_ hello: SessionHello) -> Data {
    canonical([
      helloVersion,
      hello.deviceId,
      hex(hello.signingPublicKey),
      hex(hello.exchangePublicKey),
      hex(hello.ephemeralPublicKey),
      hex(hello.nonce),
      String(hello.issuedAt)
    ])
  }

  private static func receiptCanonical(_ receipt: DeliveryReceipt) -> Data {
    canonical([
      receiptContext,
      receipt.messageId,
      receipt.senderId,
      receipt.recipientId,
      String(receipt.receivedAt),
      receipt.status
    ])
  }

  private static func messageAAD(messageId: String, senderId: String, recipientId: String, createdAt: Int64) -> Data {
    canonical([messageContext, messageId, senderId, recipientId, String(createdAt)])
  }

  private static func canonical(_ parts: [String]) -> Data {
    Data(parts.joined(separator: "\u{001F}").utf8)
  }

  private static func hex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
  }

  private static func randomBytes(count: Int) throws -> Data {
    var data = Data(count: count)
    let status: OSStatus = data.withUnsafeMutableBytes { buffer in
      guard let base = buffer.baseAddress else { return errSecParam }
      return SecRandomCopyBytes(kSecRandomDefault, count, base)
    }
    guard status == errSecSuccess else {
      throw TelemetryNativeError.message("Secure random generator failed (\(status))")
    }
    return data
  }

  static func nowMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
  }
}

private enum FrameCodec {
  static func encodeHello(_ hello: SessionHello) throws -> Data {
    try json([
      "v": "m1b",
      "d": hello.deviceId,
      "s": b64(hello.signingPublicKey),
      "x": b64(hello.exchangePublicKey),
      "e": b64(hello.ephemeralPublicKey),
      "n": b64(hello.nonce),
      "t": hello.issuedAt,
      "g": b64(hello.signature)
    ])
  }

  static func decodeHello(_ data: Data) throws -> SessionHello {
    let value = try object(data)
    guard value["v"] as? String == "m1b",
          let d = value["d"] as? String,
          let s = value["s"] as? String,
          let x = value["x"] as? String,
          let e = value["e"] as? String,
          let n = value["n"] as? String,
          let t = (value["t"] as? NSNumber)?.int64Value,
          let g = value["g"] as? String else {
      throw TelemetryNativeError.message("Malformed hello frame")
    }
    return SessionHello(
      deviceId: d,
      signingPublicKey: try unb64(s),
      exchangePublicKey: try unb64(x),
      ephemeralPublicKey: try unb64(e),
      nonce: try unb64(n),
      issuedAt: t,
      signature: try unb64(g)
    )
  }

  static func encodeMessage(_ message: EncryptedMessage) throws -> Data {
    try json([
      "v": "m1b",
      "i": message.messageId,
      "s": message.senderId,
      "r": message.recipientId,
      "t": message.createdAt,
      "n": b64(message.nonce),
      "c": b64(message.ciphertext)
    ])
  }

  static func decodeMessage(_ data: Data) throws -> EncryptedMessage {
    let value = try object(data)
    guard value["v"] as? String == "m1b",
          let i = value["i"] as? String,
          let s = value["s"] as? String,
          let r = value["r"] as? String,
          let t = (value["t"] as? NSNumber)?.int64Value,
          let n = value["n"] as? String,
          let c = value["c"] as? String else {
      throw TelemetryNativeError.message("Malformed message frame")
    }
    return EncryptedMessage(
      messageId: i,
      senderId: s,
      recipientId: r,
      createdAt: t,
      nonce: try unb64(n),
      ciphertext: try unb64(c)
    )
  }

  static func encodeReceipt(_ receipt: DeliveryReceipt) throws -> Data {
    try json([
      "v": "m1b",
      "i": receipt.messageId,
      "s": receipt.senderId,
      "r": receipt.recipientId,
      "t": receipt.receivedAt,
      "q": receipt.status,
      "g": b64(receipt.signature)
    ])
  }

  static func decodeReceipt(_ data: Data) throws -> DeliveryReceipt {
    let value = try object(data)
    guard value["v"] as? String == "m1b",
          let i = value["i"] as? String,
          let s = value["s"] as? String,
          let r = value["r"] as? String,
          let t = (value["t"] as? NSNumber)?.int64Value,
          let q = value["q"] as? String,
          let g = value["g"] as? String else {
      throw TelemetryNativeError.message("Malformed receipt frame")
    }
    return DeliveryReceipt(
      messageId: i,
      senderId: s,
      recipientId: r,
      receivedAt: t,
      status: q,
      signature: try unb64(g)
    )
  }

  private static func json(_ value: [String: Any]) throws -> Data {
    try JSONSerialization.data(withJSONObject: value, options: [])
  }

  private static func object(_ data: Data) throws -> [String: Any] {
    guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw TelemetryNativeError.message("Telemetry frame is not a JSON object")
    }
    return value
  }

  private static func b64(_ data: Data) -> String {
    data.base64EncodedString()
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }

  private static func unb64(_ text: String) throws -> Data {
    var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while value.count % 4 != 0 { value.append("=") }
    guard let data = Data(base64Encoded: value) else {
      throw TelemetryNativeError.message("Invalid base64url")
    }
    return data
  }

  static func b64Public(_ data: Data) -> String { b64(data) }
}

private final class TrustStore {
  private let defaults = UserDefaults.standard
  private let prefix = "telemetry.trust.v1."

  func verify(_ hello: SessionHello) {
    let value = [
      FrameCodec.b64Public(hello.signingPublicKey),
      FrameCodec.b64Public(hello.exchangePublicKey),
      String(TelemetryCryptoEngine.nowMs())
    ].joined(separator: "|")
    defaults.set(value, forKey: prefix + hello.deviceId)
  }

  func isVerified(_ hello: SessionHello) -> Bool {
    guard let raw = defaults.string(forKey: prefix + hello.deviceId) else { return false }
    let parts = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 3 else { return false }
    return parts[0] == FrameCodec.b64Public(hello.signingPublicKey)
      && parts[1] == FrameCodec.b64Public(hello.exchangePublicKey)
  }
}

private final class ReplayWindow {
  private var seen: [String: Int64] = [:]
  private let ttlMs: Int64 = 24 * 60 * 60 * 1000
  private let maxEntries = 1024

  func accept(_ messageId: String) -> Bool {
    let now = TelemetryCryptoEngine.nowMs()
    seen = seen.filter { now - $0.value <= ttlMs }
    guard seen[messageId] == nil else { return false }
    seen[messageId] = now
    if seen.count > maxEntries,
       let oldest = seen.min(by: { $0.value < $1.value })?.key {
      seen.removeValue(forKey: oldest)
    }
    return true
  }
}

private final class TelemetryIosCore: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate {
  var emitter: ((String, [String: Any]) -> Void)?

  private let crypto: TelemetryCryptoEngine
  private let trust = TrustStore()
  private let replay = ReplayWindow()
  private var centralManager: CBCentralManager?
  private var peripheralManager: CBPeripheralManager?
  private var desiredRunning = false
  private var gattInstalled = false
  private var discovered: [String: CBPeripheral] = [:]
  private var characteristics: [String: [CBUUID: CBCharacteristic]] = [:]
  private var sessions: [String: PeerSession] = [:]
  private var receipts: [String: Data] = [:]

  override init() {
    do {
      crypto = try TelemetryCryptoEngine()
    } catch {
      fatalError("Telemetry identity initialization failed: \(error)")
    }
    super.init()
  }

  func identityPayload() -> [String: Any] {
    [
      "deviceId": crypto.deviceId,
      "signingPublicKey": FrameCodec.b64Public(crypto.signingPublicKey),
      "exchangePublicKey": FrameCodec.b64Public(crypto.exchangePublicKey)
    ]
  }

  func capabilitiesPayload() -> [String: Any] {
    [
      "bluetooth": true,
      "wifiAware": false,
      "wifiAwareReason": "M1 uses CoreBluetooth GATT. Wi-Fi Aware is the M1C transport upgrade for iOS 26+ supported devices.",
      "platform": "ios"
    ]
  }

  func start() {
    desiredRunning = true
    emit("onState", ["state": "starting", "detail": "CoreBluetooth"])
    if centralManager == nil {
      centralManager = CBCentralManager(delegate: self, queue: .main)
    }
    if peripheralManager == nil {
      peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }
    startCentralIfReady()
    startPeripheralIfReady()
  }

  func stop() {
    desiredRunning = false
    centralManager?.stopScan()
    for peripheral in discovered.values where peripheral.state == .connected || peripheral.state == .connecting {
      centralManager?.cancelPeripheralConnection(peripheral)
    }
    peripheralManager?.stopAdvertising()
    peripheralManager?.removeAllServices()
    gattInstalled = false
    characteristics.removeAll()
    sessions.removeAll()
    receipts.removeAll()
    emit("onState", ["state": "stopped"])
  }

  func connect(peerId: String) throws {
    guard desiredRunning else { throw TelemetryNativeError.message("Start Offline first") }
    guard let peripheral = discovered[peerId] else {
      throw TelemetryNativeError.message("Nearby peer is no longer available")
    }
    _ = try ensureSession(peerId)
    emit("onState", ["state": "connecting", "detail": peerId])
    centralManager?.connect(peripheral, options: nil)
  }

  func trustPeer(deviceId: String) -> Bool {
    guard let pair = sessions.first(where: { $0.value.remoteHello?.deviceId == deviceId }),
          let remote = pair.value.remoteHello else { return false }
    trust.verify(remote)
    emit("onTrusted", ["peerId": pair.key, "deviceId": deviceId])
    return true
  }

  func sendText(peerId: String, text: String) throws -> String {
    guard let peripheral = discovered[peerId], peripheral.state == .connected else {
      throw TelemetryNativeError.message("Peer BLE connection is not active")
    }
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote) else {
      throw TelemetryNativeError.message("Compare and trust the safety code first")
    }
    guard let characteristic = characteristics[peerId]?[messageUUID] else {
      throw TelemetryNativeError.message("Telemetry message characteristic is unavailable")
    }

    let message = try TelemetryCryptoEngine.encryptText(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      text: text
    )
    let frame = try FrameCodec.encodeMessage(message)
    guard frame.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
      throw TelemetryNativeError.message("Encrypted frame exceeds current BLE write size")
    }
    session.lastOutboundMessageId = message.messageId
    peripheral.writeValue(frame, for: characteristic, type: .withResponse)
    return message.messageId
  }

  private func startCentralIfReady() {
    guard desiredRunning, centralManager?.state == .poweredOn else { return }
    centralManager?.scanForPeripherals(
      withServices: [serviceUUID],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
    )
    emit("onState", ["state": "scanning", "detail": "advertising + scanning"])
  }

  private func startPeripheralIfReady() {
    guard desiredRunning,
          let manager = peripheralManager,
          manager.state == .poweredOn else { return }

    if !gattInstalled {
      let hello = CBMutableCharacteristic(
        type: helloUUID,
        properties: [.read, .write],
        value: nil,
        permissions: [.readable, .writeable]
      )
      let message = CBMutableCharacteristic(
        type: messageUUID,
        properties: [.write],
        value: nil,
        permissions: [.writeable]
      )
      let receipt = CBMutableCharacteristic(
        type: receiptUUID,
        properties: [.read],
        value: nil,
        permissions: [.readable]
      )
      let service = CBMutableService(type: serviceUUID, primary: true)
      service.characteristics = [hello, message, receipt]
      manager.add(service)
      gattInstalled = true
    } else if !manager.isAdvertising {
      manager.startAdvertising([
        CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
        CBAdvertisementDataLocalNameKey: "Telemetry"
      ])
    }
  }

  private func ensureSession(_ peerId: String) throws -> PeerSession {
    if let session = sessions[peerId] { return session }
    let ephemeral = Curve25519.KeyAgreement.PrivateKey()
    let hello = try crypto.createHello(ephemeral: ephemeral)
    let session = PeerSession(peerId: peerId, ephemeralPrivate: ephemeral, localHello: hello)
    sessions[peerId] = session
    return session
  }

  private func acceptHello(peerId: String, frame: Data) throws {
    let remote = try FrameCodec.decodeHello(frame)
    guard TelemetryCryptoEngine.verifyHello(remote) else {
      throw TelemetryNativeError.message("Invalid signed peer hello")
    }
    guard remote.deviceId != crypto.deviceId else {
      throw TelemetryNativeError.message("Self connection rejected")
    }

    let session = try ensureSession(peerId)
    session.remoteHello = remote
    session.sessionKey = try TelemetryCryptoEngine.deriveSessionKey(
      localSession: session,
      remoteHello: remote
    )

    let code = TelemetryCryptoEngine.safetyCode(session.localHello, remote)
    if trust.isVerified(remote) {
      emit("onTrusted", ["peerId": peerId, "deviceId": remote.deviceId])
    } else {
      emit("onVerification", [
        "peerId": peerId,
        "deviceId": remote.deviceId,
        "safetyCode": code
      ])
    }
  }

  private func acceptMessage(peerId: String, frame: Data) throws {
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Unknown secure peer session")
    }
    guard trust.isVerified(remote) else {
      throw TelemetryNativeError.message("Peer is not trusted")
    }

    let message = try FrameCodec.decodeMessage(frame)
    guard message.senderId == remote.deviceId else {
      throw TelemetryNativeError.message("Sender identity mismatch")
    }
    guard message.recipientId == crypto.deviceId else {
      throw TelemetryNativeError.message("Wrong message recipient")
    }
    guard replay.accept(message.messageId) else {
      throw TelemetryNativeError.message("Duplicate message rejected")
    }

    let clear = try TelemetryCryptoEngine.decryptText(key: key, message: message)
    receipts[peerId] = try FrameCodec.encodeReceipt(crypto.createReceipt(for: message))
    emit("onMessage", [
      "peerId": peerId,
      "deviceId": remote.deviceId,
      "messageId": message.messageId,
      "text": clear
    ])
  }

  private func acceptReceipt(peerId: String, frame: Data) throws {
    guard !frame.isEmpty else {
      throw TelemetryNativeError.message("Empty delivery receipt")
    }
    guard let session = sessions[peerId],
          let remote = session.remoteHello else {
      throw TelemetryNativeError.message("Unknown peer session")
    }

    let receipt = try FrameCodec.decodeReceipt(frame)
    guard receipt.messageId == session.lastOutboundMessageId else {
      throw TelemetryNativeError.message("Receipt does not match outbound message")
    }
    guard TelemetryCryptoEngine.verifyReceipt(receipt, peerSigningKey: remote.signingPublicKey) else {
      throw TelemetryNativeError.message("Invalid signed delivery receipt")
    }

    session.lastOutboundMessageId = nil
    emit("onDelivery", ["peerId": peerId, "messageId": receipt.messageId])
  }

  private func emit(_ name: String, _ payload: [String: Any]) {
    emitter?(name, payload)
  }

  private func emitError(_ error: Error) {
    emit("onError", ["message": error.localizedDescription])
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    switch central.state {
    case .poweredOn:
      startCentralIfReady()
    case .poweredOff:
      emit("onState", ["state": "bluetooth-off", "detail": "Turn Bluetooth on"])
    case .unauthorized:
      emit("onError", ["message": "Bluetooth permission is required"])
    default:
      break
    }
  }

  func centralManager(
    _ central: CBCentralManager,
    didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    let peerId = peripheral.identifier.uuidString
    discovered[peerId] = peripheral
    let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
    emit("onPeerSeen", [
      "peerId": peerId,
      "name": advertisedName ?? peripheral.name ?? "Telemetry device",
      "rssi": RSSI.intValue
    ])
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    let peerId = peripheral.identifier.uuidString
    peripheral.delegate = self
    emit("onState", ["state": "connected", "detail": peerId])
    peripheral.discoverServices([serviceUUID])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    emit("onError", ["message": error?.localizedDescription ?? "BLE connection failed"])
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    characteristics.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    emit("onState", ["state": "disconnected", "detail": peerId])
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    if let error {
      emitError(error)
      return
    }
    for service in peripheral.services ?? [] where service.uuid == serviceUUID {
      peripheral.discoverCharacteristics([helloUUID, messageUUID, receiptUUID], for: service)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    if let error {
      emitError(error)
      return
    }

    let peerId = peripheral.identifier.uuidString
    var map: [CBUUID: CBCharacteristic] = [:]
    for item in service.characteristics ?? [] {
      map[item.uuid] = item
    }
    characteristics[peerId] = map

    guard let helloCharacteristic = map[helloUUID] else {
      emit("onError", ["message": "Nearby device is not a Telemetry M1B peer"])
      return
    }

    do {
      let session = try ensureSession(peerId)
      let frame = try FrameCodec.encodeHello(session.localHello)
      guard frame.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
        throw TelemetryNativeError.message("Signed hello exceeds current BLE write size")
      }
      peripheral.writeValue(frame, for: helloCharacteristic, type: .withResponse)
    } catch {
      emitError(error)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    if let error {
      emitError(error)
      return
    }
    let map = characteristics[peripheral.identifier.uuidString]
    if characteristic.uuid == helloUUID, let hello = map?[helloUUID] {
      peripheral.readValue(for: hello)
    } else if characteristic.uuid == messageUUID, let receipt = map?[receiptUUID] {
      peripheral.readValue(for: receipt)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    if let error {
      emitError(error)
      return
    }
    guard let data = characteristic.value else { return }
    do {
      if characteristic.uuid == helloUUID {
        try acceptHello(peerId: peripheral.identifier.uuidString, frame: data)
      } else if characteristic.uuid == receiptUUID {
        try acceptReceipt(peerId: peripheral.identifier.uuidString, frame: data)
      }
    } catch {
      emitError(error)
    }
  }

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    switch peripheral.state {
    case .poweredOn:
      startPeripheralIfReady()
    case .poweredOff:
      emit("onState", ["state": "bluetooth-off", "detail": "Turn Bluetooth on"])
    case .unauthorized:
      emit("onError", ["message": "Bluetooth permission is required"])
    default:
      break
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
    if let error {
      emitError(error)
      return
    }
    guard desiredRunning, !peripheral.isAdvertising else { return }
    peripheral.startAdvertising([
      CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
      CBAdvertisementDataLocalNameKey: "Telemetry"
    ])
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    if let error {
      emitError(error)
      return
    }
    emit("onState", ["state": "offline", "detail": "advertising + scanning"])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    let peerId = request.central.identifier.uuidString
    do {
      let value: Data
      if request.characteristic.uuid == helloUUID {
        value = try FrameCodec.encodeHello(try ensureSession(peerId).localHello)
      } else if request.characteristic.uuid == receiptUUID {
        value = receipts[peerId] ?? Data()
      } else {
        peripheral.respond(to: request, withResult: .requestNotSupported)
        return
      }

      guard request.offset <= value.count else {
        peripheral.respond(to: request, withResult: .invalidOffset)
        return
      }
      request.value = value.subdata(in: request.offset..<value.count)
      peripheral.respond(to: request, withResult: .success)
    } catch {
      emitError(error)
      peripheral.respond(to: request, withResult: .unlikelyError)
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    guard let first = requests.first else { return }
    var result: CBATTError.Code = .success

    do {
      for request in requests {
        guard request.offset == 0, let value = request.value else {
          throw TelemetryNativeError.message("Unsupported prepared/offset BLE write")
        }
        let peerId = request.central.identifier.uuidString
        if request.characteristic.uuid == helloUUID {
          try acceptHello(peerId: peerId, frame: value)
        } else if request.characteristic.uuid == messageUUID {
          try acceptMessage(peerId: peerId, frame: value)
        } else {
          result = .requestNotSupported
        }
      }
    } catch {
      result = .unlikelyError
      emitError(error)
    }

    peripheral.respond(to: first, withResult: result)
  }
}

public class TelemetryIosNativeModule: Module {
  private let core = TelemetryIosCore()

  public func definition() -> ModuleDefinition {
    Name("TelemetryIosNative")

    Events(
      "onState",
      "onPeerSeen",
      "onVerification",
      "onTrusted",
      "onMessage",
      "onDelivery",
      "onError"
    )

    OnCreate {
      self.core.emitter = { [weak self] name, payload in
        self?.sendEvent(name, payload)
      }
    }

    OnDestroy {
      self.core.stop()
      self.core.emitter = nil
    }

    Function("getIdentity") {
      self.core.identityPayload()
    }

    Function("getCapabilities") {
      self.core.capabilitiesPayload()
    }

    AsyncFunction("startOffline") {
      self.core.start()
    }.runOnQueue(.main)

    AsyncFunction("stopOffline") {
      self.core.stop()
    }.runOnQueue(.main)

    AsyncFunction("connect") { (peerId: String) in
      try self.core.connect(peerId: peerId)
    }.runOnQueue(.main)

    AsyncFunction("trustPeer") { (deviceId: String) in
      self.core.trustPeer(deviceId: deviceId)
    }.runOnQueue(.main)

    AsyncFunction("sendText") { (peerId: String, text: String) in
      try self.core.sendText(peerId: peerId, text: text)
    }.runOnQueue(.main)
  }
}
