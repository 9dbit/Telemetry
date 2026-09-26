import Foundation
import MultipeerConnectivity

private enum TelemetryMPCTransportError: LocalizedError {
  case message(String)
  var errorDescription: String? {
    switch self { case .message(let value): return value }
  }
}

final class TelemetryMPCTransport: NSObject {
  static let serviceType = "tlm-media"
  typealias FrameHandler = (String, Data, @escaping (Result<Data, Error>) -> Void) -> Void

  var onFrame: FrameHandler?
  var onPeerChange: ((String, Bool) -> Void)?
  var onLog: ((String) -> Void)?

  private struct Packet: Codable {
    let version: Int
    let kind: String
    let requestId: String
    let from: String
    let payload: Data
    let error: String?
  }

  private let queue = DispatchQueue(label: "com.telemetry.ios.mpc", qos: .userInitiated)
  private var localDeviceId = ""
  private var localPeer: MCPeerID?
  private var session: MCSession?
  private var advertiser: MCNearbyServiceAdvertiser?
  private var browser: MCNearbyServiceBrowser?
  private var peerByDevice: [String: MCPeerID] = [:]
  private var deviceByPeer: [MCPeerID: String] = [:]
  private var connectedDevices = Set<String>()
  private var invitedPeers = Set<MCPeerID>()
  private var pendingResponses: [String: CheckedContinuation<Data, Error>] = [:]
  private var pendingResponseDevice: [String: String] = [:]

  func start(localDeviceId: String) {
    stop()
    self.localDeviceId = localDeviceId
    let hex = localDeviceId.replacingOccurrences(of: "tlm:device:", with: "")
    let peer = MCPeerID(displayName: String(hex.prefix(48)))
    localPeer = peer
    let session = MCSession(peer: peer, securityIdentity: nil, encryptionPreference: .required)
    session.delegate = self
    self.session = session

    let advertiser = MCNearbyServiceAdvertiser(
      peer: peer,
      discoveryInfo: ["d": localDeviceId],
      serviceType: Self.serviceType
    )
    advertiser.delegate = self
    advertiser.startAdvertisingPeer()
    self.advertiser = advertiser

    let browser = MCNearbyServiceBrowser(peer: peer, serviceType: Self.serviceType)
    browser.delegate = self
    browser.startBrowsingForPeers()
    self.browser = browser
    onLog?("mpc-started")
  }

  func stop() {
    advertiser?.stopAdvertisingPeer()
    browser?.stopBrowsingForPeers()
    session?.disconnect()
    advertiser = nil
    browser = nil
    session = nil
    localPeer = nil
    let previous = connectedDevices
    connectedDevices.removeAll()
    peerByDevice.removeAll()
    deviceByPeer.removeAll()
    invitedPeers.removeAll()
    for (_, continuation) in pendingResponses {
      continuation.resume(throwing: TelemetryMPCTransportError.message("Direct peer session stopped"))
    }
    pendingResponses.removeAll()
    pendingResponseDevice.removeAll()
    for deviceId in previous { onPeerChange?(deviceId, false) }
  }

  func isAvailable(deviceId: String) -> Bool {
    queue.sync { connectedDevices.contains(deviceId) }
  }

  func diagnostics() -> [String: Any] {
    queue.sync { ["connectedPeers": Array(connectedDevices).sorted()] }
  }

