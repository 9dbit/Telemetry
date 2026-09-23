import ExpoModulesCore
import Foundation
import CryptoKit

public class TelemetryInteropSelfTestModule: Module {
  public func definition() -> ModuleDefinition {
    Name("TelemetryInteropSelfTest")

    Function("run") {
      try M1BInteropReference.run()
    }
  }
}

private enum M1BInteropReference {
  private static let helloVersion = "telemetry/m1b/hello"
  private static let sessionContext = "telemetry/m1b/session"
  private static let messageContext = "telemetry/m1b/message"
  private static let receiptContext = "telemetry/m1b/receipt"

  static func run() throws -> [String: Any] {
    let issuedAt: Int64 = 1_770_000_000_000
    let aSigning = try Curve25519.Signing.PrivateKey(rawRepresentation: data("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
    let aExchange = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"))
    let bSigning = try Curve25519.Signing.PrivateKey(rawRepresentation: data("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"))
    let bExchange = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"))
    let aEphemeral = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
    let bEphemeral = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"))
    let aNonce = try data("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
    let bNonce = try data("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf")

    let aSigningPublic = aSigning.publicKey.rawRepresentation
    let bSigningPublic = bSigning.publicKey.rawRepresentation
    let aExchangePublic = aExchange.publicKey.rawRepresentation
    let bExchangePublic = bExchange.publicKey.rawRepresentation
    let aEphemeralPublic = aEphemeral.publicKey.rawRepresentation
    let bEphemeralPublic = bEphemeral.publicKey.rawRepresentation
    let aDeviceId = deviceId(aSigningPublic)
    let bDeviceId = deviceId(bSigningPublic)

    let aHelloCanonical = canonical([
      helloVersion,
      aDeviceId,
      hex(aSigningPublic),
      hex(aExchangePublic),
      hex(aEphemeralPublic),
      hex(aNonce),
      String(issuedAt)
    ])
    let bHelloCanonical = canonical([
      helloVersion,
      bDeviceId,
      hex(bSigningPublic),
      hex(bExchangePublic),
      hex(bEphemeralPublic),
      hex(bNonce),
      String(issuedAt)
    ])
    let aSignature = try aSigning.signature(for: aHelloCanonical)
    let bSignature = try bSigning.signature(for: bHelloCanonical)

    let sharedA = try aEphemeral.sharedSecretFromKeyAgreement(with: bEphemeral.publicKey)
    let sharedB = try bEphemeral.sharedSecretFromKeyAgreement(with: aEphemeral.publicKey)
    let sharedAData = sharedA.withUnsafeBytes { Data($0) }
    let sharedBData = sharedB.withUnsafeBytes { Data($0) }

    let helloParts = [
      (deviceId: aDeviceId, nonce: aNonce, ephemeral: aEphemeralPublic, signing: aSigningPublic),
      (deviceId: bDeviceId, nonce: bNonce, ephemeral: bEphemeralPublic, signing: bSigningPublic)
    ].sorted { $0.deviceId < $1.deviceId }

    var info = sessionContext
    for hello in helloParts {
      info += "|\(hello.deviceId)|\(hex(hello.nonce))|\(hex(hello.ephemeral))"
    }
    let sessionKey = sharedA.hkdfDerivedSymmetricKey(
      using: SHA256.self,
      salt: Data(),
      sharedInfo: Data(info.utf8),
      outputByteCount: 32
    )
    let sessionKeyData = sessionKey.withUnsafeBytes { Data($0) }

    var safetyMaterial = Data()
    for hello in helloParts {
      safetyMaterial.append(Data(hello.deviceId.utf8))
      safetyMaterial.append(hello.signing)
      safetyMaterial.append(hello.ephemeral)
      safetyMaterial.append(hello.nonce)
    }
    let safetyDigest = Array(SHA256.hash(data: safetyMaterial))
    let rawSafety = (UInt32(safetyDigest[0]) << 24)
      | (UInt32(safetyDigest[1]) << 16)
      | (UInt32(safetyDigest[2]) << 8)
      | UInt32(safetyDigest[3])
    let safetyValue = rawSafety % 1_000_000
    let safetyCode = String(format: "%03d %03d", safetyValue / 1000, safetyValue % 1000)

    let messageId = "11111111-2222-3333-4444-555555555555"
    let createdAt: Int64 = 1_770_000_001_234
    let messageNonceData = try data("c0c1c2c3c4c5c6c7c8c9cacb")
    let messageNonce = try AES.GCM.Nonce(data: messageNonceData)
    let messageAad = canonical([messageContext, messageId, aDeviceId, bDeviceId, String(createdAt)])
    let sealed = try AES.GCM.seal(
      Data("Hello Telemetry".utf8),
      using: sessionKey,
      nonce: messageNonce,
      authenticating: messageAad
    )
    var ciphertextAndTag = sealed.ciphertext
    ciphertextAndTag.append(sealed.tag)

    let receiptReceivedAt: Int64 = 1_770_000_002_345
    let receiptCanonical = canonical([
      receiptContext,
      messageId,
      aDeviceId,
      bDeviceId,
      String(receiptReceivedAt),
      "DELIVERED"
    ])
    let receiptSignature = try bSigning.signature(for: receiptCanonical)

    let checks: [(String, Bool)] = [
      ("party-a-device-id", aDeviceId == "tlm:device:56475aa75463474c0285df5dbf2bcab7"),
      ("party-b-device-id", bDeviceId == "tlm:device:03396219237f75a64f12aeb7f39723ab"),
      ("party-a-signing-public", hex(aSigningPublic) == "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"),
      ("party-b-signing-public", hex(bSigningPublic) == "2543b92ff1095511476adc8369db6ddc933665a11978dda1404ee1066ca9559d"),
      ("party-a-exchange-public", hex(aExchangePublic) == "358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254"),
      ("party-b-exchange-public", hex(bExchangePublic) == "675dd574ed7789310b3d2e7681f3790b466c773b1521fecf36577958371ea52f"),
      ("party-a-ephemeral-public", hex(aEphemeralPublic) == "493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531d"),
      ("party-b-ephemeral-public", hex(bEphemeralPublic) == "605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c"),
      ("party-a-hello-signature", hex(aSignature) == "3da54bf0bb0fc88e30e7086e064f84a06927ccb09abe3c69a2e2e459e3711d14d54f47129daf8892a690c4ed89b388ce5cf4915bbc51caeec07e0aaaa32f4a07"),
      ("party-b-hello-signature", hex(bSignature) == "81ef660db42bb996515a59a91f28f4eea49e42a9928932858a5390dbd15fd58b21e19ae0584b34203834511d73c6d511ec61034c2becabc0d624241b40c37809"),
      ("x25519-shared-secret", sharedAData == sharedBData && hex(sharedAData) == "c6dea8dd115ef27b7e0953539b2b19e59b7abf3ffd57985ec76de86ec31d1b42"),
      ("hkdf-session-key", hex(sessionKeyData) == "55d8ea80763832c890c0e1695c09bb554ab13ca96b6aacc8976e2cbebea04faf"),
      ("safety-code", safetyCode == "822 483"),
      ("aes-gcm-message", hex(ciphertextAndTag) == "3096040f019c9495a9e613d2101e86c6becc824ade9aea364c5a4dddf660fc"),
      ("signed-delivery-receipt", hex(receiptSignature) == "7541704782943ff245f5712a7bb7d9fb330d1e2875fe46592299c9cf654861f845145144f4578a98d635310f02a9f5ded739a9c8cc134feb5e5c446c25662807")
    ]

    let failures = checks.filter { !$0.1 }.map { $0.0 }
    return [
      "version": "telemetry-m1b-interop/1",
      "passed": failures.isEmpty,
      "checkCount": checks.count,
      "failureCount": failures.count,
      "failures": failures,
      "safetyCode": safetyCode,
      "sessionKeyFingerprint": String(hex(SHA256.hash(data: sessionKeyData).data).prefix(16))
    ]
  }

  private static func canonical(_ parts: [String]) -> Data {
    Data(parts.joined(separator: "\u{001F}").utf8)
  }

  private static func deviceId(_ signingPublicKey: Data) -> String {
    let digest = SHA256.hash(data: signingPublicKey)
    return "tlm:device:" + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
  }

  private static func hex<D: DataProtocol>(_ bytes: D) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
  }

  private static func data(_ value: String) throws -> Data {
    guard value.count.isMultiple(of: 2) else {
      throw NSError(domain: "TelemetryInteropSelfTest", code: 1, userInfo: [NSLocalizedDescriptionKey: "Odd-length hex vector"])
    }
    var result = Data()
    var index = value.startIndex
    while index < value.endIndex {
      let next = value.index(index, offsetBy: 2)
      guard let byte = UInt8(value[index..<next], radix: 16) else {
        throw NSError(domain: "TelemetryInteropSelfTest", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid hex vector"])
      }
      result.append(byte)
      index = next
    }
    return result
  }
}

private extension Digest {
  var data: Data { Data(self) }
}
