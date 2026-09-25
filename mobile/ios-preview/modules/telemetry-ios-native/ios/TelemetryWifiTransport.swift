import Foundation
import Network

private enum TelemetryWifiTransportError: LocalizedError {
  case message(String)

  var errorDescription: String? {
    switch self {
    case .message(let value): return value
    }
  }
}

final class TelemetryWifiTransport {
  static let serviceType = "_telemetry._tcp"
  private static let maxPacketBytes = 8 * 1024 * 1024

  typealias FrameHandler = (String, Data, @escaping (Result<Data, Error>) -> Void) -> Void

  var onFrame: FrameHandler?
  var onPeerChange: ((String, Bool) -> Void)?
  var onLog: ((String) -> Void)?

  private let queue = DispatchQueue(label: "com.telemetry.ios.wifi", qos: .userInitiated)
  private var listener: NWListener?
  private var browser: NWBrowser?
  private var endpoints: [String: NWEndpoint] = [:]
  private var localDeviceId = ""
  private var sentBytes = 0
  private var receivedBytes = 0
  private var successfulFrames = 0
  private var failedFrames = 0
  private var failNextSendAfterConnectForTest = false

  func start(localDeviceId: String) throws {
    stop()
    self.localDeviceId = localDeviceId

    let listenerParameters = NWParameters.tcp
    listenerParameters.includePeerToPeer = true
    let listener = try NWListener(using: listenerParameters, on: .any)
    listener.service = NWListener.Service(
      name: Self.serviceName(for: localDeviceId),
      type: Self.serviceType
    )
    listener.newConnectionHandler = { [weak self] connection in
      self?.accept(connection)
    }
    listener.stateUpdateHandler = { [weak self] state in
      self?.onLog?("wifi-listener \(state)")
    }
    listener.start(queue: queue)
    self.listener = listener

    startBrowser()
  }

  func stop() {
    listener?.cancel()
    browser?.cancel()
    listener = nil
    browser = nil
    let previous = endpoints.keys
    endpoints.removeAll()
    for deviceId in previous {
      onPeerChange?(deviceId, false)
    }
  }

  func isAvailable(deviceId: String) -> Bool {
    queue.sync { endpoints[deviceId] != nil }
  }

  func armNextSendFailureForTest() {
    queue.sync { failNextSendAfterConnectForTest = true }
  }

  func diagnostics() -> [String: Any] {
    queue.sync {
      [
        "availablePeers": Array(endpoints.keys).sorted(),
        "sentBytes": sentBytes,
        "receivedBytes": receivedBytes,
        "successfulFrames": successfulFrames,
        "failedFrames": failedFrames
      ]
    }
  }

  func send(deviceId: String, frame: Data) async throws -> Data {
    try await withCheckedThrowingContinuation { continuation in
      send(deviceId: deviceId, frame: frame) { result in
        continuation.resume(with: result)
      }
    }
  }

  private func startBrowser() {
    let parameters = NWParameters.tcp
    parameters.includePeerToPeer = true
    let browser = NWBrowser(
      for: .bonjour(type: Self.serviceType, domain: nil),
      using: parameters
    )
    browser.stateUpdateHandler = { [weak self] state in
      self?.onLog?("wifi-browser \(state)")
    }
    browser.browseResultsChangedHandler = { [weak self] results, _ in
      guard let self else { return }
      var next: [String: NWEndpoint] = [:]
      for result in results {
        guard case let .service(name, _, _, _) = result.endpoint else { continue }
        let deviceId = Self.deviceId(fromServiceName: name)
        guard deviceId != self.localDeviceId else { continue }
        next[deviceId] = result.endpoint
      }
      let removed = Set(self.endpoints.keys).subtracting(next.keys)
      let added = Set(next.keys).subtracting(self.endpoints.keys)
      self.endpoints = next
      for deviceId in removed { self.onPeerChange?(deviceId, false) }
      for deviceId in added { self.onPeerChange?(deviceId, true) }
    }
    browser.start(queue: queue)
    self.browser = browser
  }

  private func send(
    deviceId: String,
    frame: Data,
    completion: @escaping (Result<Data, Error>) -> Void
  ) {
    queue.async { [weak self] in
      guard let self else { return }
      guard let endpoint = self.endpoints[deviceId] else {
        completion(.failure(TelemetryWifiTransportError.message("Wi-Fi peer is unavailable")))
        return
      }

      let parameters = NWParameters.tcp
      parameters.includePeerToPeer = true
      let connection = NWConnection(to: endpoint, using: parameters)
      var finished = false

      func finish(_ result: Result<Data, Error>) {
        guard !finished else { return }
        finished = true
        connection.cancel()
        completion(result)
      }

      connection.stateUpdateHandler = { [weak self] state in
        guard let self else { return }
        switch state {
        case .ready:
          if self.failNextSendAfterConnectForTest {
            self.failNextSendAfterConnectForTest = false
            self.failedFrames += 1
            finish(.failure(TelemetryWifiTransportError.message("Injected one-shot Wi-Fi transport failure")))
            return
          }
          self.sendRequest(connection, deviceId: deviceId, frame: frame, finish: finish)
        case .failed(let error):
          self.failedFrames += 1
          finish(.failure(error))
        case .cancelled:
          break
        default:
          break
        }
      }
      connection.start(queue: self.queue)
      self.queue.asyncAfter(deadline: .now() + 8) {
        if !finished {
          self.failedFrames += 1
          finish(.failure(TelemetryWifiTransportError.message("Wi-Fi send timed out")))
        }
      }
    }
  }

