import Foundation
import CryptoKit
import Security

struct TelemetryMediaPeerContext {
  let deviceId: String
  let signingPublicKey: Data
  let pairwiseKey: SymmetricKey
}

private enum TelemetryMediaError: LocalizedError {
  case message(String)

  var errorDescription: String? {
    switch self {
    case .message(let value): return value
    }
  }
}

private struct TelemetryMediaManifest: Codable {
  let version: String
  let assetId: String
  let kind: String
  let mimeType: String
  let fileName: String
  let byteLength: Int64
  let chunkBytes: Int
  let chunkCount: Int
  let sha256: String
  let contentKey: String
  let createdAt: String
}

private struct TelemetryMediaManifestPayload: Codable {
  let kind: String
  let manifest: TelemetryMediaManifest
}

private struct TelemetryMediaAck: Codable {
  let kind: String
  let assetId: String
  let chunkCount: Int
  let receivedRanges: [[Int]]
  let complete: Bool
}

private struct TelemetryMediaOutgoingState: Codable {
  let assetId: String
  let peerDeviceId: String
  let kind: String
  let fileName: String
  let byteLength: Int64
  let chunkCount: Int
  var acked: [Int]
  let expiresAt: Int64
}

private struct TelemetryMediaChunk {
  let assetId: String
  let index: Int
  let count: Int
  let plainBytes: Int
  let nonce: Data
  let ciphertext: Data
  let tag: Data
}

private struct TelemetryControlEnvelope {
  let protocolVersion: String
  let messageId: String
  let conversationId: String
  let senderId: String
  let recipientId: String
  let createdAt: String
  let hopLimit: Int
  let contentType: String
  let nonce: Data
  let ciphertext: Data
  let tag: Data
  let signature: Data
}

final class TelemetryMediaRuntime {
  private static let manifestVersion = "telemetry/media-manifest/0.1"
  private static let chunkVersion = "telemetry/media-chunk/0.1"
  private static let manifestContentType = "application/telemetry+media-manifest"
  private static let controlContentType = "application/telemetry+media-control"
  private static let chunkBytes = 256 * 1024
  private static let maxChunkBytes = 1024 * 1024
  private static let maxAssetBytes: Int64 = 512 * 1024 * 1024
  private static let maxChunks = 8192
  private static let transferTtlMs: Int64 = 7 * 24 * 60 * 60 * 1000

  private let localDeviceId: String
  private let localSigningPublicKey: Data
  private let sign: (Data) throws -> Data
  private let contextForDeviceId: (String) -> TelemetryMediaPeerContext?
  private let wifiAvailable: (String) -> Bool
  private let sendFrame: (String, Data) async throws -> Data
  private let emit: (String, [String: Any]) -> Void
  private let root: URL
  private let outgoingRoot: URL
  private let incomingRoot: URL
  private let openRoot: URL

