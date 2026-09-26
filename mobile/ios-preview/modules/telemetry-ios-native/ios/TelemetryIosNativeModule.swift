import ExpoModulesCore
import Foundation
import CoreBluetooth
import CryptoKit
import Security
import UIKit
import UserNotifications

private let serviceUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D59")
private let helloUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D01")
private let messageUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D02")
private let receiptUUID = CBUUID(string: "F0A0C0DE-7E1E-4E7F-9A11-54454C454D03")
private let bleMediaHeaderBytes = 49
private let bleMediaMaxFrameBytes = 2 * 1024 * 1024
private let bleMediaAssemblyTtlMs: Int64 = 90_000

private let helloVersion = "telemetry/m1b/hello"
private let sessionContext = "telemetry/m1b/session"
private let messageContext = "telemetry/m1b/message"
private let receiptContext = "telemetry/m1b/receipt"
private let maxClockSkewMs: Int64 = 15 * 60 * 1000
private let maxTextBytes = 160
private let profileControlPrefix = "\u{2063}TLM_PROFILE:"
private let callControlPrefix = "\u{2063}TLM_CALL:"

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
  var lastOutboundFrame: Data?

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

  static func encryptPayload(key: SymmetricKey, senderId: String, recipientId: String, payload: Data, messageId: String = UUID().uuidString.lowercased()) throws -> EncryptedMessage {
    guard !payload.isEmpty else { throw TelemetryNativeError.message("Encrypted payload cannot be empty") }
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

  static func encryptText(key: SymmetricKey, senderId: String, recipientId: String, text: String, messageId: String = UUID().uuidString.lowercased()) throws -> EncryptedMessage {
    guard let payload = text.data(using: .utf8), !payload.isEmpty, payload.count <= maxTextBytes else {
      throw TelemetryNativeError.message("M1B text must be 1-\(maxTextBytes) UTF-8 bytes")
    }
    return try encryptPayload(key: key, senderId: senderId, recipientId: recipientId, payload: payload, messageId: messageId)
  }

  static func decryptPayload(key: SymmetricKey, message: EncryptedMessage) throws -> Data {
    guard message.nonce.count == 12, message.ciphertext.count >= 16 else {
      throw TelemetryNativeError.message("Malformed AES-GCM frame")
    }
    let nonce = try AES.GCM.Nonce(data: message.nonce)
    let cipher = Data(message.ciphertext.dropLast(16))
    let tag = Data(message.ciphertext.suffix(16))
    let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipher, tag: tag)
    let aad = messageAAD(messageId: message.messageId, senderId: message.senderId, recipientId: message.recipientId, createdAt: message.createdAt)
    return try AES.GCM.open(box, using: key, authenticating: aad)
  }

  static func decryptText(key: SymmetricKey, message: EncryptedMessage) throws -> String {
    let clear = try decryptPayload(key: key, message: message)
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

  func trustedKeyMaterial(deviceId: String) -> (signing: Data, exchange: Data)? {
    guard let raw = defaults.string(forKey: prefix + deviceId) else { return nil }
    let parts = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 3,
          let signing = Self.decodeBase64Url(parts[0]),
          let exchange = Self.decodeBase64Url(parts[1]),
          signing.count == 32, exchange.count == 32 else { return nil }
    return (signing, exchange)
  }

  private static func decodeBase64Url(_ text: String) -> Data? {
    var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while value.count % 4 != 0 { value.append("=") }
    return Data(base64Encoded: value)
  }
}


private struct LocalContactRecord: Codable {
  var deviceId: String
  var peerId: String
  var alias: String
  var updatedAt: Int64
  var unreadCount: Int? = nil
  var profileDisplayName: String? = nil
  var profileTemplateId: String? = nil
  var profilePhotoUri: String? = nil
}

private struct LocalProfileRecord: Codable {
  var displayName: String
  var about: String
  var photoUri: String? = nil
  var templateId: String? = nil
  var updatedAt: Int64
}

private struct LocalMessageRecord: Codable {
  var id: String
  var peerDeviceId: String
  var text: String
  var mine: Bool
  var timestamp: Int64
  var delivered: Bool
  var attemptCount: Int? = nil
  var nextAttemptAt: Int64? = nil
  var lastError: String? = nil
}

private struct LocalMediaRecord: Codable {
  var assetId: String
  var peerDeviceId: String
  var kind: String
  var fileName: String
  var byteLength: Int64
  var state: String
  var totalChunks: Int
  var acknowledgedChunks: Int? = nil
  var receivedChunks: Int? = nil
  var localUri: String? = nil
  var sha256: String? = nil
  var verified: Bool? = nil
  var message: String? = nil
  var updatedAt: Int64
}

private struct LocalVaultState: Codable {
  var version: Int = 1
  var profile: LocalProfileRecord? = nil
  var appearance: String? = nil
  var contacts: [LocalContactRecord] = []
  var messages: [LocalMessageRecord] = []
  var media: [LocalMediaRecord]? = nil
}

private final class LocalVault {
  private let key: SymmetricKey
  private let fileURL: URL
  private let directoryURL: URL
  private var state = LocalVaultState()

  init() throws {
    let account = "local-vault-aes-v1"
    if let raw = KeychainStore.load(account: account), raw.count == 32 {
      key = SymmetricKey(data: raw)
    } else {
      let generated = SymmetricKey(size: .bits256)
      let raw = generated.withUnsafeBytes { Data($0) }
      try KeychainStore.save(account: account, data: raw)
      key = generated
    }

    let base = try FileManager.default.url(
      for: .applicationSupportDirectory,
      in: .userDomainMask,
      appropriateFor: nil,
      create: true
    )
    let directory = base.appendingPathComponent("Telemetry", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    directoryURL = directory
    fileURL = directory.appendingPathComponent("local-vault-v1.bin")
    try load()
  }

  func payload() -> String {
    var payloadState = state
    if var media = payloadState.media {
      var repaired = false
      for index in media.indices {
        let existingValid: Bool = {
          guard let uri = media[index].localUri, let url = URL(string: uri), url.isFileURL else { return false }
          return FileManager.default.fileExists(atPath: url.path)
        }()
        if existingValid { continue }
        let safeName = URL(fileURLWithPath: media[index].fileName).lastPathComponent.isEmpty
          ? "attachment.bin" : URL(fileURLWithPath: media[index].fileName).lastPathComponent
        let root = directoryURL.appendingPathComponent("media-v1", isDirectory: true)
        let candidates = [
          root.appendingPathComponent("outgoing/\(media[index].assetId)/preview-\(safeName)"),
          root.appendingPathComponent("open/\(media[index].assetId)/\(safeName)")
        ]
        if let found = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
          media[index].localUri = found.absoluteString
          repaired = true
        } else if media[index].localUri != nil {
          media[index].localUri = nil
          repaired = true
        }
      }
      payloadState.media = media
      if repaired {
        state.media = media
        try? save()
      }
    }
    let encoder = JSONEncoder()
    guard let data = try? encoder.encode(payloadState) else { return "{\"version\":1,\"contacts\":[],\"messages\":[]}" }
    return String(data: data, encoding: .utf8) ?? "{\"version\":1,\"contacts\":[],\"messages\":[]}"
  }

  func profile() -> LocalProfileRecord {
    state.profile ?? LocalProfileRecord(displayName: "", about: "", updatedAt: 0)
  }

  func appearance() -> String {
    let value = state.appearance ?? "dark"
    return ["system", "light", "dark"].contains(value) ? value : "dark"
  }

  func setAppearance(_ mode: String) -> String {
    guard ["system", "light", "dark"].contains(mode) else { return appearance() }
    state.appearance = mode
    try? save()
    return mode
  }

  func setProfile(displayName: String, about: String, sourcePhotoUri: String?, templateId: String?) -> LocalProfileRecord {
    let cleanName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(48))
    let cleanAbout = String(about.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
    let allowedTemplates = Set((1...6).map { "avatar-\($0)" })
    var storedPhotoUri: String? = state.profile?.photoUri
    var storedTemplateId: String? = state.profile?.templateId

    if let templateId, allowedTemplates.contains(templateId) {
      storedTemplateId = templateId
      storedPhotoUri = nil
    } else if let sourcePhotoUri, !sourcePhotoUri.isEmpty, let sourceURL = URL(string: sourcePhotoUri), sourceURL.isFileURL {
      let profileDir = directoryURL.appendingPathComponent("profile", isDirectory: true)
      try? FileManager.default.createDirectory(at: profileDir, withIntermediateDirectories: true)
      let destination = profileDir.appendingPathComponent("avatar.jpg")
      if sourceURL.standardizedFileURL != destination.standardizedFileURL {
        try? FileManager.default.removeItem(at: destination)
        if (try? FileManager.default.copyItem(at: sourceURL, to: destination)) != nil {
          storedPhotoUri = destination.absoluteString
          storedTemplateId = nil
        }
      } else {
        storedPhotoUri = destination.absoluteString
        storedTemplateId = nil
      }
    }

    let record = LocalProfileRecord(
      displayName: cleanName,
      about: cleanAbout,
      photoUri: storedPhotoUri,
      templateId: storedTemplateId,
      updatedAt: TelemetryCryptoEngine.nowMs()
    )
    state.profile = record
    try? save()
    return record
  }

  func alias(for deviceId: String) -> String? {
    state.contacts.first(where: { $0.deviceId == deviceId })?.alias.nilIfBlank
  }