  private func sendRequest(
    _ connection: NWConnection,
    deviceId: String,
    frame: Data,
    finish: @escaping (Result<Data, Error>) -> Void
  ) {
    do {
      let packet = try encodePacket(from: localDeviceId, payload: frame)
      sentBytes += packet.count
      connection.send(content: packet, completion: .contentProcessed { [weak self] error in
        guard let self else { return }
        if let error {
          self.failedFrames += 1
          finish(.failure(error))
          return
        }
        self.receiveLine(connection, buffer: Data()) { result in
          switch result {
          case .success(let data):
            do {
              let response = try self.decodePacket(data)
              guard response.from == deviceId else {
                throw TelemetryWifiTransportError.message("Wi-Fi receipt identity mismatch")
              }
              self.receivedBytes += data.count
              self.successfulFrames += 1
              finish(.success(response.payload))
            } catch {
              self.failedFrames += 1
              finish(.failure(error))
            }
          case .failure(let error):
            self.failedFrames += 1
            finish(.failure(error))
          }
        }
      })
    } catch {
      failedFrames += 1
      finish(.failure(error))
    }
  }

  private func accept(_ connection: NWConnection) {
    connection.stateUpdateHandler = { [weak self, weak connection] state in
      guard let self, let connection else { return }
      switch state {
      case .ready:
        self.receiveLine(connection, buffer: Data()) { result in
          switch result {
          case .success(let data):
            self.receivedBytes += data.count
            do {
              let request = try self.decodePacket(data)
              self.handleIncoming(
                connection,
                remoteDeviceId: request.from,
                payload: request.payload
              )
            } catch {
              connection.cancel()
            }
          case .failure:
            connection.cancel()
          }
        }
      case .failed:
        connection.cancel()
      default:
        break
      }
    }
    connection.start(queue: queue)
  }

  private func handleIncoming(
    _ connection: NWConnection,
    remoteDeviceId: String,
    payload: Data
  ) {
    guard remoteDeviceId != localDeviceId, let onFrame else {
      connection.cancel()
      return
    }
    onFrame(remoteDeviceId, payload) { [weak self, weak connection] result in
      guard let self, let connection else { return }
      self.queue.async {
        switch result {
        case .success(let receipt):
          do {
            let packet = try self.encodePacket(from: self.localDeviceId, payload: receipt)
            self.sentBytes += packet.count
            connection.send(content: packet, completion: .contentProcessed { error in
              if error == nil { self.successfulFrames += 1 }
              else { self.failedFrames += 1 }
              connection.cancel()
            })
          } catch {
            self.failedFrames += 1
            connection.cancel()
          }
        case .failure:
          self.failedFrames += 1
          connection.cancel()
        }
      }
    }
  }

  private func receiveLine(
    _ connection: NWConnection,
    buffer: Data,
    completion: @escaping (Result<Data, Error>) -> Void
  ) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
      guard let self else { return }
      var next = buffer
      if let data { next.append(data) }
      if next.count > Self.maxPacketBytes {
        completion(.failure(TelemetryWifiTransportError.message("Wi-Fi packet exceeds limit")))
        return
      }
      if let newline = next.firstIndex(of: 0x0A) {
        completion(.success(Data(next[..<newline])))
        return
      }
      if let error {
        completion(.failure(error))
        return
      }
      if isComplete {
        completion(.failure(TelemetryWifiTransportError.message("Wi-Fi connection closed before complete frame")))
        return
      }
      self.receiveLine(connection, buffer: next, completion: completion)
    }
  }
  private func encodePacket(from deviceId: String, payload: Data) throws -> Data {
    let value: [String: Any] = [
      "v": "m1d",
      "from": deviceId,
      "payload": payload.base64EncodedString()
    ]
    var data = try JSONSerialization.data(withJSONObject: value, options: [])
    data.append(0x0A)
    return data
  }

  private func decodePacket(_ data: Data) throws -> (from: String, payload: Data) {
    guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          value["v"] as? String == "m1d",
          let from = value["from"] as? String,
          let encoded = value["payload"] as? String,
          let payload = Data(base64Encoded: encoded) else {
      throw TelemetryWifiTransportError.message("Malformed Wi-Fi transport packet")
    }
    return (from, payload)
  }

  private static func serviceName(for deviceId: String) -> String {
    if deviceId.hasPrefix("tlm:device:") {
      return String(deviceId.dropFirst("tlm:device:".count))
    }
    return deviceId.replacingOccurrences(of: ":", with: "-")
  }

  private static func deviceId(fromServiceName name: String) -> String {
    "tlm:device:\(name)"
  }
}