  init(
    localDeviceId: String,
    localSigningPublicKey: Data,
    sign: @escaping (Data) throws -> Data,
    contextForDeviceId: @escaping (String) -> TelemetryMediaPeerContext?,
    wifiAvailable: @escaping (String) -> Bool,
    sendFrame: @escaping (String, Data) async throws -> Data,
    emit: @escaping (String, [String: Any]) -> Void
  ) throws {
    self.localDeviceId = localDeviceId
    self.localSigningPublicKey = localSigningPublicKey
    self.sign = sign
    self.contextForDeviceId = contextForDeviceId
    self.wifiAvailable = wifiAvailable
    self.sendFrame = sendFrame
    self.emit = emit

    let support = try FileManager.default.url(
      for: .applicationSupportDirectory,
      in: .userDomainMask,
      appropriateFor: nil,
      create: true
    )
    root = support.appendingPathComponent("Telemetry/media-v1", isDirectory: true)
    outgoingRoot = root.appendingPathComponent("outgoing", isDirectory: true)
    incomingRoot = root.appendingPathComponent("incoming", isDirectory: true)
    openRoot = root.appendingPathComponent("open", isDirectory: true)
    for url in [root, outgoingRoot, incomingRoot, openRoot] {
      try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
  }

  static func handles(_ frame: Data) -> Bool {
    guard frame.count >= 4 else { return false }
    let magic = String(data: frame.prefix(4), encoding: .ascii)
    return magic == "TCE1" || magic == "TMC1"
  }

  func sendMedia(
    peerDeviceId: String,
    fileURL: URL,
    kind: String,
    mimeType: String,
    fileName: String
  ) async throws -> String {
    guard let context = contextForDeviceId(peerDeviceId) else {
      throw TelemetryMediaError.message("Trusted peer key material is unavailable")
    }
    let prepared = try preparePackage(
      context: context,
      fileURL: fileURL,
      kind: kind,
      mimeType: mimeType,
      fileName: fileName
    )
    // Picker/cache URLs are ephemeral. Preserve a durable local copy inside
    // Application Support so the sender's preview survives picker dismissal,
    // tab changes, app relaunches and later gallery hydration.
    let previewURL = outgoingAssetDir(prepared.assetId)
      .appendingPathComponent("preview-\(Self.safeFileName(fileName))")
    var previewUri = fileURL.absoluteString
    do {
      if previewURL.standardizedFileURL != fileURL.standardizedFileURL {
        try? FileManager.default.removeItem(at: previewURL)
        try FileManager.default.copyItem(at: fileURL, to: previewURL)
      }
      previewUri = previewURL.absoluteString
    } catch {
      // Transfer integrity uses encrypted chunks already persisted below. A preview
      // copy failure must not discard the queued media; UI can fall back to a card.
    }
    emitMedia(state: "outgoingQueued", record: prepared, extra: ["localUri": previewUri])
    guard wifiAvailable(peerDeviceId) else {
      emitMedia(
        state: "paused",
        record: prepared,
        extra: ["message": "Queued securely. Waiting for a direct peer path."]
      )
      return prepared.assetId
    }
    do {
      try await sendPrepared(prepared)
    } catch {
      let queued = (try? loadOutgoingState(assetId: prepared.assetId)) ?? prepared
      emitMedia(state: "paused", record: queued, extra: ["message": error.localizedDescription])
    }
    return prepared.assetId
  }

  func resumePending(peerDeviceId: String? = nil) async -> Int {
    var resumed = 0
    for record in loadAllOutgoingStates() {
      if let peerDeviceId, record.peerDeviceId != peerDeviceId { continue }
      if record.expiresAt <= Self.nowMs() { continue }
      if record.acked.count >= record.chunkCount { continue }
      guard wifiAvailable(record.peerDeviceId), contextForDeviceId(record.peerDeviceId) != nil else { continue }
      do {
        try await sendPrepared(record)
        resumed += 1
      } catch {
        emitMedia(state: "paused", record: record, extra: ["message": error.localizedDescription])
      }
    }
    return resumed
  }

  func handleIncoming(deviceId: String, frame: Data) throws -> Data {
    guard let context = contextForDeviceId(deviceId) else {
      throw TelemetryMediaError.message("Incoming media sender is not a trusted contact")
    }
    if String(data: frame.prefix(4), encoding: .ascii) == "TCE1" {
      let opened = try Self.openControlWire(
        frame,
        context: context,
        localDeviceId: localDeviceId,
        expectedSenderId: deviceId
      )
      guard opened.envelope.contentType == Self.manifestContentType else {
        throw TelemetryMediaError.message("Unsupported incoming media control frame")
      }
      let payload = try JSONDecoder().decode(TelemetryMediaManifestPayload.self, from: opened.plaintext)
      guard payload.kind == "media-manifest" else {
        throw TelemetryMediaError.message("Invalid media manifest payload kind")
      }
      try Self.validateManifest(payload.manifest)
      let assetDir = incomingAssetDir(payload.manifest.assetId)
      try FileManager.default.createDirectory(at: assetDir, withIntermediateDirectories: true)
      try Self.writeAtomically(frame, to: assetDir.appendingPathComponent("manifest.tce1"))
      let received = try storedIncomingIndices(manifest: payload.manifest)
      emitIncomingProgress(payload.manifest, received: received.count, senderDeviceId: deviceId)
      try materializeIfComplete(manifest: payload.manifest, senderDeviceId: deviceId, assetDir: assetDir)
      return try createAckWire(manifest: payload.manifest, receivedIndices: received, context: context)
    }

    let chunk = try Self.decodeChunkWire(frame)
    let assetDir = incomingAssetDir(chunk.assetId)
    let manifestWireURL = assetDir.appendingPathComponent("manifest.tce1")
    guard let manifestWire = try? Data(contentsOf: manifestWireURL) else {
      throw TelemetryMediaError.message("Media chunk arrived before its trusted manifest")
    }
    let opened = try Self.openControlWire(
      manifestWire,
      context: context,
      localDeviceId: localDeviceId,
      expectedSenderId: deviceId
    )
    let payload = try JSONDecoder().decode(TelemetryMediaManifestPayload.self, from: opened.plaintext)
    let manifest = payload.manifest
    try Self.validateManifest(manifest)
    try Self.validateChunk(chunk, manifest: manifest)

    let chunkURL = incomingChunkURL(assetDir: assetDir, index: chunk.index)
    if let existing = try? Data(contentsOf: chunkURL) {
      guard existing == frame else {
        throw TelemetryMediaError.message("Encrypted media chunk conflict rejected")
      }
    } else {
      try Self.writeAtomically(frame, to: chunkURL)
    }

    let received = try storedIncomingIndices(manifest: manifest)
    emitIncomingProgress(manifest, received: received.count, senderDeviceId: deviceId)
    try materializeIfComplete(manifest: manifest, senderDeviceId: deviceId, assetDir: assetDir)
    return try createAckWire(manifest: manifest, receivedIndices: received, context: context)
  }

  private func preparePackage(
    context: TelemetryMediaPeerContext,
    fileURL: URL,
    kind: String,
    mimeType: String,
    fileName: String
  ) throws -> TelemetryMediaOutgoingState {
    guard ["photo", "video", "file"].contains(kind) else {
      throw TelemetryMediaError.message("Unsupported media kind")
    }
    guard !mimeType.isEmpty, mimeType.count <= 127 else {
      throw TelemetryMediaError.message("Invalid media MIME type")
    }
    guard !fileName.isEmpty, fileName.count <= 255 else {
      throw TelemetryMediaError.message("Invalid media file name")
    }

    let values = try fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
    guard values.isRegularFile == true, let size = values.fileSize, size > 0 else {
      throw TelemetryMediaError.message("Selected media file is unavailable")
    }
    let byteLength = Int64(size)
    guard byteLength <= Self.maxAssetBytes else {
      throw TelemetryMediaError.message("Media exceeds the current 512 MB asset limit")
    }
    let chunkCount = Int((byteLength + Int64(Self.chunkBytes) - 1) / Int64(Self.chunkBytes))
    guard chunkCount >= 1, chunkCount <= Self.maxChunks else {
      throw TelemetryMediaError.message("Media requires too many encrypted chunks")
    }

    let assetId = UUID().uuidString.lowercased()
    let assetDir = outgoingAssetDir(assetId)
    try FileManager.default.createDirectory(at: assetDir, withIntermediateDirectories: true)
    let digest = try Self.sha256File(fileURL)
    let contentKeyData = try Self.randomBytes(count: 32)
    let manifest = TelemetryMediaManifest(
      version: Self.manifestVersion,
      assetId: assetId,
      kind: kind,
      mimeType: mimeType,
      fileName: fileName,
      byteLength: byteLength,
      chunkBytes: Self.chunkBytes,
      chunkCount: chunkCount,
      sha256: digest,
      contentKey: Self.b64url(contentKeyData),
      createdAt: Self.isoNow()
    )
    try Self.validateManifest(manifest)

    let source = try FileHandle(forReadingFrom: fileURL)
    defer { try? source.close() }
    let key = SymmetricKey(data: contentKeyData)
    for index in 0..<chunkCount {
      guard let plain = try source.read(upToCount: Self.chunkBytes), !plain.isEmpty else {
        throw TelemetryMediaError.message("Selected media ended before expected byte length")
      }
      let wire = try Self.encryptChunkWire(
        plain: plain,
        key: key,
        assetId: assetId,
        index: index,
        count: chunkCount
      )
      try Self.writeAtomically(wire, to: outgoingChunkURL(assetDir: assetDir, index: index))
    }

    let payload = TelemetryMediaManifestPayload(kind: "media-manifest", manifest: manifest)
    let plaintext = try JSONEncoder().encode(payload)
    let manifestWire = try Self.createControlWire(
      localDeviceId: localDeviceId,
      recipientId: context.deviceId,
      pairwiseKey: context.pairwiseKey,
      contentType: Self.manifestContentType,
      plaintext: plaintext,
      conversationId: "chat:\(context.deviceId)",
      sign: sign
    )
    try Self.writeAtomically(manifestWire, to: assetDir.appendingPathComponent("manifest.tce1"))

    let record = TelemetryMediaOutgoingState(
      assetId: assetId,
      peerDeviceId: context.deviceId,
      kind: kind,
      fileName: fileName,
      byteLength: byteLength,
      chunkCount: chunkCount,
      acked: [],
      expiresAt: Self.nowMs() + Self.transferTtlMs
    )
    try saveOutgoingState(record)
    return record
  }

  private func sendPrepared(_ initial: TelemetryMediaOutgoingState) async throws {
    var record = try loadOutgoingState(assetId: initial.assetId)
    guard let context = contextForDeviceId(record.peerDeviceId) else {
      throw TelemetryMediaError.message("Trusted peer key material is unavailable")
    }
    guard wifiAvailable(record.peerDeviceId) else {
      throw TelemetryMediaError.message("Direct peer path is unavailable; transfer remains queued")
    }
    let assetDir = outgoingAssetDir(record.assetId)
    let manifestWire = try Data(contentsOf: assetDir.appendingPathComponent("manifest.tce1"))
    let manifestAckWire = try await sendFrame(record.peerDeviceId, manifestWire)
    record = try applyAckWire(manifestAckWire, to: record, context: context)
    try saveOutgoingState(record)
    emitMedia(state: "outgoingProgress", record: record)

    for index in 0..<record.chunkCount {
      if record.acked.contains(index) { continue }
      guard wifiAvailable(record.peerDeviceId) else {
        throw TelemetryMediaError.message("Direct peer path disappeared; encrypted chunks remain queued")
      }
      let wire = try Data(contentsOf: outgoingChunkURL(assetDir: assetDir, index: index))
      let ackWire = try await sendFrame(record.peerDeviceId, wire)
      record = try applyAckWire(ackWire, to: record, context: context)
      try saveOutgoingState(record)
      emitMedia(state: "outgoingProgress", record: record)
    }

    guard record.acked.count == record.chunkCount else {
      throw TelemetryMediaError.message("Media transfer paused with unacknowledged encrypted chunks")
    }
    emitMedia(state: "outgoingComplete", record: record, extra: ["verified": true])
  }

  private func applyAckWire(
    _ wire: Data,
    to original: TelemetryMediaOutgoingState,
    context: TelemetryMediaPeerContext
  ) throws -> TelemetryMediaOutgoingState {
    let opened = try Self.openControlWire(
      wire,
      context: context,
      localDeviceId: localDeviceId,
      expectedSenderId: context.deviceId
    )
    guard opened.envelope.contentType == Self.controlContentType else {
      throw TelemetryMediaError.message("Unexpected media ACK content type")
    }
    let ack = try JSONDecoder().decode(TelemetryMediaAck.self, from: opened.plaintext)
    guard ack.kind == "media-chunk-ack",
          ack.assetId == original.assetId,
          ack.chunkCount == original.chunkCount else {
      throw TelemetryMediaError.message("Media ACK does not match outgoing asset")
    }
    let expanded = try Self.expandRanges(ack.receivedRanges, chunkCount: ack.chunkCount)
    var next = original
    next.acked = Array(Set(original.acked).union(expanded)).sorted()
    if ack.complete, next.acked.count != next.chunkCount {
      throw TelemetryMediaError.message("Complete media ACK is missing chunks")
    }
    return next
  }

  private func createAckWire(
    manifest: TelemetryMediaManifest,
    receivedIndices: [Int],
    context: TelemetryMediaPeerContext
  ) throws -> Data {
    let ack = TelemetryMediaAck(
      kind: "media-chunk-ack",
      assetId: manifest.assetId,
      chunkCount: manifest.chunkCount,
      receivedRanges: Self.compactRanges(receivedIndices),
      complete: receivedIndices.count == manifest.chunkCount
    )
    let plaintext = try JSONEncoder().encode(ack)
    return try Self.createControlWire(
      localDeviceId: localDeviceId,
      recipientId: context.deviceId,
      pairwiseKey: context.pairwiseKey,
      contentType: Self.controlContentType,
      plaintext: plaintext,
      conversationId: "chat:\(context.deviceId)",
      sign: sign
    )
  }

  private func materializeIfComplete(
    manifest: TelemetryMediaManifest,
    senderDeviceId: String,
    assetDir: URL
  ) throws {
    let received = try storedIncomingIndices(manifest: manifest)
    guard received.count == manifest.chunkCount else { return }
    let readyMarker = assetDir.appendingPathComponent("ready.json")
    if FileManager.default.fileExists(atPath: readyMarker.path) { return }

    let contentKey = try Self.unb64url(manifest.contentKey)
    guard contentKey.count == 32 else { throw TelemetryMediaError.message("Invalid media content key") }
    let key = SymmetricKey(data: contentKey)
    let targetDir = openRoot.appendingPathComponent(manifest.assetId, isDirectory: true)
    try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)
    let safeName = Self.safeFileName(manifest.fileName)
    let finalURL = targetDir.appendingPathComponent(safeName)
    let tempURL = targetDir.appendingPathComponent(".partial-\(UUID().uuidString)")
    FileManager.default.createFile(atPath: tempURL.path, contents: nil)
    let output = try FileHandle(forWritingTo: tempURL)
    var hasher = SHA256()
    var total: Int64 = 0
    do {
      for index in 0..<manifest.chunkCount {
        let wire = try Data(contentsOf: incomingChunkURL(assetDir: assetDir, index: index))
        let chunk = try Self.decodeChunkWire(wire)
        try Self.validateChunk(chunk, manifest: manifest)
        let clear = try Self.decryptChunk(chunk, key: key)
        try output.write(contentsOf: clear)
        hasher.update(data: clear)
        total += Int64(clear.count)
      }
      try output.close()
    } catch {
      try? output.close()
      try? FileManager.default.removeItem(at: tempURL)
      throw error
    }

    let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
    guard total == manifest.byteLength, digest == manifest.sha256 else {
      try? FileManager.default.removeItem(at: tempURL)
      throw TelemetryMediaError.message("Received media failed final size/SHA-256 verification")
    }
    try? FileManager.default.removeItem(at: finalURL)
    try FileManager.default.moveItem(at: tempURL, to: finalURL)
    let marker: [String: Any] = ["localUri": finalURL.absoluteString, "verifiedAt": Self.nowMs()]
    let markerData = try JSONSerialization.data(withJSONObject: marker)
    try Self.writeAtomically(markerData, to: readyMarker)
    emit("onMedia", [
      "state": "incomingReady",
      "assetId": manifest.assetId,
      "kind": manifest.kind,
      "fileName": manifest.fileName,
      "byteLength": manifest.byteLength,
      "receivedChunks": manifest.chunkCount,
      "totalChunks": manifest.chunkCount,
      "peerDeviceId": senderDeviceId,
      "localUri": finalURL.absoluteString,
      "sha256": manifest.sha256,
      "verified": true
    ])
  }