  func upsertContact(deviceId: String, peerId: String, alias: String? = nil) {
    let now = TelemetryCryptoEngine.nowMs()
    if let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }) {
      state.contacts[index].peerId = peerId
      if let alias, !alias.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        state.contacts[index].alias = alias.trimmingCharacters(in: .whitespacesAndNewlines)
      }
      state.contacts[index].updatedAt = now
    } else {
      state.contacts.append(LocalContactRecord(
        deviceId: deviceId,
        peerId: peerId,
        alias: alias?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
        updatedAt: now
      ))
    }
    try? save()
  }

  func setAlias(deviceId: String, alias: String) -> Bool {
    guard let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }) else { return false }
    state.contacts[index].alias = alias.trimmingCharacters(in: .whitespacesAndNewlines)
    state.contacts[index].updatedAt = TelemetryCryptoEngine.nowMs()
    try? save()
    return true
  }

  func updateContactProfile(deviceId: String, displayName: String?, templateId: String?) -> Bool {
    guard let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }) else { return false }
    let cleanName = String((displayName ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(48))
    let allowedTemplates = Set((1...6).map { "avatar-\($0)" })
    state.contacts[index].profileDisplayName = cleanName.nilIfBlank
    state.contacts[index].profileTemplateId = templateId.flatMap { allowedTemplates.contains($0) ? $0 : nil }
    if state.contacts[index].profileTemplateId != nil {
      state.contacts[index].profilePhotoUri = nil
    }
    state.contacts[index].updatedAt = TelemetryCryptoEngine.nowMs()
    try? save()
    return true
  }

  func setContactProfilePhoto(deviceId: String, photoUri: String) -> Bool {
    guard let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }),
          let sourceURL = URL(string: photoUri), sourceURL.isFileURL else { return false }
    let profileDir = directoryURL.appendingPathComponent("peer-profiles", isDirectory: true)
    try? FileManager.default.createDirectory(at: profileDir, withIntermediateDirectories: true)
    let safeName = deviceId.data(using: .utf8)?.base64EncodedString()
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "+", with: "-") ?? UUID().uuidString
    let destination = profileDir.appendingPathComponent("\(safeName).jpg")
    do {
      if sourceURL.standardizedFileURL != destination.standardizedFileURL {
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: sourceURL, to: destination)
      }
      state.contacts[index].profilePhotoUri = destination.absoluteString
      state.contacts[index].profileTemplateId = nil
      state.contacts[index].updatedAt = TelemetryCryptoEngine.nowMs()
      try? save()
      return true
    } catch {
      return false
    }
  }

  func incrementUnread(deviceId: String) {
    guard let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }) else { return }
    state.contacts[index].unreadCount = (state.contacts[index].unreadCount ?? 0) + 1
    state.contacts[index].updatedAt = TelemetryCryptoEngine.nowMs()
    try? save()
  }

  func markConversationRead(deviceId: String) -> Bool {
    guard let index = state.contacts.firstIndex(where: { $0.deviceId == deviceId }) else { return false }
    state.contacts[index].unreadCount = 0
    state.contacts[index].updatedAt = TelemetryCryptoEngine.nowMs()
    try? save()
    return true
  }

  func totalUnreadCount() -> Int {
    state.contacts.reduce(0) { $0 + ($1.unreadCount ?? 0) }
  }

  func upsertMediaEvent(_ payload: [String: Any]) {
    guard let assetId = payload["assetId"] as? String,
          let peerDeviceId = payload["peerDeviceId"] as? String,
          let kind = payload["kind"] as? String,
          let fileName = payload["fileName"] as? String,
          let stateName = payload["state"] as? String else { return }
    var media = state.media ?? []
    let now = TelemetryCryptoEngine.nowMs()
    let index = media.firstIndex(where: { $0.assetId == assetId })
    var record = index.map { media[$0] } ?? LocalMediaRecord(
      assetId: assetId, peerDeviceId: peerDeviceId, kind: kind, fileName: fileName,
      byteLength: (payload["byteLength"] as? NSNumber)?.int64Value ?? 0,
      state: stateName, totalChunks: (payload["totalChunks"] as? NSNumber)?.intValue ?? 1,
      updatedAt: now
    )
    record.peerDeviceId = peerDeviceId
    record.kind = kind
    record.fileName = fileName
    let terminalStates: Set<String> = ["outgoingComplete", "incomingReady"]
    if !terminalStates.contains(record.state) || terminalStates.contains(stateName) {
      record.state = stateName
    }
    record.byteLength = (payload["byteLength"] as? NSNumber)?.int64Value ?? record.byteLength
    record.totalChunks = (payload["totalChunks"] as? NSNumber)?.intValue ?? record.totalChunks
    record.acknowledgedChunks = (payload["acknowledgedChunks"] as? NSNumber)?.intValue ?? record.acknowledgedChunks
    record.receivedChunks = (payload["receivedChunks"] as? NSNumber)?.intValue ?? record.receivedChunks
    record.localUri = (payload["localUri"] as? String) ?? record.localUri
    record.sha256 = (payload["sha256"] as? String) ?? record.sha256
    record.verified = (payload["verified"] as? Bool) ?? record.verified
    if record.state == "outgoingComplete" || record.state == "incomingReady" { record.verified = true }
    record.message = (payload["message"] as? String) ?? record.message
    record.updatedAt = now
    if let index { media[index] = record } else { media.append(record) }
    if media.count > 1000 { media.removeFirst(media.count - 1000) }
    state.media = media
    try? save()
  }

  func hasContact(deviceId: String, peerId: String) -> Bool {
    state.contacts.contains(where: { $0.deviceId == deviceId && $0.peerId == peerId })
  }

  func hasAnyContact() -> Bool {
    !state.contacts.isEmpty
  }

  func trustedDeviceId(matchingFingerprint fingerprint: String) -> String? {
    let clean = fingerprint.lowercased()
    guard clean.count >= 8 else { return nil }
    return state.contacts.first(where: { contact in
      let hex = contact.deviceId.replacingOccurrences(of: "tlm:device:", with: "").lowercased()
      return hex.hasPrefix(clean)
    })?.deviceId
  }

  func enqueueOutgoing(peerDeviceId: String, text: String) -> String {
    let messageId = UUID().uuidString.lowercased()
    appendMessage(id: messageId, peerDeviceId: peerDeviceId, text: text, mine: true, delivered: false)
    return messageId
  }

  func message(id: String) -> LocalMessageRecord? {
    state.messages.first(where: { $0.id == id })
  }

  func containsIncomingMessage(id: String, peerDeviceId: String) -> Bool {
    state.messages.contains(where: { $0.id == id && !$0.mine && $0.peerDeviceId == peerDeviceId })
  }

  func markAttempt(messageId: String, error: String? = nil) {
    guard let index = state.messages.firstIndex(where: { $0.id == messageId && $0.mine && !$0.delivered }) else { return }
    let attempts = (state.messages[index].attemptCount ?? 0) + 1
    let delays: [Int64] = [1_000, 2_000, 5_000, 10_000, 30_000, 60_000]
    state.messages[index].attemptCount = attempts
    state.messages[index].nextAttemptAt = TelemetryCryptoEngine.nowMs() + delays[min(attempts - 1, delays.count - 1)]
    state.messages[index].lastError = error
    try? save()
  }

  func appendMessage(id: String, peerDeviceId: String, text: String, mine: Bool, delivered: Bool) {
    if state.messages.contains(where: { $0.id == id }) { return }
    state.messages.append(LocalMessageRecord(
      id: id,
      peerDeviceId: peerDeviceId,
      text: text,
      mine: mine,
      timestamp: TelemetryCryptoEngine.nowMs(),
      delivered: delivered
    ))
    if state.messages.count > 2000 {
      state.messages.removeFirst(state.messages.count - 2000)
    }
    try? save()
  }

  func markDelivered(messageId: String) {
    guard let index = state.messages.firstIndex(where: { $0.id == messageId }) else { return }
    state.messages[index].delivered = true
    state.messages[index].nextAttemptAt = nil
    state.messages[index].lastError = nil
    try? save()
  }

  func reliabilityStats() -> [String: Any] {
    let grouped = Dictionary(grouping: state.messages, by: { $0.id })
    return [
      "messageRecords": state.messages.count,
      "pendingOutgoing": state.messages.filter { $0.mine && !$0.delivered }.count,
      "deliveredOutgoing": state.messages.filter { $0.mine && $0.delivered }.count,
      "incoming": state.messages.filter { !$0.mine }.count,
      "duplicateMessageIds": grouped.values.filter { $0.count > 1 }.count
    ]
  }

  private func load() throws {
    guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
    let combined = try Data(contentsOf: fileURL)
    guard !combined.isEmpty else { return }
    let box = try AES.GCM.SealedBox(combined: combined)
    let clear = try AES.GCM.open(box, using: key)
    state = try JSONDecoder().decode(LocalVaultState.self, from: clear)
    if let media = state.media {
      let clean = media.filter { $0.fileName != "__telemetry_media_probe.bin" }
      if clean.count != media.count {
        state.media = clean
        try? save()
      }
    }
  }

  private func save() throws {
    let clear = try JSONEncoder().encode(state)
    let sealed = try AES.GCM.seal(clear, using: key)
    guard let combined = sealed.combined else {
      throw TelemetryNativeError.message("Unable to encode local vault")
    }
    try combined.write(to: fileURL, options: .atomic)
  }
}