  func send(deviceId: String, frame: Data) async throws -> Data {
    try await withCheckedThrowingContinuation { continuation in
      queue.async { [weak self] in
        guard let self, let session = self.session else {
          continuation.resume(throwing: TelemetryMPCTransportError.message("Direct peer session is unavailable"))
          return
        }
        guard self.connectedDevices.contains(deviceId),
              let peer = self.peerByDevice[deviceId],
              session.connectedPeers.contains(peer) else {
          continuation.resume(throwing: TelemetryMPCTransportError.message("Direct peer fallback is not connected"))
          return
        }
        let requestId = UUID().uuidString.lowercased()
        let packet = Packet(version: 1, kind: "request", requestId: requestId, from: self.localDeviceId, payload: frame, error: nil)
        do {
          let data = try JSONEncoder().encode(packet)
          self.pendingResponses[requestId] = continuation
          self.pendingResponseDevice[requestId] = deviceId
          try session.send(data, toPeers: [peer], with: .reliable)
          self.onLog?("mpc-send request=\(requestId) bytes=\(frame.count) peer=\(deviceId)")
          self.queue.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, let pending = self.pendingResponses.removeValue(forKey: requestId) else { return }
            self.pendingResponseDevice.removeValue(forKey: requestId)
            pending.resume(throwing: TelemetryMPCTransportError.message("Direct peer response timed out"))
          }
        } catch {
          self.pendingResponses.removeValue(forKey: requestId)
          self.pendingResponseDevice.removeValue(forKey: requestId)
          continuation.resume(throwing: error)
        }
      }
    }
  }

  private func remember(peer: MCPeerID, deviceId: String) {
    guard deviceId != localDeviceId, deviceId.hasPrefix("tlm:device:") else { return }
    peerByDevice[deviceId] = peer
    deviceByPeer[peer] = deviceId
  }

  private func maybeInvite(peer: MCPeerID, deviceId: String) {
    guard let browser, let session else { return }
    guard localDeviceId < deviceId else { return }
    guard !session.connectedPeers.contains(peer), !invitedPeers.contains(peer) else { return }
    invitedPeers.insert(peer)
    let context = localDeviceId.data(using: .utf8)
    browser.invitePeer(peer, to: session, withContext: context, timeout: 12)
    onLog?("mpc-invite peer=\(deviceId)")
  }

  private func sendResponse(to peer: MCPeerID, requestId: String, result: Result<Data, Error>) {
    guard let session else { return }
    let packet: Packet
    switch result {
    case .success(let data):
      packet = Packet(version: 1, kind: "response", requestId: requestId, from: localDeviceId, payload: data, error: nil)
    case .failure(let error):
      packet = Packet(version: 1, kind: "response", requestId: requestId, from: localDeviceId, payload: Data(), error: error.localizedDescription)
    }
    do {
      try session.send(try JSONEncoder().encode(packet), toPeers: [peer], with: .reliable)
    } catch {
      onLog?("mpc-response-failed request=\(requestId) error=\(error.localizedDescription)")
    }
  }

  private func handle(_ data: Data, from peer: MCPeerID) {
    do {
      let packet = try JSONDecoder().decode(Packet.self, from: data)
      guard packet.version == 1, packet.from != localDeviceId else { return }
      remember(peer: peer, deviceId: packet.from)
      if packet.kind == "response" {
        guard let continuation = pendingResponses.removeValue(forKey: packet.requestId) else { return }
        let expected = pendingResponseDevice.removeValue(forKey: packet.requestId)
        guard expected == nil || expected == packet.from else {
          continuation.resume(throwing: TelemetryMPCTransportError.message("Direct peer response identity mismatch"))
          return
        }
        if let error = packet.error {
          continuation.resume(throwing: TelemetryMPCTransportError.message(error))
        } else {
          continuation.resume(returning: packet.payload)
        }
        return
      }
      guard packet.kind == "request", let onFrame else { return }
      onFrame(packet.from, packet.payload) { [weak self] result in
        self?.queue.async { self?.sendResponse(to: peer, requestId: packet.requestId, result: result) }
      }
    } catch {
      onLog?("mpc-decode-failed error=\(error.localizedDescription)")
    }
  }
}

extension TelemetryMPCTransport: MCNearbyServiceBrowserDelegate {
  func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
    queue.async { [weak self] in
      guard let self, let deviceId = info?["d"] else { return }
      self.remember(peer: peerID, deviceId: deviceId)
      self.onLog?("mpc-found peer=\(deviceId)")
      self.maybeInvite(peer: peerID, deviceId: deviceId)
    }
  }

  func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
    queue.async { [weak self] in
      guard let self else { return }
      self.invitedPeers.remove(peerID)
      if let deviceId = self.deviceByPeer[peerID], !self.connectedDevices.contains(deviceId) {
        self.peerByDevice.removeValue(forKey: deviceId)
        self.deviceByPeer.removeValue(forKey: peerID)
      }
    }
  }

  func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
    onLog?("mpc-browser-failed \(error.localizedDescription)")
  }
}

extension TelemetryMPCTransport: MCNearbyServiceAdvertiserDelegate {
  func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
    queue.async { [weak self] in
      guard let self, let session = self.session else { invitationHandler(false, nil); return }
      let deviceId = context.flatMap { String(data: $0, encoding: .utf8) }
      if let deviceId { self.remember(peer: peerID, deviceId: deviceId) }
      invitationHandler(true, session)
      self.onLog?("mpc-invitation peer=\(deviceId ?? peerID.displayName)")
    }
  }

  func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
    onLog?("mpc-advertiser-failed \(error.localizedDescription)")
  }
}

extension TelemetryMPCTransport: MCSessionDelegate {
  func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
    queue.async { [weak self] in
      guard let self else { return }
      let deviceId = self.deviceByPeer[peerID]
      switch state {
      case .connected:
        self.invitedPeers.remove(peerID)
        if let deviceId {
          let inserted = self.connectedDevices.insert(deviceId).inserted
          if inserted { self.onPeerChange?(deviceId, true) }
          self.onLog?("mpc-connected peer=\(deviceId)")
        }
      case .notConnected:
        self.invitedPeers.remove(peerID)
        if let deviceId, self.connectedDevices.remove(deviceId) != nil {
          self.onPeerChange?(deviceId, false)
        }
        self.onLog?("mpc-disconnected peer=\(deviceId ?? peerID.displayName)")
      case .connecting:
        self.onLog?("mpc-connecting peer=\(deviceId ?? peerID.displayName)")
      @unknown default:
        break
      }
    }
  }

  func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
    queue.async { [weak self] in self?.handle(data, from: peerID) }
  }

  func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
  func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
  func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}