  private func emitIncomingProgress(_ manifest: TelemetryMediaManifest, received: Int, senderDeviceId: String) {
    emit("onMedia", [
      "state": "incomingProgress",
      "assetId": manifest.assetId,
      "kind": manifest.kind,
      "fileName": manifest.fileName,
      "byteLength": manifest.byteLength,
      "receivedChunks": received,
      "totalChunks": manifest.chunkCount,
      "peerDeviceId": senderDeviceId
    ])
  }

  private func emitMedia(
    state: String,
    record: TelemetryMediaOutgoingState,
    extra: [String: Any] = [:]
  ) {
    var payload: [String: Any] = [
      "state": state,
      "assetId": record.assetId,
      "kind": record.kind,
      "fileName": record.fileName,
      "byteLength": record.byteLength,
      "acknowledgedChunks": record.acked.count,
      "totalChunks": record.chunkCount,
      "peerDeviceId": record.peerDeviceId,
      "transport": "wifi-direct"
    ]
    extra.forEach { payload[$0.key] = $0.value }
    emit("onMedia", payload)
  }

  private func outgoingAssetDir(_ assetId: String) -> URL {
    outgoingRoot.appendingPathComponent(assetId, isDirectory: true)
  }

  private func incomingAssetDir(_ assetId: String) -> URL {
    incomingRoot.appendingPathComponent(assetId, isDirectory: true)
  }