private extension String {
  var nilIfBlank: String? {
    let value = trimmingCharacters(in: .whitespacesAndNewlines)
    return value.isEmpty ? nil : value
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

private struct BleMediaAssembly {
  let fragmentCount: Int
  var fragments: [Int: Data]
  var byteCount: Int
  var updatedAt: Int64
}

private final class TelemetryIosCore: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate, UNUserNotificationCenterDelegate {
  private var emitter: ((String, [String: Any]) -> Void)?
  private var pendingNotificationOpenDeviceId: String?

  private let crypto: TelemetryCryptoEngine
  private let trust = TrustStore()
  private let replay = ReplayWindow()
  private let vault: LocalVault
  private var centralManager: CBCentralManager?
  private var peripheralManager: CBPeripheralManager?
  private var desiredRunning = false
  private var gattInstalled = false
  private var discovered: [String: CBPeripheral] = [:]
  private var characteristics: [String: [CBUUID: CBCharacteristic]] = [:]
  private var sessions: [String: PeerSession] = [:]
  private var receipts: [String: Data] = [:]
  private var messageServerCharacteristic: CBMutableCharacteristic?
  private var receiptServerCharacteristic: CBMutableCharacteristic?
  private var subscribedCentrals: [String: CBCentral] = [:]
  private var bleMediaAssemblies: [String: BleMediaAssembly] = [:]
  private var bleMediaResponseContinuations: [String: CheckedContinuation<Data, Error>] = [:]
  private var bleMediaWriteContinuations: [String: CheckedContinuation<Void, Error>] = [:]
  private var bleMediaActiveWriterPeerIds = Set<String>()
  private var reconnectWorkItems: [String: DispatchWorkItem] = [:]
  private var reconnectAttempts: [String: Int] = [:]
  private var interactivePeerIds = Set<String>()
  private var silentProbePeerIds = Set<String>()
  private var probeCooldownUntil: [String: Int64] = [:]
  private var availableWifiPeerDeviceIds = Set<String>()
  private var acceptedIncomingCount = 0
  private var duplicateFramesSuppressed = 0
  private var replayRejectedCount = 0
  private var deliveryReceiptsAccepted = 0
  private let notificationCenter = UNUserNotificationCenter.current()
  private var notificationPermissionRequested = false
  private var wifiFailureLaunchHookConsumed = false
  private var payloadProbeLaunchStarted = false
  private var mediaProbeTargetDeviceIds = Set<String>()
  private var callProbeTargetDeviceIds = Set<String>()
  private var lastNativeProfileSyncAt: [String: Int64] = [:]
  private lazy var wifiTransport: TelemetryWifiTransport = {
    let transport = TelemetryWifiTransport()
    transport.onLog = { [weak self] message in
      guard let self, self.reliabilityLoggingEnabled else { return }
      print("[TelemetryWiFi] \(message)")
    }
    transport.onPeerChange = { [weak self] deviceId, available in
      if self?.reliabilityLoggingEnabled == true {
        print("[TelemetryWiFi] peer=\(deviceId) available=\(available)")
      }
      DispatchQueue.main.async {
        self?.emit("onState", [
          "state": available ? "wifi-peer-available" : "wifi-peer-unavailable",
          "detail": deviceId
        ])
      }
      if available {
        self?.availableWifiPeerDeviceIds.insert(deviceId)
        self?.probeDiscoveredPeerForTrustedWifiDevice(deviceId)
        self?.scheduleMediaProbeIfRequested(deviceId: deviceId)
        self?.scheduleCallProbeIfRequested(deviceId: deviceId)
        Task { [weak self] in
          _ = await self?.mediaRuntime.resumePending(peerDeviceId: deviceId)
        }
      } else {
        self?.availableWifiPeerDeviceIds.remove(deviceId)
      }
    }
    transport.onFrame = { [weak self] deviceId, frame, completion in
      guard let self else {
        completion(.failure(TelemetryNativeError.message("Telemetry core unavailable")))
        return
      }
      if TelemetryMediaRuntime.handles(frame) {
        do {
          completion(.success(try self.mediaRuntime.handleIncoming(deviceId: deviceId, frame: frame)))
        } catch {
          completion(.failure(error))
        }
        return
      }
      DispatchQueue.main.async {
        do {
          guard let peerId = self.peerIdForDeviceId(deviceId) else {
            throw TelemetryNativeError.message("Wi-Fi peer has no trusted BLE session")
          }
          completion(.success(try self.acceptMessage(peerId: peerId, frame: frame)))
        } catch {
          completion(.failure(error))
        }
      }
    }
    return transport
  }()

  private lazy var mpcTransport: TelemetryMPCTransport = {
    let transport = TelemetryMPCTransport()
    transport.onLog = { [weak self] message in
      guard let self, self.reliabilityLoggingEnabled else { return }
      print("[TelemetryMPC] \(message)")
    }
    transport.onPeerChange = { [weak self] deviceId, available in
      guard let self else { return }
      if self.reliabilityLoggingEnabled {
        print("[TelemetryMPC] peer=\(deviceId) available=\(available)")
      }
      DispatchQueue.main.async {
        self.emit("onState", [
          "state": available ? "direct-peer-available" : "direct-peer-unavailable",
          "detail": deviceId
        ])
      }
      if available {
        self.scheduleMediaProbeIfRequested(deviceId: deviceId)
        self.scheduleCallProbeIfRequested(deviceId: deviceId)
        Task { [weak self] in
          _ = await self?.mediaRuntime.resumePending(peerDeviceId: deviceId)
        }
      }
    }
    transport.onFrame = { [weak self] deviceId, frame, completion in
      guard let self else {
        completion(.failure(TelemetryNativeError.message("Telemetry core unavailable")))
        return
      }
      if TelemetryMediaRuntime.handles(frame) {
        do {
          completion(.success(try self.mediaRuntime.handleIncoming(deviceId: deviceId, frame: frame)))
        } catch {
          completion(.failure(error))
        }
        return
      }
      DispatchQueue.main.async {
        do {
          guard let peerId = self.peerIdForDeviceId(deviceId) else {
            throw TelemetryNativeError.message("Direct peer has no trusted BLE session")
          }
          completion(.success(try self.acceptMessage(peerId: peerId, frame: frame)))
        } catch {
          completion(.failure(error))
        }
      }
    }
    return transport
  }()

  private var forceBleMediaForTest: Bool {
    ProcessInfo.processInfo.arguments.contains("--telemetry-media-force-ble")
  }

  private func bleMediaAvailable(deviceId: String) -> Bool {
    guard let peerId = peerIdForDeviceId(deviceId),
          let session = sessions[peerId],
          let remote = session.remoteHello,
          remote.deviceId == deviceId,
          trust.isVerified(remote) else { return false }
    if let peripheral = discovered[peerId],
       peripheral.state == .connected,
       characteristics[peerId]?[messageUUID] != nil {
      return true
    }
    return subscribedCentrals[peerId] != nil && messageServerCharacteristic != nil
  }

  private func mediaPathAvailable(deviceId: String) -> Bool {
    if forceBleMediaForTest { return bleMediaAvailable(deviceId: deviceId) }
    return wifiTransport.isAvailable(deviceId: deviceId) ||
      mpcTransport.isAvailable(deviceId: deviceId) ||
      bleMediaAvailable(deviceId: deviceId)
  }

  private func sendMediaFrame(deviceId: String, frame: Data) async throws -> Data {
    if !forceBleMediaForTest && wifiTransport.isAvailable(deviceId: deviceId) {
      do {
        let response = try await wifiTransport.send(deviceId: deviceId, frame: frame)
        if reliabilityLoggingEnabled { print("[TelemetryMediaPath] selected=wifi peer=\(deviceId) bytes=\(frame.count)") }
        return response
      } catch {
        if reliabilityLoggingEnabled { print("[TelemetryMediaPath] wifi-failed peer=\(deviceId) error=\(error.localizedDescription)") }
      }
    }
    if !forceBleMediaForTest && mpcTransport.isAvailable(deviceId: deviceId) {
      do {
        let response = try await mpcTransport.send(deviceId: deviceId, frame: frame)
        if reliabilityLoggingEnabled { print("[TelemetryMediaPath] selected=mpc peer=\(deviceId) bytes=\(frame.count)") }
        return response
      } catch {
        if reliabilityLoggingEnabled { print("[TelemetryMediaPath] mpc-failed peer=\(deviceId) error=\(error.localizedDescription)") }
      }
    }
    if bleMediaAvailable(deviceId: deviceId) {
      let response = try await sendBleMediaFrame(deviceId: deviceId, frame: frame)
      if reliabilityLoggingEnabled { print("[TelemetryMediaPath] selected=ble-fragmented peer=\(deviceId) bytes=\(frame.count)") }
      return response
    }
    throw TelemetryNativeError.message("No direct media path is currently available")
  }

  private func encodeBleMediaFragment(transactionId: String, isResponse: Bool, index: Int, count: Int, payload: Data) throws -> Data {
    let idData = Data(transactionId.utf8)
    guard idData.count == 36, index >= 0, count > 0, index < count, count <= 20_000 else {
      throw TelemetryNativeError.message("Invalid BLE media fragment metadata")
    }
    var packet = Data("TBM1".utf8)
    packet.append(isResponse ? 1 : 0)
    packet.append(idData)
    for value in [UInt32(index), UInt32(count)] {
      var big = value.bigEndian
      withUnsafeBytes(of: &big) { packet.append(contentsOf: $0) }
    }
    packet.append(payload)
    return packet
  }

  private func decodeBleMediaFragment(_ packet: Data) throws -> (String, Bool, Int, Int, Data) {
    guard packet.count >= bleMediaHeaderBytes,
          packet.prefix(4) == Data("TBM1".utf8),
          let transactionId = String(data: packet.subdata(in: 5..<41), encoding: .utf8),
          transactionId.count == 36 else {
      throw TelemetryNativeError.message("Malformed BLE media fragment")
    }
    func u32(_ offset: Int) -> UInt32 {
      (UInt32(packet[offset]) << 24) | (UInt32(packet[offset + 1]) << 16) |
        (UInt32(packet[offset + 2]) << 8) | UInt32(packet[offset + 3])
    }
    let index = Int(u32(41))
    let count = Int(u32(45))
    guard count > 0, count <= 20_000, index >= 0, index < count else {
      throw TelemetryNativeError.message("Invalid BLE media fragment range")
    }
    return (transactionId, packet[4] == 1, index, count, packet.subdata(in: bleMediaHeaderBytes..<packet.count))
  }

  private func isBleMediaPacket(_ data: Data) -> Bool {
    data.count >= 4 && data.prefix(4) == Data("TBM1".utf8)
  }

  @MainActor
  private func writeBleMediaPacket(
    peerId: String,
    peripheral: CBPeripheral,
    characteristic: CBCharacteristic,
    packet: Data
  ) async throws {
    guard bleMediaWriteContinuations[peerId] == nil else {
      throw TelemetryNativeError.message("BLE media write already in flight")
    }
    try await withCheckedThrowingContinuation { continuation in
      bleMediaWriteContinuations[peerId] = continuation
      peripheral.writeValue(packet, for: characteristic, type: .withResponse)
      DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
        guard let self, let pending = self.bleMediaWriteContinuations.removeValue(forKey: peerId) else { return }
        pending.resume(throwing: TelemetryNativeError.message("BLE media fragment write timed out"))
      }
    }
  }

  @MainActor
  private func transmitBleMediaPayload(peerId: String, transactionId: String, isResponse: Bool, payload: Data) async throws {
    guard payload.count <= bleMediaMaxFrameBytes else {
      throw TelemetryNativeError.message("BLE media frame exceeds fallback limit")
    }

    var waitSpins = 0
    while bleMediaActiveWriterPeerIds.contains(peerId) || sessions[peerId]?.lastOutboundMessageId != nil {
      try await Task.sleep(nanoseconds: 5_000_000)
      waitSpins += 1
      if waitSpins > 4_000 { throw TelemetryNativeError.message("BLE media writer wait timed out") }
    }
    bleMediaActiveWriterPeerIds.insert(peerId)
    defer { bleMediaActiveWriterPeerIds.remove(peerId) }

    let centralPeripheral = discovered[peerId]
    let centralCharacteristic = characteristics[peerId]?[messageUUID]
    let peripheralCentral = subscribedCentrals[peerId]
    let manager = peripheralManager
    let serverCharacteristic = messageServerCharacteristic

    let packetLimit: Int
    let useCentralWrite: Bool
    if let peripheral = centralPeripheral, peripheral.state == .connected, centralCharacteristic != nil {
      packetLimit = peripheral.maximumWriteValueLength(for: .withResponse)
      useCentralWrite = true
    } else if let central = peripheralCentral, manager != nil, serverCharacteristic != nil {
      packetLimit = central.maximumUpdateValueLength
      useCentralWrite = false
    } else {
      throw TelemetryNativeError.message("BLE media fallback has no active peer route")
    }
    guard packetLimit > bleMediaHeaderBytes else {
      throw TelemetryNativeError.message("BLE MTU is too small for media fallback")
    }
    let fragmentBytes = packetLimit - bleMediaHeaderBytes
    let count = max(1, Int(ceil(Double(payload.count) / Double(fragmentBytes))))
    guard count <= 20_000 else { throw TelemetryNativeError.message("BLE media frame requires too many fragments") }

    for index in 0..<count {
      let lower = index * fragmentBytes
      let upper = min(payload.count, lower + fragmentBytes)
      let body = payload.subdata(in: lower..<upper)
      let packet = try encodeBleMediaFragment(transactionId: transactionId, isResponse: isResponse, index: index, count: count, payload: body)
      if useCentralWrite {
        guard let peripheral = centralPeripheral, let characteristic = centralCharacteristic else {
          throw TelemetryNativeError.message("BLE media central route disappeared")
        }
        try await writeBleMediaPacket(peerId: peerId, peripheral: peripheral, characteristic: characteristic, packet: packet)
      } else {
        guard let central = peripheralCentral, let manager, let characteristic = serverCharacteristic else {
          throw TelemetryNativeError.message("BLE media peripheral route disappeared")
        }
        var spins = 0
        while !manager.updateValue(packet, for: characteristic, onSubscribedCentrals: [central]) {
          try await Task.sleep(nanoseconds: 5_000_000)
          spins += 1
          if spins > 4_000 { throw TelemetryNativeError.message("BLE media notify queue timed out") }
        }
      }
      if index % 64 == 63 { await Task.yield() }
    }
    if reliabilityLoggingEnabled {
      print("[TelemetryBLEMedia] tx=\(transactionId) response=\(isResponse) fragments=\(count) bytes=\(payload.count)")
    }
  }

  @MainActor
  private func sendBleMediaFrame(deviceId: String, frame: Data) async throws -> Data {
    guard let peerId = peerIdForDeviceId(deviceId), bleMediaAvailable(deviceId: deviceId) else {
      throw TelemetryNativeError.message("BLE media peer is unavailable")
    }
    let transactionId = UUID().uuidString.lowercased()
    return try await withCheckedThrowingContinuation { continuation in
      bleMediaResponseContinuations[transactionId] = continuation
      Task { @MainActor [weak self] in
        guard let self else { return }
        do {
          try await self.transmitBleMediaPayload(peerId: peerId, transactionId: transactionId, isResponse: false, payload: frame)
        } catch {
          if let pending = self.bleMediaResponseContinuations.removeValue(forKey: transactionId) {
            pending.resume(throwing: error)
          }
        }
      }
      DispatchQueue.main.asyncAfter(deadline: .now() + 75) { [weak self] in
        guard let self, let pending = self.bleMediaResponseContinuations.removeValue(forKey: transactionId) else { return }
        pending.resume(throwing: TelemetryNativeError.message("BLE media response timed out"))
      }
    }
  }

  private func handleBleMediaFragment(peerId: String, packet: Data) throws {
    let (transactionId, isResponse, index, count, body) = try decodeBleMediaFragment(packet)
    let now = TelemetryCryptoEngine.nowMs()
    bleMediaAssemblies = bleMediaAssemblies.filter { now - $0.value.updatedAt <= bleMediaAssemblyTtlMs }
    let key = "\(peerId)|\(transactionId)|\(isResponse ? 1 : 0)"
    var assembly = bleMediaAssemblies[key] ?? BleMediaAssembly(fragmentCount: count, fragments: [:], byteCount: 0, updatedAt: now)
    guard assembly.fragmentCount == count else { throw TelemetryNativeError.message("BLE media fragment count changed mid-frame") }
    if let existing = assembly.fragments[index] {
      guard existing == body else { throw TelemetryNativeError.message("BLE media duplicate fragment conflict") }
    } else {
      assembly.fragments[index] = body
      assembly.byteCount += body.count
    }
    guard assembly.byteCount <= bleMediaMaxFrameBytes else {
      bleMediaAssemblies.removeValue(forKey: key)
      throw TelemetryNativeError.message("BLE media reassembly exceeds fallback limit")
    }
    assembly.updatedAt = now
    bleMediaAssemblies[key] = assembly
    guard assembly.fragments.count == count else { return }
    var full = Data(capacity: assembly.byteCount)
    for partIndex in 0..<count {
      guard let part = assembly.fragments[partIndex] else { return }
      full.append(part)
    }
    bleMediaAssemblies.removeValue(forKey: key)

    if isResponse {
      if let pending = bleMediaResponseContinuations.removeValue(forKey: transactionId) {
        pending.resume(returning: full)
      }
      if reliabilityLoggingEnabled { print("[TelemetryBLEMedia] rx-response tx=\(transactionId) bytes=\(full.count)") }
      return
    }

    guard let session = sessions[peerId], let remote = session.remoteHello, trust.isVerified(remote) else {
      throw TelemetryNativeError.message("BLE media request came from an untrusted session")
    }
    let response = try mediaRuntime.handleIncoming(deviceId: remote.deviceId, frame: full)
    if reliabilityLoggingEnabled { print("[TelemetryBLEMedia] rx-request tx=\(transactionId) bytes=\(full.count)") }
    Task { @MainActor [weak self] in
      guard let self else { return }
      do {
        try await self.transmitBleMediaPayload(peerId: peerId, transactionId: transactionId, isResponse: true, payload: response)
      } catch {
        self.emit("onError", ["message": "BLE media response failed: \(error.localizedDescription)"])
      }
    }
  }

  private lazy var mediaRuntime: TelemetryMediaRuntime = {
    do {
      return try TelemetryMediaRuntime(
        localDeviceId: crypto.deviceId,
        localSigningPublicKey: crypto.signingPublicKey,
        sign: { [weak self] data in
          guard let self else { throw TelemetryNativeError.message("Telemetry core unavailable") }
          return try self.crypto.signingPrivate.signature(for: data)
        },
        contextForDeviceId: { [weak self] deviceId in
          self?.mediaPeerContext(deviceId: deviceId)
        },
        wifiAvailable: { [weak self] deviceId in
          self?.mediaPathAvailable(deviceId: deviceId) == true
        },
        sendFrame: { [weak self] deviceId, frame in
          guard let self else { throw TelemetryNativeError.message("Telemetry core unavailable") }
          return try await self.sendMediaFrame(deviceId: deviceId, frame: frame)
        },
        emit: { [weak self] name, payload in
          DispatchQueue.main.async {
            guard let self else { return }
            if name == "onMedia",
               let mediaFile = payload["fileName"] as? String,
               mediaFile != "__telemetry_profile_avatar.jpg",
               mediaFile != "__telemetry_media_probe.bin" {
              self.vault.upsertMediaEvent(payload)
            }
            if name == "onMedia", self.reliabilityLoggingEnabled {
              let state = payload["state"] as? String ?? "?"
              let file = payload["fileName"] as? String ?? "?"
              let peer = payload["peerDeviceId"] as? String ?? "?"
              let verified = payload["verified"] as? Bool ?? false
              print("[TelemetryMedia] state=\(state) file=\(file) peer=\(peer) verified=\(verified)")
            }
            if name == "onMedia",
               payload["state"] as? String == "incomingReady",
               payload["fileName"] as? String == "__telemetry_profile_avatar.jpg",
               let deviceId = payload["peerDeviceId"] as? String,
               let localUri = payload["localUri"] as? String,
               self.vault.setContactProfilePhoto(deviceId: deviceId, photoUri: localUri) {
              if self.reliabilityLoggingEnabled { print("[TelemetryProfile] avatarPersisted device=\(deviceId)") }
              self.emit("onProfile", ["deviceId": deviceId, "photoUri": localUri])
            }
            self.emit(name, payload)
          }
        }
      )
    } catch {
      fatalError("Telemetry media initialization failed: \(error)")
    }
  }()

  private func scheduleCallProbeIfRequested(deviceId: String) {
    guard reliabilityLoggingEnabled,
          !callProbeTargetDeviceIds.contains(deviceId),
          let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--telemetry-call-probe=") }) else { return }
    let requested = argument.replacingOccurrences(of: "--telemetry-call-probe=", with: "")
    guard ["voice", "video"].contains(requested),
          let peerId = peerIdForDeviceId(deviceId),
          trust.trustedKeyMaterial(deviceId: deviceId) != nil else { return }
    callProbeTargetDeviceIds.insert(deviceId)
    let callId = "probe-" + UUID().uuidString.lowercased()
    Task { @MainActor [weak self] in
      guard let self else { return }
      do {
        _ = try await self.sendCallSignal(
          peerId: peerId,
          peerDeviceId: deviceId,
          callId: callId,
          action: "invite",
          mode: requested
        )
        print("[TelemetryCallProbe] SENT peer=\(deviceId) mode=\(requested) callId=\(callId)")
      } catch {
        print("[TelemetryCallProbe] FAIL peer=\(deviceId) error=\(error.localizedDescription)")
      }
    }
  }

  private func scheduleMediaProbeIfRequested(deviceId: String) {
    guard reliabilityLoggingEnabled,
          !mediaProbeTargetDeviceIds.contains(deviceId),
          let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--telemetry-media-probe=") }),
          let requested = Int(argument.replacingOccurrences(of: "--telemetry-media-probe=", with: "")) else { return }
    let size = min(max(requested, 1024), 1024 * 1024)
    guard trust.trustedKeyMaterial(deviceId: deviceId) != nil else { return }
    mediaProbeTargetDeviceIds.insert(deviceId)
    Task { [weak self] in
      guard let self else { return }
      do {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("telemetry-media-probe-\(UUID().uuidString).bin")
        var data = Data(count: size)
        data.withUnsafeMutableBytes { raw in
          guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
          for index in 0..<size { base[index] = UInt8(index & 0xff) }
        }
        try data.write(to: url, options: .atomic)
        print("[TelemetryMediaProbe] start peer=\(deviceId) bytes=\(size)")
        let assetId = try await self.mediaRuntime.sendMedia(
          peerDeviceId: deviceId,
          fileURL: url,
          kind: "file",
          mimeType: "application/octet-stream",
          fileName: "__telemetry_media_probe.bin"
        )
        print("[TelemetryMediaProbe] queued peer=\(deviceId) asset=\(assetId)")
        try? FileManager.default.removeItem(at: url)
      } catch {
        print("[TelemetryMediaProbe] FAIL peer=\(deviceId) error=\(error.localizedDescription)")
      }
    }
  }

  private func mediaPeerContext(deviceId: String) -> TelemetryMediaPeerContext? {
    guard let material = trust.trustedKeyMaterial(deviceId: deviceId) else { return nil }
    do {
      let remote = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: material.exchange)
      let secret = try crypto.exchangePrivate.sharedSecretFromKeyAgreement(with: remote)
      let key = secret.hkdfDerivedSymmetricKey(
        using: SHA256.self,
        salt: Data(),
        sharedInfo: Data("telemetry/v0.1/session".utf8),
        outputByteCount: 32
      )
      return TelemetryMediaPeerContext(deviceId: deviceId, signingPublicKey: material.signing, pairwiseKey: key)
    } catch {
      return nil
    }
  }