  private func outgoingChunkURL(assetDir: URL, index: Int) -> URL {
    assetDir.appendingPathComponent(String(format: "chunk-%08d.tmc1", index))
  }

  private func incomingChunkURL(assetDir: URL, index: Int) -> URL {
    assetDir.appendingPathComponent(String(format: "chunk-%08d.tmc1", index))
  }

  private func stateURL(assetId: String) -> URL {
    outgoingAssetDir(assetId).appendingPathComponent("state.json")
  }

  private func saveOutgoingState(_ state: TelemetryMediaOutgoingState) throws {
    let data = try JSONEncoder().encode(state)
    try Self.writeAtomically(data, to: stateURL(assetId: state.assetId))
  }

  private func loadOutgoingState(assetId: String) throws -> TelemetryMediaOutgoingState {
    try JSONDecoder().decode(TelemetryMediaOutgoingState.self, from: Data(contentsOf: stateURL(assetId: assetId)))
  }

  private func loadAllOutgoingStates() -> [TelemetryMediaOutgoingState] {
    guard let dirs = try? FileManager.default.contentsOfDirectory(at: outgoingRoot, includingPropertiesForKeys: nil) else { return [] }
    return dirs.compactMap { dir in
      guard let data = try? Data(contentsOf: dir.appendingPathComponent("state.json")) else { return nil }
      return try? JSONDecoder().decode(TelemetryMediaOutgoingState.self, from: data)
    }
  }