  override init() {
    do {
      crypto = try TelemetryCryptoEngine()
      vault = try LocalVault()
    } catch {
      fatalError("Telemetry secure local initialization failed: \(error)")
    }
    super.init()
    notificationCenter.delegate = self
  }

  func setEmitter(_ value: ((String, [String: Any]) -> Void)?) {
    emitter = value
  }

  func consumePendingNotificationOpen() -> String? {
    defer { pendingNotificationOpenDeviceId = nil }
    return pendingNotificationOpenDeviceId
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
      "wifiPeerToPeer": true,
      "wifiTransport": "network-framework-bonjour-peer-to-peer",
      "wifiAware": false,
      "wifiAwareReason": "M1.4D uses Network.framework peer-to-peer Wi-Fi while BLE remains discovery/trust/fallback.",
      "platform": "ios"
    ]
  }

  func launchArgumentsPayload() -> [String] {
    ProcessInfo.processInfo.arguments
  }

  private var reliabilityLoggingEnabled: Bool {
    ProcessInfo.processInfo.arguments.contains("--telemetry-reliability-log")
  }

  private func logReliability(_ event: String) {
    guard reliabilityLoggingEnabled else { return }
    let stats = vault.reliabilityStats()
    print("[TelemetryReliability] \(event) pendingOutgoing=\(stats["pendingOutgoing"] ?? -1) deliveredOutgoing=\(stats["deliveredOutgoing"] ?? -1) incoming=\(stats["incoming"] ?? -1)")
  }

  func localStatePayload() -> String {
    for payload in mediaRuntime.recoverDurableOutgoingPreviews() {
      vault.upsertMediaEvent(payload)
    }
    return vault.payload()
  }

  func setAppearance(_ mode: String) -> String {
    vault.setAppearance(mode)
  }

  func setLocalProfile(displayName: String, about: String, sourcePhotoUri: String?, templateId: String?) -> [String: Any] {
    let record = vault.setProfile(displayName: displayName, about: about, sourcePhotoUri: sourcePhotoUri, templateId: templateId)
    var payload: [String: Any] = [
      "displayName": record.displayName,
      "about": record.about,
      "updatedAt": record.updatedAt
    ]
    if let photoUri = record.photoUri { payload["photoUri"] = photoUri }
    if let templateId = record.templateId { payload["templateId"] = templateId }
    return payload
  }

  func reliabilityDiagnosticsPayload() -> String {
    var payload = vault.reliabilityStats()
    payload["acceptedIncoming"] = acceptedIncomingCount
    payload["duplicateFramesSuppressed"] = duplicateFramesSuppressed
    payload["replayRejected"] = replayRejectedCount
    payload["deliveryReceiptsAccepted"] = deliveryReceiptsAccepted
    guard let data = try? JSONSerialization.data(withJSONObject: payload),
          let text = String(data: data, encoding: .utf8) else { return "{}" }
    return text
  }

  func resetReliabilityDiagnostics() {
    acceptedIncomingCount = 0
    duplicateFramesSuppressed = 0
    replayRejectedCount = 0
    deliveryReceiptsAccepted = 0
  }

  func setContactAlias(deviceId: String, alias: String) -> Bool {
    vault.setAlias(deviceId: deviceId, alias: alias)
  }

  func markConversationRead(deviceId: String) -> Bool {
    let result = vault.markConversationRead(deviceId: deviceId)
    if result { UIApplication.shared.applicationIconBadgeNumber = vault.totalUnreadCount() }
    return result
  }

  func setContactProfilePhoto(deviceId: String, photoUri: String) -> Bool {
    vault.setContactProfilePhoto(deviceId: deviceId, photoUri: photoUri)
  }

  func start() {
    desiredRunning = true
    logReliability("start")
    requestNotificationPermissionIfNeeded()
    emit("onState", ["state": "starting", "detail": "CoreBluetooth"])
    if centralManager == nil {
      centralManager = CBCentralManager(
        delegate: self,
        queue: .main,
        options: [
          // Keep trusted identity/message state in our vault, not in a restored CoreBluetooth
          // connection. Restored central links can remain stuck in connecting/disconnecting
          // after app replacement and prevent secure-session recovery.
          CBCentralManagerOptionShowPowerAlertKey: true
        ]
      )
    }
    if peripheralManager == nil {
      peripheralManager = CBPeripheralManager(
        delegate: self,
        queue: .main,
        options: [
          CBPeripheralManagerOptionRestoreIdentifierKey: "com.telemetry.ios.preview.peripheral",
          CBPeripheralManagerOptionShowPowerAlertKey: true
        ]
      )
    }
    _ = mediaRuntime
    _ = mpcTransport
    mpcTransport.start(localDeviceId: crypto.deviceId)
    do {
      try wifiTransport.start(localDeviceId: crypto.deviceId)
      if ProcessInfo.processInfo.arguments.contains("--telemetry-wifi-fail-next-send"), !wifiFailureLaunchHookConsumed {
        wifiFailureLaunchHookConsumed = true
        wifiTransport.armNextSendFailureForTest()
        if reliabilityLoggingEnabled { print("[TelemetryTransportTest] armed=wifi-fail-next-send") }
      }
      emit("onState", ["state": "wifi-starting", "detail": "peer-to-peer Bonjour"] )
    } catch {
      emit("onState", ["state": "wifi-unavailable", "detail": error.localizedDescription])
    }
    startCentralIfReady()
    startPeripheralIfReady()
  }

  private func requestNotificationPermissionIfNeeded() {
    guard !notificationPermissionRequested else { return }
    notificationPermissionRequested = true
    notificationCenter.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
      if let error {
        DispatchQueue.main.async { self?.emit("onError", ["message": "Notification permission error: \(error.localizedDescription)"]) }
      } else {
        DispatchQueue.main.async { self?.emit("onState", ["state": granted ? "notifications-ready" : "notifications-disabled"]) }
      }
    }
  }

  private func notifyIncomingMessageIfBackground(text: String, deviceId: String) {
    guard UIApplication.shared.applicationState != .active else { return }
    let content = UNMutableNotificationContent()
    content.title = vault.alias(for: deviceId) ?? "Telemetry …\(deviceId.suffix(8))"
    content.body = text
    content.sound = UNNotificationSound(named: UNNotificationSoundName("cleng.wav"))
    content.threadIdentifier = "telemetry.messages.\(deviceId)"
    content.userInfo = ["deviceId": deviceId]
    content.badge = NSNumber(value: vault.totalUnreadCount())
    let request = UNNotificationRequest(
      identifier: "telemetry.message.\(UUID().uuidString)",
      content: content,
      trigger: nil
    )
    notificationCenter.add(request) { [weak self] error in
      guard let error else { return }
      DispatchQueue.main.async { self?.emit("onError", ["message": "Notification delivery error: \(error.localizedDescription)"]) }
    }
  }

  func stop() {
    desiredRunning = false
    wifiTransport.stop()
    mpcTransport.stop()
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
    messageServerCharacteristic = nil
    receiptServerCharacteristic = nil
    subscribedCentrals.removeAll()
    bleMediaAssemblies.removeAll()
    let pendingBleMedia = bleMediaResponseContinuations.values
    bleMediaResponseContinuations.removeAll()
    for continuation in pendingBleMedia {
      continuation.resume(throwing: TelemetryNativeError.message("BLE media transport stopped"))
    }
    let pendingBleWrites = bleMediaWriteContinuations.values
    bleMediaWriteContinuations.removeAll()
    bleMediaActiveWriterPeerIds.removeAll()
    for continuation in pendingBleWrites {
      continuation.resume(throwing: TelemetryNativeError.message("BLE media transport stopped"))
    }
    for item in reconnectWorkItems.values { item.cancel() }
    reconnectWorkItems.removeAll()
    reconnectAttempts.removeAll()
    interactivePeerIds.removeAll()
    silentProbePeerIds.removeAll()
    probeCooldownUntil.removeAll()
    availableWifiPeerDeviceIds.removeAll()
    mediaProbeTargetDeviceIds.removeAll()
    callProbeTargetDeviceIds.removeAll()
    lastNativeProfileSyncAt.removeAll()
    emit("onState", ["state": "stopped"])
  }

  private func probeDiscoveredPeerForTrustedWifiDevice(_ remoteDeviceId: String) {
    guard desiredRunning,
          trust.trustedKeyMaterial(deviceId: remoteDeviceId) != nil,
          shouldInitiateTrustedConnection(remoteDeviceId: remoteDeviceId) else { return }

    let now = TelemetryCryptoEngine.nowMs()
    for peerId in discovered.keys.sorted() {
      if let remote = sessions[peerId]?.remoteHello {
        if remote.deviceId == remoteDeviceId, trust.isVerified(remote) {
          vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
          emitTrustedIfReady(peerId: peerId)
          return
        }
        continue
      }
      if interactivePeerIds.contains(peerId) || silentProbePeerIds.contains(peerId) { continue }
      if now < (probeCooldownUntil[peerId] ?? 0) { continue }
      silentProbePeerIds.insert(peerId)
      do {
        try connectInternal(peerId: peerId, emitConnecting: false)
        logReliability("wifiIdentityProbe target=\(remoteDeviceId) peer=\(peerId)")
      } catch {
        silentProbePeerIds.remove(peerId)
        probeCooldownUntil[peerId] = now + 4_000
      }
      return
    }
  }

  private func advertisementLocalName() -> String {
    let hex = crypto.deviceId.replacingOccurrences(of: "tlm:device:", with: "").lowercased()
    return "TLM-" + String(hex.prefix(12))
  }

  private func advertisedFingerprint(_ name: String?) -> String? {
    guard let name, name.hasPrefix("TLM-") else { return nil }
    let value = String(name.dropFirst(4)).lowercased()
    return value.count >= 8 ? value : nil
  }

  private func shouldInitiateTrustedConnection(remoteDeviceId: String) -> Bool {
    crypto.deviceId < remoteDeviceId
  }

  func connect(peerId: String) throws {
    interactivePeerIds.insert(peerId)
    silentProbePeerIds.remove(peerId)
    try connectInternal(peerId: peerId, emitConnecting: true)
  }

  func probePeer(peerId: String) throws {
    if let session = sessions[peerId], let remote = session.remoteHello, trust.isVerified(remote) {
      vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
      emitTrustedIfReady(peerId: peerId)
      return
    }
    guard !interactivePeerIds.contains(peerId) else { return }
    silentProbePeerIds.insert(peerId)
    try connectInternal(peerId: peerId, emitConnecting: false)
  }

  private func connectInternal(peerId: String, emitConnecting: Bool) throws {
    guard desiredRunning else { throw TelemetryNativeError.message("Start Offline first") }
    guard let peripheral = discovered[peerId] else {
      restartCentralScan()
      throw TelemetryNativeError.message("Nearby peer is no longer available")
    }
    _ = try ensureSession(peerId)
    reconnectWorkItems[peerId]?.cancel()
    reconnectWorkItems.removeValue(forKey: peerId)
    if emitConnecting { emit("onState", ["state": "connecting", "detail": peerId]) }
    logReliability("connectInternal peer=\(peerId) state=\(peripheral.state.rawValue)")
    switch peripheral.state {
    case .connected:
      peripheral.delegate = self
      peripheral.discoverServices([serviceUUID])
    case .disconnected:
      centralManager?.connect(peripheral, options: nil)
    case .connecting, .disconnecting:
      // A restored CoreBluetooth link can remain stuck across app replacement/relaunch.
      // Cancel only the transient transport, then reconnect with the same trusted identity.
      centralManager?.cancelPeripheralConnection(peripheral)
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self, weak peripheral] in
        guard let self, let peripheral, self.desiredRunning else { return }
        let currentPeerId = peripheral.identifier.uuidString
        self.logReliability("staleLinkRetry peer=\(currentPeerId) state=\(peripheral.state.rawValue)")
        if peripheral.state == .disconnected {
          _ = try? self.ensureSession(currentPeerId)
          self.centralManager?.connect(peripheral, options: nil)
        } else if peripheral.state == .connected {
          peripheral.delegate = self
          peripheral.discoverServices([serviceUUID])
        } else {
          self.scheduleReconnect(peerId: currentPeerId)
        }
      }
    @unknown default:
      scheduleReconnect(peerId: peerId)
    }
  }

  func recoverTransport(peerId: String) {
    guard desiredRunning else { return }
    logReliability("recoverTransport peer=\(peerId)")
    restartCentralScan()
    // Recovery is driven by signed identity discovery. Do not directly reconnect here:
    // both phones doing so at once can create a symmetric CoreBluetooth connect collision.
    emit("onState", ["state": "recovering", "detail": peerId])
  }

  func trustPeer(deviceId: String) -> Bool {
    guard let pair = sessions.first(where: { $0.value.remoteHello?.deviceId == deviceId }),
          let remote = pair.value.remoteHello else { return false }
    trust.verify(remote)
    vault.upsertContact(deviceId: remote.deviceId, peerId: pair.key)
    emitTrustedIfReady(peerId: pair.key)
    return true
  }

  private func peerIdForDeviceId(_ deviceId: String) -> String? {
    sessions.first(where: { $0.value.remoteHello?.deviceId == deviceId })?.key
  }

  private func isTransportReady(peerId: String) -> Bool {
    if let peripheral = discovered[peerId],
       peripheral.state == .connected,
       characteristics[peerId]?[messageUUID] != nil {
      return true
    }

    if subscribedCentrals[peerId] != nil, messageServerCharacteristic != nil {
      return true
    }

    return false
  }

  private func emitTrustedIfReady(peerId: String) {
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          trust.isVerified(remote),
          isTransportReady(peerId: peerId) else { return }
    logReliability("trustedReady peer=\(peerId)")
    emit("onTrusted", ["peerId": peerId, "deviceId": remote.deviceId])
    scheduleNativeProfileSync(peerId: peerId, deviceId: remote.deviceId)
    schedulePayloadProbesIfRequested(peerId: peerId)
    if reliabilityLoggingEnabled {
      print("[TelemetryBLEMedia] trusted-check peer=\(peerId) available=\(bleMediaAvailable(deviceId: remote.deviceId)) sharedMessageChannel=true")
    }
    scheduleMediaProbeIfRequested(deviceId: remote.deviceId)
    scheduleCallProbeIfRequested(deviceId: remote.deviceId)
    Task { [weak self] in
      let resumed = await self?.mediaRuntime.resumePending(peerDeviceId: remote.deviceId) ?? 0
      if self?.reliabilityLoggingEnabled == true { print("[TelemetryBLEMedia] trusted-resume peer=\(peerId) count=\(resumed)") }
    }
  }

  private func scheduleNativeProfileSync(peerId: String, deviceId: String) {
    let now = TelemetryCryptoEngine.nowMs()
    if now - (lastNativeProfileSyncAt[deviceId] ?? 0) < 5_000 { return }
    lastNativeProfileSyncAt[deviceId] = now
    let profile = vault.profile()
    if reliabilityLoggingEnabled {
      print("[TelemetryProfile] local device=\(crypto.deviceId) name=\(profile.displayName) template=\(profile.templateId ?? "") customPhoto=\(profile.photoUri != nil)")
    }
    Task { @MainActor [weak self] in
      guard let self else { return }
      // Give restored message queues first use of the fresh secure session.
      try? await Task.sleep(nanoseconds: 1_200_000_000)
      do {
        _ = try await self.sendProfile(
          peerId: peerId,
          peerDeviceId: deviceId,
          displayName: profile.displayName,
          templateId: profile.templateId
        )
        if self.reliabilityLoggingEnabled { print("[TelemetryProfile] metadataSent device=\(deviceId)") }
      } catch {
        if self.reliabilityLoggingEnabled { print("[TelemetryProfile] metadataSendDeferred device=\(deviceId) error=\(error.localizedDescription)") }
      }
      if let photoUri = profile.photoUri, let fileURL = URL(string: photoUri), fileURL.isFileURL {
        do {
          _ = try await self.mediaRuntime.sendMedia(
            peerDeviceId: deviceId,
            fileURL: fileURL,
            kind: "photo",
            mimeType: "image/jpeg",
            fileName: "__telemetry_profile_avatar.jpg"
          )
          if self.reliabilityLoggingEnabled { print("[TelemetryProfile] avatarSent device=\(deviceId)") }
        } catch {
          if self.reliabilityLoggingEnabled { print("[TelemetryProfile] avatarSendDeferred device=\(deviceId) error=\(error.localizedDescription)") }
        }
      }
    }
  }

  private func schedulePayloadProbesIfRequested(peerId: String) {
    guard !payloadProbeLaunchStarted,
          let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--telemetry-payload-probe=") }) else { return }
    let raw = argument.replacingOccurrences(of: "--telemetry-payload-probe=", with: "")
    let sizes = raw.split(separator: ",").compactMap { Int($0) }.filter { $0 > 0 && $0 <= 2 * 1024 * 1024 }
    guard !sizes.isEmpty else { return }
    payloadProbeLaunchStarted = true
    Task { @MainActor [weak self] in
      guard let self else { return }
      try? await Task.sleep(nanoseconds: 2_000_000_000)
      for size in sizes {
        do {
          _ = try await self.runEncryptedPayloadProbe(peerId: peerId, sizeBytes: size)
        } catch {
          print("[TelemetryPayloadProbe] FAIL bytes=\(size) error=\(error.localizedDescription)")
          self.emit("onError", ["message": "Payload probe failed: \(error.localizedDescription)"])
          break
        }
      }
    }
  }

  @MainActor
  func sendProfile(peerId: String, peerDeviceId: String, displayName: String, templateId: String?) async throws -> String {
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote), remote.deviceId == peerDeviceId else {
      throw TelemetryNativeError.message("Trusted profile peer binding mismatch")
    }

    let cleanName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(28))
    var payload: [String: String] = ["n": cleanName]
    if let templateId, (1...6).map({ "avatar-\($0)" }).contains(templateId) {
      payload["t"] = templateId
    }
    let jsonData = try JSONSerialization.data(withJSONObject: payload, options: [])
    guard let json = String(data: jsonData, encoding: .utf8) else {
      throw TelemetryNativeError.message("Could not encode peer profile")
    }
    let clear = profileControlPrefix + json
    guard clear.utf8.count <= maxTextBytes else {
      throw TelemetryNativeError.message("Peer profile control frame is too large")
    }

    let messageId = "profile:" + UUID().uuidString.lowercased()
    let message = try TelemetryCryptoEngine.encryptText(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      text: clear,
      messageId: messageId
    )
    let frame = try FrameCodec.encodeMessage(message)
    session.lastOutboundFrame = frame

    if wifiTransport.isAvailable(deviceId: remote.deviceId) {
      let receipt = try await wifiTransport.send(deviceId: remote.deviceId, frame: frame)
      DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        try? self.acceptReceipt(peerId: peerId, frame: receipt)
      }
      return messageId
    }

    try sendFrameOverBle(peerId: peerId, session: session, frame: frame, messageId: messageId)
    return messageId
  }

  @MainActor
  func sendCallSignal(
    peerId: String,
    peerDeviceId: String,
    callId: String,
    action: String,
    mode: String
  ) async throws -> String {
    guard ["invite", "end", "decline"].contains(action), ["voice", "video"].contains(mode) else {
      throw TelemetryNativeError.message("Unsupported call signal")
    }
    guard callId.count >= 8, callId.count <= 64 else {
      throw TelemetryNativeError.message("Invalid call identifier")
    }
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote), remote.deviceId == peerDeviceId else {
      throw TelemetryNativeError.message("Trusted call peer binding mismatch")
    }

    let payload = ["a": action, "m": mode, "c": callId]
    let jsonData = try JSONSerialization.data(withJSONObject: payload, options: [])
    guard let json = String(data: jsonData, encoding: .utf8) else {
      throw TelemetryNativeError.message("Could not encode call signal")
    }
    let clear = callControlPrefix + json
    guard clear.utf8.count <= maxTextBytes else {
      throw TelemetryNativeError.message("Call control frame is too large")
    }

    let messageId = "call:" + UUID().uuidString.lowercased()
    let message = try TelemetryCryptoEngine.encryptText(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      text: clear,
      messageId: messageId
    )
    let frame = try FrameCodec.encodeMessage(message)
    session.lastOutboundFrame = frame

    if wifiTransport.isAvailable(deviceId: remote.deviceId) {
      let receipt = try await wifiTransport.send(deviceId: remote.deviceId, frame: frame)
      DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        try? self.acceptReceipt(peerId: peerId, frame: receipt)
      }
      return messageId
    }

    try sendFrameOverBle(peerId: peerId, session: session, frame: frame, messageId: messageId)
    return messageId
  }

  func enqueueText(peerId: String, peerDeviceId: String, text: String) throws -> String {
    guard let payload = text.data(using: .utf8), !payload.isEmpty, payload.count <= maxTextBytes else {
      throw TelemetryNativeError.message("M1B text must be 1-\(maxTextBytes) UTF-8 bytes")
    }
    guard vault.hasContact(deviceId: peerDeviceId, peerId: peerId) else {
      throw TelemetryNativeError.message("Trusted contact binding is missing")
    }
    return vault.enqueueOutgoing(peerDeviceId: peerDeviceId, text: text)
  }

  private func prepareQueuedFrame(peerId: String, messageId: String) throws -> (PeerSession, SessionHello, Data, String) {
    guard let queued = vault.message(id: messageId), queued.mine, !queued.delivered else {
      throw TelemetryNativeError.message("Queued outbound message is missing or already delivered")
    }
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote) else {
      throw TelemetryNativeError.message("Compare and trust the safety code first")
    }
    guard remote.deviceId == queued.peerDeviceId else {
      throw TelemetryNativeError.message("Queued message contact binding mismatch")
    }

    let message = try TelemetryCryptoEngine.encryptText(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      text: queued.text,
      messageId: queued.id
    )
    let frame = try FrameCodec.encodeMessage(message)
    vault.markAttempt(messageId: queued.id)
    session.lastOutboundFrame = frame
    return (session, remote, frame, message.messageId)
  }

  private func sendFrameOverBle(peerId: String, session: PeerSession, frame: Data, messageId: String) throws {
    guard !bleMediaActiveWriterPeerIds.contains(peerId) else {
      throw TelemetryNativeError.message("BLE media transfer is using this peer link; retry text shortly")
    }
    session.lastOutboundMessageId = messageId
    if let peripheral = discovered[peerId],
       peripheral.state == .connected,
       let characteristic = characteristics[peerId]?[messageUUID] {
      guard frame.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
        throw TelemetryNativeError.message("Encrypted frame exceeds current BLE write size")
      }
      peripheral.writeValue(frame, for: characteristic, type: .withResponse)
      return
    }

    if let central = subscribedCentrals[peerId],
       let manager = peripheralManager,
       let characteristic = messageServerCharacteristic {
      guard frame.count <= central.maximumUpdateValueLength else {
        throw TelemetryNativeError.message("Encrypted frame exceeds current BLE notification size")
      }
      guard manager.updateValue(frame, for: characteristic, onSubscribedCentrals: [central]) else {
        session.lastOutboundMessageId = nil
        throw TelemetryNativeError.message("BLE transmit queue is busy; retry")
      }
      return
    }

    throw TelemetryNativeError.message("Peer BLE connection is not active in either direction")
  }

  func sendQueuedText(peerId: String, messageId: String) throws -> String {
    let prepared = try prepareQueuedFrame(peerId: peerId, messageId: messageId)
    try sendFrameOverBle(peerId: peerId, session: prepared.0, frame: prepared.2, messageId: prepared.3)
    emit("onState", ["state": "transport-selected", "detail": "ble"])
    return prepared.3
  }

  @MainActor
  func sendQueuedTextAdaptive(peerId: String, messageId: String) async throws -> String {
    let prepared = try prepareQueuedFrame(peerId: peerId, messageId: messageId)
    let session = prepared.0
    let remote = prepared.1
    let frame = prepared.2
    let stableMessageId = prepared.3

    if wifiTransport.isAvailable(deviceId: remote.deviceId) {
      session.lastOutboundMessageId = stableMessageId
      do {
        let receipt = try await wifiTransport.send(deviceId: remote.deviceId, frame: frame)
        // Defer receipt delivery one main-queue turn so the Expo promise can resolve
        // and JS can bind the stable messageId before onDelivery is emitted.
        DispatchQueue.main.async { [weak self] in
          guard let self else { return }
          do {
            try self.acceptReceipt(peerId: peerId, frame: receipt)
          } catch {
            self.emit("onError", ["message": error.localizedDescription])
          }
        }
        if reliabilityLoggingEnabled { print("[TelemetryWiFi] selected=wifi-direct messageId=\(stableMessageId) bytes=\(frame.count)") }
        emit("onState", ["state": "transport-selected", "detail": "wifi-direct"])
        return stableMessageId
      } catch {
        if reliabilityLoggingEnabled { print("[TelemetryWiFi] fallback=ble messageId=\(stableMessageId) reason=\(error.localizedDescription)") }
        emit("onState", [
          "state": "transport-fallback",
          "detail": "wifi-direct to ble · \(error.localizedDescription)"
        ])
      }
    }

    try sendFrameOverBle(peerId: peerId, session: session, frame: frame, messageId: stableMessageId)
    if reliabilityLoggingEnabled { print("[TelemetryWiFi] selected=ble messageId=\(stableMessageId) bytes=\(frame.count)") }
    emit("onState", ["state": "transport-selected", "detail": "ble"])
    return stableMessageId
  }

  @MainActor
  func sendQueuedTextUsingTransport(peerId: String, messageId: String, transport: String) async throws -> String {
    switch transport.lowercased() {
    case "wifi":
      let prepared = try prepareQueuedFrame(peerId: peerId, messageId: messageId)
      let session = prepared.0
      let remote = prepared.1
      let frame = prepared.2
      let stableMessageId = prepared.3
      guard wifiTransport.isAvailable(deviceId: remote.deviceId) else {
        throw TelemetryNativeError.message("Wi-Fi test transport is unavailable")
      }
      session.lastOutboundMessageId = stableMessageId
      let receipt = try await wifiTransport.send(deviceId: remote.deviceId, frame: frame)
      DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        do { try self.acceptReceipt(peerId: peerId, frame: receipt) }
        catch { self.emit("onError", ["message": error.localizedDescription]) }
      }
      if reliabilityLoggingEnabled { print("[TelemetryTransportTest] mode=wifi messageId=\(stableMessageId) bytes=\(frame.count)") }
      emit("onState", ["state": "transport-selected", "detail": "wifi-test"])
      return stableMessageId

    case "ble":
      let prepared = try prepareQueuedFrame(peerId: peerId, messageId: messageId)
      try sendFrameOverBle(peerId: peerId, session: prepared.0, frame: prepared.2, messageId: prepared.3)
      if reliabilityLoggingEnabled { print("[TelemetryTransportTest] mode=ble messageId=\(prepared.3) bytes=\(prepared.2.count)") }
      emit("onState", ["state": "transport-selected", "detail": "ble-test"])
      return prepared.3

    default:
      return try await sendQueuedTextAdaptive(peerId: peerId, messageId: messageId)
    }
  }

  @MainActor
  private func runEncryptedPayloadProbe(peerId: String, sizeBytes: Int) async throws -> String {
    guard sizeBytes > 0, sizeBytes <= 2 * 1024 * 1024 else {
      throw TelemetryNativeError.message("Payload probe size is outside the 1-2097152 byte test range")
    }
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote) else { throw TelemetryNativeError.message("Peer is not trusted") }
    guard wifiTransport.isAvailable(deviceId: remote.deviceId) else {
      throw TelemetryNativeError.message("Wi-Fi peer is unavailable for payload probe")
    }

    let payload = Data(repeating: 0x5A, count: sizeBytes)
    let messageId = "probe:" + UUID().uuidString.lowercased()
    let message = try TelemetryCryptoEngine.encryptPayload(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      payload: payload,
      messageId: messageId
    )
    let frame = try FrameCodec.encodeMessage(message)
    let started = CFAbsoluteTimeGetCurrent()
    let receiptFrame = try await wifiTransport.send(deviceId: remote.deviceId, frame: frame)
    let receipt = try FrameCodec.decodeReceipt(receiptFrame)
    guard receipt.messageId == messageId,
          TelemetryCryptoEngine.verifyReceipt(receipt, peerSigningKey: remote.signingPublicKey) else {
      throw TelemetryNativeError.message("Payload probe receipt verification failed")
    }
    let elapsedMs = Int((CFAbsoluteTimeGetCurrent() - started) * 1000)
    print("[TelemetryPayloadProbe] PASS bytes=\(sizeBytes) frameBytes=\(frame.count) elapsedMs=\(elapsedMs) messageId=\(messageId) receipt=verified")
    return messageId
  }

  func replayLastEncryptedFrameForTest(peerId: String) throws -> Bool {
    guard let session = sessions[peerId],
          let frame = session.lastOutboundFrame else {
      throw TelemetryNativeError.message("No encrypted frame is available to replay")
    }
    let message = try FrameCodec.decodeMessage(frame)
    session.lastOutboundMessageId = message.messageId

    if let peripheral = discovered[peerId],
       peripheral.state == .connected,
       let characteristic = characteristics[peerId]?[messageUUID] {
      peripheral.writeValue(frame, for: characteristic, type: .withResponse)
      return true
    }
    if let central = subscribedCentrals[peerId],
       let manager = peripheralManager,
       let characteristic = messageServerCharacteristic {
      guard manager.updateValue(frame, for: characteristic, onSubscribedCentrals: [central]) else {
        throw TelemetryNativeError.message("BLE transmit queue is busy; retry")
      }
      return true
    }
    throw TelemetryNativeError.message("Peer BLE connection is not active in either direction")
  }

  func sendMedia(
    peerDeviceId: String,
    uri: String,
    kind: String,
    mimeType: String,
    fileName: String
  ) async throws -> String {
    guard let url = URL(string: uri), url.isFileURL else {
      throw TelemetryNativeError.message("Media URI must be a local file URL")
    }
    if !mediaPathAvailable(deviceId: peerDeviceId) {
      wifiTransport.refreshDiscovery()
    }
    return try await mediaRuntime.sendMedia(
      peerDeviceId: peerDeviceId,
      fileURL: url,
      kind: kind,
      mimeType: mimeType,
      fileName: fileName
    )
  }

  func resumeMedia(peerDeviceId: String?) async -> Int {
    if let peerDeviceId, !mediaPathAvailable(deviceId: peerDeviceId) {
      wifiTransport.refreshDiscovery()
      try? await Task.sleep(nanoseconds: 650_000_000)
    }
    return await mediaRuntime.resumePending(peerDeviceId: peerDeviceId)
  }

  func sendText(peerId: String, text: String) throws -> String {
    guard let session = sessions[peerId], let remote = session.remoteHello else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
    let messageId = try enqueueText(peerId: peerId, peerDeviceId: remote.deviceId, text: text)
    return try sendQueuedText(peerId: peerId, messageId: messageId)
  }

  private func startCentralIfReady() {
    guard desiredRunning, let manager = centralManager, manager.state == .poweredOn else { return }
    guard !manager.isScanning else { return }
    manager.scanForPeripherals(
      withServices: [serviceUUID],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
    )
    emit("onState", ["state": "scanning", "detail": "advertising + scanning"])
  }

  private func restartCentralScan() {
    guard desiredRunning, let manager = centralManager, manager.state == .poweredOn else { return }
    if manager.isScanning { manager.stopScan() }
    manager.scanForPeripherals(
      withServices: [serviceUUID],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
    )
    emit("onState", ["state": "scanning", "detail": "recovery scan"])
  }

  private func scheduleReconnect(peerId: String, immediate: Bool = false) {
    guard desiredRunning, centralManager?.state == .poweredOn else { return }
    reconnectWorkItems[peerId]?.cancel()

    let attempt = reconnectAttempts[peerId] ?? 0
    let delays: [Double] = [0.5, 1.0, 2.0, 3.0]
    guard immediate || attempt < delays.count else {
      restartCentralScan()
      emit("onState", ["state": "waiting-peer", "detail": peerId])
      return
    }
    let delay = immediate ? 0.15 : delays[attempt]
    if !immediate { reconnectAttempts[peerId] = attempt + 1 }

    let work = DispatchWorkItem { [weak self] in
      guard let self, self.desiredRunning, self.centralManager?.state == .poweredOn else { return }
      self.reconnectWorkItems.removeValue(forKey: peerId)
      self.restartCentralScan()
      guard let peripheral = self.discovered[peerId] else {
        self.emit("onState", ["state": "waiting-peer", "detail": peerId])
        if !immediate { self.scheduleReconnect(peerId: peerId) }
        return
      }
      switch peripheral.state {
      case .connected:
        peripheral.delegate = self
        self.emit("onState", ["state": "recovering", "detail": peerId])
        peripheral.discoverServices([serviceUUID])
      case .disconnected:
        self.emit("onState", ["state": "reconnecting", "detail": peerId])
        self.centralManager?.connect(peripheral, options: nil)
      default:
        break
      }
    }
    reconnectWorkItems[peerId] = work
    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
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
        properties: [.write, .notify],
        value: nil,
        permissions: [.writeable]
      )
      let receipt = CBMutableCharacteristic(
        type: receiptUUID,
        properties: [.read, .write],
        value: nil,
        permissions: [.readable, .writeable]
      )
      let service = CBMutableService(type: serviceUUID, primary: true)
      service.characteristics = [hello, message, receipt]
      messageServerCharacteristic = message
      receiptServerCharacteristic = receipt
      manager.add(service)
      gattInstalled = true
    } else if !manager.isAdvertising {
      manager.startAdvertising([
        CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
        CBAdvertisementDataLocalNameKey: advertisementLocalName()
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
      interactivePeerIds.remove(peerId)
      silentProbePeerIds.remove(peerId)
      probeCooldownUntil.removeValue(forKey: peerId)
      vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
      logReliability("identityRebound peer=\(peerId) device=\(remote.deviceId)")
      emitTrustedIfReady(peerId: peerId)
    } else if interactivePeerIds.contains(peerId) {
      silentProbePeerIds.remove(peerId)
      emit("onVerification", [
        "peerId": peerId,
        "deviceId": remote.deviceId,
        "safetyCode": code
      ])
    } else {
      silentProbePeerIds.remove(peerId)
      probeCooldownUntil[peerId] = TelemetryCryptoEngine.nowMs() + 15_000
      logReliability("silentProbeIgnoredUntrusted peer=\(peerId) device=\(remote.deviceId)")
      if let peripheral = discovered[peerId], peripheral.state == .connected {
        centralManager?.cancelPeripheralConnection(peripheral)
      }
    }
  }

  private func acceptMessage(peerId: String, frame: Data) throws -> Data {
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

    let receipt = try FrameCodec.encodeReceipt(crypto.createReceipt(for: message))
    if message.messageId.hasPrefix("probe:"), ProcessInfo.processInfo.arguments.contains("--telemetry-payload-probe-accept") {
      guard replay.accept(message.messageId) else {
        throw TelemetryNativeError.message("Duplicate payload probe rejected")
      }
      let clearPayload = try TelemetryCryptoEngine.decryptPayload(key: key, message: message)
      print("[TelemetryPayloadProbe] ACCEPT bytes=\(clearPayload.count) frameBytes=\(frame.count) messageId=\(message.messageId)")
      return receipt
    }

    let clear = try TelemetryCryptoEngine.decryptText(key: key, message: message)
    if message.messageId.hasPrefix("profile:") && clear.hasPrefix(profileControlPrefix) {
      guard replay.accept(message.messageId) else { return receipt }
      let raw = String(clear.dropFirst(profileControlPrefix.count))
      if let data = raw.data(using: .utf8),
         let payload = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
        vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
        _ = vault.updateContactProfile(
          deviceId: remote.deviceId,
          displayName: payload["n"],
          templateId: payload["t"]
        )
        if reliabilityLoggingEnabled {
          print("[TelemetryProfile] metadataAccepted device=\(remote.deviceId) name=\(payload["n"] ?? "") template=\(payload["t"] ?? "")")
        }
        emit("onProfile", [
          "peerId": peerId,
          "deviceId": remote.deviceId,
          "displayName": payload["n"] ?? "",
          "templateId": payload["t"] ?? ""
        ])
      }
      return receipt
    }
    if message.messageId.hasPrefix("call:") && clear.hasPrefix(callControlPrefix) {
      guard replay.accept(message.messageId) else { return receipt }
      let raw = String(clear.dropFirst(callControlPrefix.count))
      guard let data = raw.data(using: .utf8),
            let payload = try? JSONSerialization.jsonObject(with: data) as? [String: String],
            let action = payload["a"], ["invite", "end", "decline"].contains(action),
            let mode = payload["m"], ["voice", "video"].contains(mode),
            let callId = payload["c"], callId.count >= 8, callId.count <= 64 else {
        throw TelemetryNativeError.message("Malformed encrypted call signal")
      }
      if reliabilityLoggingEnabled {
        print("[TelemetryCallSignal] RECEIVED peer=\(remote.deviceId) action=\(action) mode=\(mode) callId=\(callId)")
      }
      emit("onCallSignal", [
        "peerId": peerId,
        "deviceId": remote.deviceId,
        "callId": callId,
        "action": action,
        "mode": mode
      ])
      return receipt
    }
    if vault.containsIncomingMessage(id: message.messageId, peerDeviceId: remote.deviceId) {
      duplicateFramesSuppressed += 1
      if reliabilityLoggingEnabled {
        print("[TelemetryReliability] duplicateFrameSuppressed=\(duplicateFramesSuppressed) messageId=\(message.messageId)")
      }
      return receipt
    }
    guard replay.accept(message.messageId) else {
      replayRejectedCount += 1
      throw TelemetryNativeError.message("Duplicate message rejected")
    }
    vault.upsertContact(deviceId: remote.deviceId, peerId: peerId)
    vault.appendMessage(
      id: message.messageId,
      peerDeviceId: remote.deviceId,
      text: clear,
      mine: false,
      delivered: true
    )
    acceptedIncomingCount += 1
    if reliabilityLoggingEnabled {
      print("[TelemetryReliability] acceptedIncoming=\(acceptedIncomingCount)")
    }
    vault.incrementUnread(deviceId: remote.deviceId)
    UIApplication.shared.applicationIconBadgeNumber = vault.totalUnreadCount()
    notifyIncomingMessageIfBackground(text: clear, deviceId: remote.deviceId)
    emit("onMessage", [
      "peerId": peerId,
      "deviceId": remote.deviceId,
      "messageId": message.messageId,
      "text": clear
    ])
    return receipt
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
    guard TelemetryCryptoEngine.verifyReceipt(receipt, peerSigningKey: remote.signingPublicKey) else {
      throw TelemetryNativeError.message("Invalid signed delivery receipt")
    }
    if receipt.messageId.hasPrefix("profile:") || receipt.messageId.hasPrefix("call:") {
      if session.lastOutboundMessageId == receipt.messageId {
        session.lastOutboundMessageId = nil
      }
      return
    }
    guard receipt.messageId == session.lastOutboundMessageId else {
      throw TelemetryNativeError.message("Receipt does not match outbound message")
    }

    session.lastOutboundMessageId = nil
    deliveryReceiptsAccepted += 1
    if reliabilityLoggingEnabled {
      print("[TelemetryReliability] deliveryReceiptsAccepted=\(deliveryReceiptsAccepted)")
    }
    vault.markDelivered(messageId: receipt.messageId)
    emit("onDelivery", ["peerId": peerId, "messageId": receipt.messageId])
  }


  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    defer { completionHandler() }
    guard let deviceId = response.notification.request.content.userInfo["deviceId"] as? String else { return }
    pendingNotificationOpenDeviceId = deviceId
    if emitter != nil { emit("onNotificationOpen", ["deviceId": deviceId]) }
  }

  private func emit(_ name: String, _ payload: [String: Any]) {
    emitter?(name, payload)
  }

  private func emitError(_ error: Error) {
    emit("onError", ["message": error.localizedDescription])
  }

  func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    desiredRunning = true
    let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
    for peripheral in restored {
      let peerId = peripheral.identifier.uuidString
      discovered[peerId] = peripheral
      peripheral.delegate = self
      if peripheral.state == .connected {
        peripheral.discoverServices([serviceUUID])
      }
    }
    emit("onState", ["state": "restored", "detail": "BLE central · \(restored.count) peer(s)"])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
    desiredRunning = true
    let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
    for service in services where service.uuid == serviceUUID {
      gattInstalled = true
      for characteristic in service.characteristics ?? [] {
        guard let mutable = characteristic as? CBMutableCharacteristic else { continue }
        if mutable.uuid == messageUUID { messageServerCharacteristic = mutable }
        if mutable.uuid == receiptUUID { receiptServerCharacteristic = mutable }
      }
    }
    emit("onState", ["state": "restored", "detail": "BLE peripheral"])
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    switch central.state {
    case .poweredOn:
      reconnectAttempts.removeAll()
      restartCentralScan()
      emit("onState", ["state": "bluetooth-on", "detail": "Recovery ready"])
    case .poweredOff:
      characteristics.removeAll()
      sessions.removeAll()
      for item in reconnectWorkItems.values { item.cancel() }
      reconnectWorkItems.removeAll()
      reconnectAttempts.removeAll()
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
    if reliabilityLoggingEnabled { print("[TelemetryReliability] peerSeen=\(peerId) rssi=\(RSSI.intValue)") }
    let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
    emit("onPeerSeen", [
      "peerId": peerId,
      "name": advertisedName ?? peripheral.name ?? "Telemetry device",
      "rssi": RSSI.intValue
    ])

    for deviceId in availableWifiPeerDeviceIds.sorted() {
      probeDiscoveredPeerForTrustedWifiDevice(deviceId)
    }

    // CoreBluetooth peer UUIDs are transient. The advertisement exposes only a short
    // stable fingerprint so we can choose one BLE initiator before the signed hello.
    // Full trust still comes exclusively from the verified signed hello below.
    let now = TelemetryCryptoEngine.nowMs()
    if let fingerprint = advertisedFingerprint(advertisedName),
       let remoteDeviceId = vault.trustedDeviceId(matchingFingerprint: fingerprint) {
      let initiate = shouldInitiateTrustedConnection(remoteDeviceId: remoteDeviceId)
      logReliability("trustedAdvertisement peer=\(peerId) device=\(remoteDeviceId) initiate=\(initiate)")
      if initiate,
         sessions[peerId]?.remoteHello == nil,
         !interactivePeerIds.contains(peerId),
         !silentProbePeerIds.contains(peerId),
         now >= (probeCooldownUntil[peerId] ?? 0) {
        silentProbePeerIds.insert(peerId)
        do {
          try connectInternal(peerId: peerId, emitConnecting: false)
          logReliability("nativeIdentityProbe peer=\(peerId)")
        } catch {
          silentProbePeerIds.remove(peerId)
          probeCooldownUntil[peerId] = now + 4_000
        }
      }
    }
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    let peerId = peripheral.identifier.uuidString
    logReliability("didConnect peer=\(peerId)")
    reconnectWorkItems[peerId]?.cancel()
    reconnectWorkItems.removeValue(forKey: peerId)
    reconnectAttempts[peerId] = 0
    peripheral.delegate = self
    emit("onState", ["state": "connected", "detail": peerId])
    peripheral.discoverServices([serviceUUID])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    characteristics.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    silentProbePeerIds.remove(peerId)
    emit("onState", ["state": "waiting-peer", "detail": peerId])
    scheduleReconnect(peerId: peerId)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    let peerId = peripheral.identifier.uuidString
    characteristics.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    silentProbePeerIds.remove(peerId)
    emit("onState", ["state": "disconnected", "detail": peerId])

    guard desiredRunning else { return }
    reconnectAttempts[peerId] = 0
    scheduleReconnect(peerId: peerId)
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

    if let messageCharacteristic = map[messageUUID], messageCharacteristic.properties.contains(.notify) {
      peripheral.setNotifyValue(true, for: messageCharacteristic)
    }

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
    let peerId = peripheral.identifier.uuidString
    if characteristic.uuid == messageUUID, let pending = bleMediaWriteContinuations.removeValue(forKey: peerId) {
      if let error { pending.resume(throwing: error) }
      else { pending.resume(returning: ()) }
      return
    }
    if let error {
      if characteristic.uuid == helloUUID || characteristic.uuid == messageUUID || characteristic.uuid == receiptUUID {
        characteristics.removeValue(forKey: peerId)
        sessions.removeValue(forKey: peerId)
        emit("onState", ["state": "transport-interrupted", "detail": peerId])
        if peripheral.state == .connected {
          centralManager?.cancelPeripheralConnection(peripheral)
        } else {
          scheduleReconnect(peerId: peerId, immediate: true)
        }
      } else {
        emitError(error)
      }
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
      let peerId = peripheral.identifier.uuidString
      characteristics.removeValue(forKey: peerId)
      sessions.removeValue(forKey: peerId)
      emit("onState", ["state": "transport-interrupted", "detail": peerId])
      if peripheral.state == .connected {
        centralManager?.cancelPeripheralConnection(peripheral)
      } else {
        scheduleReconnect(peerId: peerId, immediate: true)
      }
      return
    }
    guard let data = characteristic.value else { return }
    do {
      if characteristic.uuid == helloUUID {
        try acceptHello(peerId: peripheral.identifier.uuidString, frame: data)
      } else if characteristic.uuid == messageUUID {
        let peerId = peripheral.identifier.uuidString
        if isBleMediaPacket(data) {
          try handleBleMediaFragment(peerId: peerId, packet: data)
        } else {
          let receipt = try acceptMessage(peerId: peerId, frame: data)
          guard let receiptCharacteristic = characteristics[peerId]?[receiptUUID] else {
            throw TelemetryNativeError.message("Telemetry receipt characteristic is unavailable")
          }
          guard receipt.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
            throw TelemetryNativeError.message("Signed receipt exceeds current BLE write size")
          }
          peripheral.writeValue(receipt, for: receiptCharacteristic, type: .withResponse)
        }
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
      CBAdvertisementDataLocalNameKey: advertisementLocalName()
    ])
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    if let error {
      emitError(error)
      return
    }
    emit("onState", ["state": "offline", "detail": "advertising + scanning"])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    guard characteristic.uuid == messageUUID else { return }
    let peerId = central.identifier.uuidString
    subscribedCentrals[peerId] = central
    emit("onState", ["state": "connected", "detail": peerId])
    emitTrustedIfReady(peerId: peerId)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    guard characteristic.uuid == messageUUID else { return }
    let peerId = central.identifier.uuidString
    subscribedCentrals.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    emit("onState", ["state": "disconnected", "detail": peerId])
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
          if isBleMediaPacket(value) {
            try handleBleMediaFragment(peerId: peerId, packet: value)
          } else {
            receipts[peerId] = try acceptMessage(peerId: peerId, frame: value)
          }
        } else if request.characteristic.uuid == receiptUUID {
          try acceptReceipt(peerId: peerId, frame: value)
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
      "onProfile",
      "onDelivery",
      "onMedia",
      "onCallSignal",
      "onNotificationOpen",
      "onError"
    )

    OnCreate {
      self.core.setEmitter { [weak self] name, payload in
        self?.sendEvent(name, payload)
      }
    }

    OnDestroy {
      self.core.stop()
      self.core.setEmitter(nil)
    }

    Function("getIdentity") {
      self.core.identityPayload()
    }

    Function("getCapabilities") {
      self.core.capabilitiesPayload()
    }

    Function("getLocalState") {
      self.core.localStatePayload()
    }

    Function("getLaunchArguments") {
      self.core.launchArgumentsPayload()
    }

    Function("consumePendingNotificationOpen") {
      self.core.consumePendingNotificationOpen()
    }

    Function("getReliabilityDiagnostics") {
      self.core.reliabilityDiagnosticsPayload()
    }

    Function("resetReliabilityDiagnostics") {
      self.core.resetReliabilityDiagnostics()
    }

    AsyncFunction("replayLastEncryptedFrameForTest") { (peerId: String) in
      try self.core.replayLastEncryptedFrameForTest(peerId: peerId)
    }.runOnQueue(.main)

    AsyncFunction("setLocalProfile") { (displayName: String, about: String, sourcePhotoUri: String?, templateId: String?) in
      self.core.setLocalProfile(displayName: displayName, about: about, sourcePhotoUri: sourcePhotoUri, templateId: templateId)
    }.runOnQueue(.main)

    AsyncFunction("setAppearance") { (mode: String) in
      self.core.setAppearance(mode)
    }.runOnQueue(.main)

    AsyncFunction("setContactAlias") { (deviceId: String, alias: String) in
      self.core.setContactAlias(deviceId: deviceId, alias: alias)
    }.runOnQueue(.main)

    AsyncFunction("markConversationRead") { (deviceId: String) in
      self.core.markConversationRead(deviceId: deviceId)
    }.runOnQueue(.main)

    AsyncFunction("setContactProfilePhoto") { (deviceId: String, photoUri: String) in
      self.core.setContactProfilePhoto(deviceId: deviceId, photoUri: photoUri)
    }.runOnQueue(.main)

    AsyncFunction("startOffline") {
      self.core.start()
    }.runOnQueue(.main)

    AsyncFunction("stopOffline") {
      self.core.stop()
    }.runOnQueue(.main)

    AsyncFunction("connect") { (peerId: String) in
      try self.core.connect(peerId: peerId)
    }.runOnQueue(.main)

    AsyncFunction("probePeer") { (peerId: String) in
      try self.core.probePeer(peerId: peerId)
    }.runOnQueue(.main)

    AsyncFunction("recoverTransport") { (peerId: String) in
      self.core.recoverTransport(peerId: peerId)
    }.runOnQueue(.main)

    AsyncFunction("trustPeer") { (deviceId: String) in
      self.core.trustPeer(deviceId: deviceId)
    }.runOnQueue(.main)

    AsyncFunction("enqueueText") { (peerId: String, peerDeviceId: String, text: String) in
      try self.core.enqueueText(peerId: peerId, peerDeviceId: peerDeviceId, text: text)
    }.runOnQueue(.main)

    AsyncFunction("sendProfile") { (peerId: String, peerDeviceId: String, displayName: String, templateId: String?) async throws -> String in
      try await self.core.sendProfile(peerId: peerId, peerDeviceId: peerDeviceId, displayName: displayName, templateId: templateId)
    }

    AsyncFunction("sendQueuedText") { (peerId: String, messageId: String) async throws -> String in
      try await self.core.sendQueuedTextAdaptive(peerId: peerId, messageId: messageId)
    }

    AsyncFunction("sendQueuedTextUsingTransport") { (peerId: String, messageId: String, transport: String) async throws -> String in
      try await self.core.sendQueuedTextUsingTransport(peerId: peerId, messageId: messageId, transport: transport)
    }

    AsyncFunction("sendCallSignal") { (peerId: String, peerDeviceId: String, callId: String, action: String, mode: String) async throws -> String in
      try await self.core.sendCallSignal(
        peerId: peerId,
        peerDeviceId: peerDeviceId,
        callId: callId,
        action: action,
        mode: mode
      )
    }

    AsyncFunction("sendMedia") { (peerDeviceId: String, uri: String, kind: String, mimeType: String, fileName: String) async throws -> String in
      try await self.core.sendMedia(
        peerDeviceId: peerDeviceId,
        uri: uri,
        kind: kind,
        mimeType: mimeType,
        fileName: fileName
      )
    }

    AsyncFunction("resumeMedia") { (peerDeviceId: String?) async -> Int in
      await self.core.resumeMedia(peerDeviceId: peerDeviceId)
    }

    AsyncFunction("sendText") { (peerId: String, text: String) in
      try self.core.sendText(peerId: peerId, text: text)
    }.runOnQueue(.main)
  }
}