  private func storedIncomingIndices(manifest: TelemetryMediaManifest) throws -> [Int] {
    let assetDir = incomingAssetDir(manifest.assetId)
    var indices: [Int] = []
    for index in 0..<manifest.chunkCount {
      let url = incomingChunkURL(assetDir: assetDir, index: index)
      if FileManager.default.fileExists(atPath: url.path) { indices.append(index) }
    }
    return indices
  }

  private static func validateManifest(_ manifest: TelemetryMediaManifest) throws {
    guard manifest.version == manifestVersion else { throw TelemetryMediaError.message("Unsupported media manifest version") }
    guard manifest.assetId.count >= 8, manifest.assetId.count <= 128 else { throw TelemetryMediaError.message("Invalid media asset ID") }
    guard ["photo", "video", "file"].contains(manifest.kind) else { throw TelemetryMediaError.message("Unsupported media kind") }
    guard !manifest.mimeType.isEmpty, manifest.mimeType.count <= 127 else { throw TelemetryMediaError.message("Invalid media MIME type") }
    guard !manifest.fileName.isEmpty, manifest.fileName.count <= 255 else { throw TelemetryMediaError.message("Invalid media file name") }
    guard manifest.byteLength >= 1, manifest.byteLength <= maxAssetBytes else { throw TelemetryMediaError.message("Invalid media byte length") }
    guard manifest.chunkBytes >= 16 * 1024, manifest.chunkBytes <= maxChunkBytes else { throw TelemetryMediaError.message("Invalid media chunk size") }
    guard manifest.chunkCount >= 1, manifest.chunkCount <= maxChunks else { throw TelemetryMediaError.message("Invalid media chunk count") }
    let expected = Int((manifest.byteLength + Int64(manifest.chunkBytes) - 1) / Int64(manifest.chunkBytes))
    guard expected == manifest.chunkCount else { throw TelemetryMediaError.message("Media chunk count does not match byte length") }
    guard manifest.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw TelemetryMediaError.message("Invalid media SHA-256") }
    guard (try? unb64url(manifest.contentKey).count) == 32 else { throw TelemetryMediaError.message("Invalid media content key") }
    guard validISO8601(manifest.createdAt) else { throw TelemetryMediaError.message("Invalid media createdAt") }
  }

  private static func validateChunk(_ chunk: TelemetryMediaChunk, manifest: TelemetryMediaManifest) throws {
    guard chunk.assetId == manifest.assetId, chunk.count == manifest.chunkCount else { throw TelemetryMediaError.message("Media chunk/manifest mismatch") }
    guard chunk.index >= 0, chunk.index < manifest.chunkCount else { throw TelemetryMediaError.message("Invalid media chunk index") }
    let expected = chunk.index == manifest.chunkCount - 1
      ? Int(manifest.byteLength - Int64(chunk.index * manifest.chunkBytes))
      : manifest.chunkBytes
    guard chunk.plainBytes == expected, chunk.ciphertext.count == expected else { throw TelemetryMediaError.message("Media chunk size does not match manifest") }
    guard chunk.nonce.count == 12, chunk.tag.count == 16 else { throw TelemetryMediaError.message("Malformed media AEAD fields") }
  }

  private static func encryptChunkWire(
    plain: Data,
    key: SymmetricKey,
    assetId: String,
    index: Int,
    count: Int
  ) throws -> Data {
    let nonceData = try randomBytes(count: 12)
    let nonce = try AES.GCM.Nonce(data: nonceData)
    let aad = chunkAAD(assetId: assetId, index: index, count: count, plainBytes: plain.count)
    let box = try AES.GCM.seal(plain, using: key, nonce: nonce, authenticating: aad)
    let chunk = TelemetryMediaChunk(
      assetId: assetId,
      index: index,
      count: count,
      plainBytes: plain.count,
      nonce: nonceData,
      ciphertext: box.ciphertext,
      tag: box.tag
    )
    return try encodeChunkWire(chunk)
  }

  private static func decryptChunk(_ chunk: TelemetryMediaChunk, key: SymmetricKey) throws -> Data {
    let nonce = try AES.GCM.Nonce(data: chunk.nonce)
    let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: chunk.ciphertext, tag: chunk.tag)
    let aad = chunkAAD(assetId: chunk.assetId, index: chunk.index, count: chunk.count, plainBytes: chunk.plainBytes)
    return try AES.GCM.open(box, using: key, authenticating: aad)
  }

  private static func chunkAAD(assetId: String, index: Int, count: Int, plainBytes: Int) -> Data {
    Data("{\"version\":\"telemetry/media-chunk/0.1\",\"assetId\":\(jsonString(assetId)),\"index\":\(index),\"count\":\(count),\"plainBytes\":\(plainBytes)}".utf8)
  }

  private static func encodeChunkWire(_ chunk: TelemetryMediaChunk) throws -> Data {
    let asset = Data(chunk.assetId.utf8)
    guard !asset.isEmpty, asset.count <= 128 else { throw TelemetryMediaError.message("Invalid media asset ID") }
    guard chunk.ciphertext.count <= maxChunkBytes else { throw TelemetryMediaError.message("Media ciphertext too large") }
    var out = Data("TMC1".utf8)
    appendU16(asset.count, to: &out)
    out.append(asset)
    appendU32(chunk.index, to: &out)
    appendU32(chunk.count, to: &out)
    appendU32(chunk.plainBytes, to: &out)
    out.append(chunk.nonce)
    out.append(chunk.tag)
    appendU32(chunk.ciphertext.count, to: &out)
    out.append(chunk.ciphertext)
    return out
  }

  private static func decodeChunkWire(_ data: Data) throws -> TelemetryMediaChunk {
    var reader = DataReader(data)
    guard try reader.take(4) == Data("TMC1".utf8) else { throw TelemetryMediaError.message("Unsupported media wire magic") }
    let assetLength = Int(try reader.u16())
    guard assetLength >= 1, assetLength <= 128,
          let assetId = String(data: try reader.take(assetLength), encoding: .utf8) else {
      throw TelemetryMediaError.message("Invalid media wire asset ID")
    }
    let index = Int(try reader.u32())
    let count = Int(try reader.u32())
    let plainBytes = Int(try reader.u32())
    let nonce = try reader.take(12)
    let tag = try reader.take(16)
    let cipherLength = Int(try reader.u32())
    guard cipherLength >= 1, cipherLength <= maxChunkBytes else { throw TelemetryMediaError.message("Invalid media ciphertext length") }
    let ciphertext = try reader.take(cipherLength)
    guard reader.remaining == 0 else { throw TelemetryMediaError.message("Unexpected trailing media wire bytes") }
    return TelemetryMediaChunk(assetId: assetId, index: index, count: count, plainBytes: plainBytes, nonce: nonce, ciphertext: ciphertext, tag: tag)
  }

  private static func createControlWire(
    localDeviceId: String,
    recipientId: String,
    pairwiseKey: SymmetricKey,
    contentType: String,
    plaintext: Data,
    conversationId: String,
    sign: (Data) throws -> Data
  ) throws -> Data {
    guard !plaintext.isEmpty, plaintext.count <= 64 * 1024 else { throw TelemetryMediaError.message("Control plaintext length invalid") }
    let nonceData = try randomBytes(count: 12)
    let nonce = try AES.GCM.Nonce(data: nonceData)
    let box = try AES.GCM.seal(plaintext, using: pairwiseKey, nonce: nonce)
    var envelope = TelemetryControlEnvelope(
      protocolVersion: "telemetry/0.1",
      messageId: UUID().uuidString.lowercased(),
      conversationId: conversationId,
      senderId: localDeviceId,
      recipientId: recipientId,
      createdAt: isoNow(),
      hopLimit: 8,
      contentType: contentType,
      nonce: nonceData,
      ciphertext: box.ciphertext,
      tag: box.tag,
      signature: Data()
    )
    let signature = try sign(canonicalControlBytes(envelope))
    envelope = TelemetryControlEnvelope(
      protocolVersion: envelope.protocolVersion,
      messageId: envelope.messageId,
      conversationId: envelope.conversationId,
      senderId: envelope.senderId,
      recipientId: envelope.recipientId,
      createdAt: envelope.createdAt,
      hopLimit: envelope.hopLimit,
      contentType: envelope.contentType,
      nonce: envelope.nonce,
      ciphertext: envelope.ciphertext,
      tag: envelope.tag,
      signature: signature
    )
    return try encodeControlWire(envelope)
  }

  private static func openControlWire(
    _ data: Data,
    context: TelemetryMediaPeerContext,
    localDeviceId: String,
    expectedSenderId: String
  ) throws -> (envelope: TelemetryControlEnvelope, plaintext: Data) {
    let envelope = try decodeControlWire(data)
    guard envelope.protocolVersion == "telemetry/0.1",
          envelope.senderId == expectedSenderId,
          envelope.recipientId == localDeviceId else {
      throw TelemetryMediaError.message("Control envelope identity binding mismatch")
    }
    let expectedDeviceId = deviceId(context.signingPublicKey)
    guard expectedDeviceId == envelope.senderId else { throw TelemetryMediaError.message("Control sender public key mismatch") }
    let pub = try Curve25519.Signing.PublicKey(rawRepresentation: context.signingPublicKey)
    guard pub.isValidSignature(envelope.signature, for: canonicalControlBytes(envelope)) else {
      throw TelemetryMediaError.message("Invalid control envelope signature")
    }
    let nonce = try AES.GCM.Nonce(data: envelope.nonce)
    let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: envelope.ciphertext, tag: envelope.tag)
    let plaintext = try AES.GCM.open(box, using: context.pairwiseKey)
    return (envelope, plaintext)
  }

  private static func encodeControlWire(_ envelope: TelemetryControlEnvelope) throws -> Data {
    guard envelope.signature.count == 64 else { throw TelemetryMediaError.message("Control signature must be 64 bytes") }
    var out = Data("TCE1".utf8)
    for value in [envelope.protocolVersion, envelope.messageId, envelope.conversationId, envelope.senderId, envelope.recipientId, envelope.createdAt] {
      try appendString(value, to: &out)
    }
    out.append(UInt8(envelope.hopLimit))
    try appendString(envelope.contentType, to: &out)
    out.append(envelope.nonce)
    appendU32(envelope.ciphertext.count, to: &out)
    out.append(envelope.ciphertext)
    out.append(envelope.tag)
    out.append(envelope.signature)
    return out
  }

  private static func decodeControlWire(_ data: Data) throws -> TelemetryControlEnvelope {
    var reader = DataReader(data)
    guard try reader.take(4) == Data("TCE1".utf8) else { throw TelemetryMediaError.message("Unsupported control wire magic") }
    let protocolVersion = try reader.string()
    let messageId = try reader.string()
    let conversationId = try reader.string()
    let senderId = try reader.string()
    let recipientId = try reader.string()
    let createdAt = try reader.string()
    let hopLimit = Int(try reader.u8())
    let contentType = try reader.string()
    let nonce = try reader.take(12)
    let cipherLength = Int(try reader.u32())
    guard cipherLength >= 1, cipherLength <= 64 * 1024 else { throw TelemetryMediaError.message("Control ciphertext length invalid") }
    let ciphertext = try reader.take(cipherLength)
    let tag = try reader.take(16)
    let signature = try reader.take(64)
    guard reader.remaining == 0 else { throw TelemetryMediaError.message("Unexpected trailing control envelope bytes") }
    guard hopLimit >= 1, hopLimit <= 32 else { throw TelemetryMediaError.message("Invalid control hop limit") }
    return TelemetryControlEnvelope(
      protocolVersion: protocolVersion,
      messageId: messageId,
      conversationId: conversationId,
      senderId: senderId,
      recipientId: recipientId,
      createdAt: createdAt,
      hopLimit: hopLimit,
      contentType: contentType,
      nonce: nonce,
      ciphertext: ciphertext,
      tag: tag,
      signature: signature
    )
  }

  private static func canonicalControlBytes(_ envelope: TelemetryControlEnvelope) -> Data {
    let text = "{\"protocol\":\(jsonString(envelope.protocolVersion)),\"messageId\":\(jsonString(envelope.messageId)),\"conversationId\":\(jsonString(envelope.conversationId)),\"senderId\":\(jsonString(envelope.senderId)),\"recipientId\":\(jsonString(envelope.recipientId)),\"createdAt\":\(jsonString(envelope.createdAt)),\"hopLimit\":\(envelope.hopLimit),\"contentType\":\(jsonString(envelope.contentType)),\"payload\":{\"algorithm\":\"AES-256-GCM\",\"keyAgreement\":\"X25519-HKDF-SHA256\",\"nonce\":\(jsonString(b64url(envelope.nonce))),\"ciphertext\":\(jsonString(b64url(envelope.ciphertext))),\"tag\":\(jsonString(b64url(envelope.tag)))}}"
    return Data(text.utf8)
  }

  private static func compactRanges(_ indices: [Int]) -> [[Int]] {
    let sorted = Array(Set(indices)).sorted()
    guard let first = sorted.first else { return [] }
    var ranges: [[Int]] = []
    var start = first
    var end = first
    for value in sorted.dropFirst() {
      if value == end + 1 { end = value }
      else { ranges.append([start, end]); start = value; end = value }
    }
    ranges.append([start, end])
    return ranges
  }

  private static func expandRanges(_ ranges: [[Int]], chunkCount: Int) throws -> [Int] {
    var values: [Int] = []
    var previousEnd = -1
    for range in ranges {
      guard range.count == 2 else { throw TelemetryMediaError.message("Invalid media ACK range") }
      let start = range[0], end = range[1]
      guard start >= 0, end >= start, end < chunkCount, start > previousEnd else {
        throw TelemetryMediaError.message("Invalid media ACK range bounds")
      }
      values.append(contentsOf: start...end)
      previousEnd = end
    }
    return values
  }

  private static func sha256File(_ url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
      hasher.update(data: data)
    }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
  }

  private static func writeAtomically(_ data: Data, to url: URL) throws {
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let temp = url.deletingLastPathComponent().appendingPathComponent(".tmp-\(UUID().uuidString)")
    try data.write(to: temp, options: .atomic)
    if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    try FileManager.default.moveItem(at: temp, to: url)
  }

  private static func safeFileName(_ value: String) -> String {
    let last = URL(fileURLWithPath: value).lastPathComponent
    return last.isEmpty ? "attachment.bin" : last
  }

  private static func randomBytes(count: Int) throws -> Data {
    var data = Data(count: count)
    let status = data.withUnsafeMutableBytes { raw in
      SecRandomCopyBytes(kSecRandomDefault, count, raw.baseAddress!)
    }
    guard status == errSecSuccess else { throw TelemetryMediaError.message("Secure random generation failed") }
    return data
  }

  private static func validISO8601(_ value: String) -> Bool {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if fractional.date(from: value) != nil { return true }
    let standard = ISO8601DateFormatter()
    standard.formatOptions = [.withInternetDateTime]
    return standard.date(from: value) != nil
  }

  private static func isoNow() -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: Date())
  }

  private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

  private static func deviceId(_ signingPublicKey: Data) -> String {
    let digest = SHA256.hash(data: signingPublicKey)
    return "tlm:device:" + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
  }

  private static func b64url(_ data: Data) -> String {
    data.base64EncodedString()
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }

  private static func unb64url(_ text: String) throws -> Data {
    var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while value.count % 4 != 0 { value.append("=") }
    guard let data = Data(base64Encoded: value) else { throw TelemetryMediaError.message("Invalid base64url") }
    return data
  }

  private static func jsonString(_ value: String) -> String {
    var out = "\""
    for scalar in value.unicodeScalars {
      switch scalar.value {
      case 0x22: out += "\\\""
      case 0x5c: out += "\\\\"
      case 0x08: out += "\\b"
      case 0x0c: out += "\\f"
      case 0x0a: out += "\\n"
      case 0x0d: out += "\\r"
      case 0x09: out += "\\t"
      case 0x00...0x1f: out += String(format: "\\u%04x", scalar.value)
      default: out.unicodeScalars.append(scalar)
      }
    }
    out += "\""
    return out
  }

  private static func appendString(_ value: String, to data: inout Data) throws {
    let bytes = Data(value.utf8)
    guard !bytes.isEmpty, bytes.count <= 1024 else { throw TelemetryMediaError.message("Control string length invalid") }
    appendU16(bytes.count, to: &data)
    data.append(bytes)
  }

  private static func appendU16(_ value: Int, to data: inout Data) {
    data.append(UInt8((value >> 8) & 0xff))
    data.append(UInt8(value & 0xff))
  }

  private static func appendU32(_ value: Int, to data: inout Data) {
    data.append(UInt8((value >> 24) & 0xff))
    data.append(UInt8((value >> 16) & 0xff))
    data.append(UInt8((value >> 8) & 0xff))
    data.append(UInt8(value & 0xff))
  }
}

private struct DataReader {
  private let data: Data
  private var offset: Int = 0

  init(_ data: Data) { self.data = data }

  var remaining: Int { data.count - offset }

  mutating func take(_ count: Int) throws -> Data {
    guard count >= 0, offset + count <= data.count else { throw TelemetryMediaError.message("Truncated media/control wire") }
    let part = data.subdata(in: offset..<(offset + count))
    offset += count
    return part
  }

  mutating func u8() throws -> UInt8 { try take(1)[0] }

  mutating func u16() throws -> UInt16 {
    let b = try take(2)
    return (UInt16(b[0]) << 8) | UInt16(b[1])
  }

  mutating func u32() throws -> UInt32 {
    let b = try take(4)
    return (UInt32(b[0]) << 24) | (UInt32(b[1]) << 16) | (UInt32(b[2]) << 8) | UInt32(b[3])
  }

  mutating func string() throws -> String {
    let count = Int(try u16())
    guard count >= 1, count <= 1024,
          let value = String(data: try take(count), encoding: .utf8) else {
      throw TelemetryMediaError.message("Invalid control string")
    }
    return value
  }
}
