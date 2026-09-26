import fs from 'node:fs';
import path from 'node:path';

const sourcePath = path.join(
  process.cwd(),
  'modules/telemetry-ios-native/ios/TelemetryIosNativeModule.swift'
);

if (!fs.existsSync(sourcePath)) {
  throw new Error(`Telemetry iOS native source not found: ${sourcePath}`);
}

let source = fs.readFileSync(sourcePath, 'utf8');
const marker = 'private var subscribedCentrals: [String: CBCentral] = [:]';

if (source.includes(marker)) {
  console.log('[telemetry] bidirectional BLE source patch already applied');
  process.exit(0);
}

function replaceOne(before, after, label) {
  const count = source.split(before).length - 1;
  if (count !== 1) {
    throw new Error(`${label}: expected 1 source match, found ${count}`);
  }
  source = source.replace(before, after);
}

replaceOne(
`  private var sessions: [String: PeerSession] = [:]
  private var receipts: [String: Data] = [:]
`,
`  private var sessions: [String: PeerSession] = [:]
  private var receipts: [String: Data] = [:]
  private var messageServerCharacteristic: CBMutableCharacteristic?
  private var receiptServerCharacteristic: CBMutableCharacteristic?
  private var subscribedCentrals: [String: CBCentral] = [:]
`,
'BLE server state'
);

replaceOne(
`    receipts.removeAll()
    emit("onState", ["state": "stopped"])
`,
`    receipts.removeAll()
    messageServerCharacteristic = nil
    receiptServerCharacteristic = nil
    subscribedCentrals.removeAll()
    emit("onState", ["state": "stopped"])
`,
'stop cleanup'
);

replaceOne(
`  func trustPeer(deviceId: String) -> Bool {
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
`,
`  func trustPeer(deviceId: String) -> Bool {
    guard let pair = sessions.first(where: { $0.value.remoteHello?.deviceId == deviceId }),
          let remote = pair.value.remoteHello else { return false }
    trust.verify(remote)
    emitTrustedIfReady(peerId: pair.key)
    return true
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
    emit("onTrusted", ["peerId": peerId, "deviceId": remote.deviceId])
  }

  func sendText(peerId: String, text: String) throws -> String {
    guard let session = sessions[peerId],
          let remote = session.remoteHello,
          let key = session.sessionKey else {
      throw TelemetryNativeError.message("Secure peer session is incomplete")
    }
    guard trust.isVerified(remote) else {
      throw TelemetryNativeError.message("Compare and trust the safety code first")
    }

    let message = try TelemetryCryptoEngine.encryptText(
      key: key,
      senderId: crypto.deviceId,
      recipientId: remote.deviceId,
      text: text
    )
    let frame = try FrameCodec.encodeMessage(message)

    if let peripheral = discovered[peerId],
       peripheral.state == .connected,
       let characteristic = characteristics[peerId]?[messageUUID] {
      guard frame.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
        throw TelemetryNativeError.message("Encrypted frame exceeds current BLE write size")
      }
      session.lastOutboundMessageId = message.messageId
      peripheral.writeValue(frame, for: characteristic, type: .withResponse)
      return message.messageId
    }

    if let central = subscribedCentrals[peerId],
       let manager = peripheralManager,
       let characteristic = messageServerCharacteristic {
      guard frame.count <= central.maximumUpdateValueLength else {
        throw TelemetryNativeError.message("Encrypted frame exceeds current BLE notification size")
      }
      session.lastOutboundMessageId = message.messageId
      guard manager.updateValue(frame, for: characteristic, onSubscribedCentrals: [central]) else {
        session.lastOutboundMessageId = nil
        throw TelemetryNativeError.message("BLE transmit queue is busy; retry")
      }
      return message.messageId
    }

    throw TelemetryNativeError.message("Peer BLE connection is not active in either direction")
  }
`,
'bidirectional send path'
);

replaceOne(
`      let message = CBMutableCharacteristic(
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
`,
`      let message = CBMutableCharacteristic(
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
`,
'GATT characteristic capabilities'
);

replaceOne(
`    if trust.isVerified(remote) {
      emit("onTrusted", ["peerId": peerId, "deviceId": remote.deviceId])
    } else {
`,
`    if trust.isVerified(remote) {
      emitTrustedIfReady(peerId: peerId)
    } else {
`,
'trusted hello gating'
);

replaceOne(
`  private func acceptMessage(peerId: String, frame: Data) throws {
`,
`  private func acceptMessage(peerId: String, frame: Data) throws -> Data {
`,
'acceptMessage return type'
);

replaceOne(
`    let clear = try TelemetryCryptoEngine.decryptText(key: key, message: message)
    receipts[peerId] = try FrameCodec.encodeReceipt(crypto.createReceipt(for: message))
    emit("onMessage", [
      "peerId": peerId,
      "deviceId": remote.deviceId,
      "messageId": message.messageId,
      "text": clear
    ])
`,
`    let clear = try TelemetryCryptoEngine.decryptText(key: key, message: message)
    let receipt = try FrameCodec.encodeReceipt(crypto.createReceipt(for: message))
    emit("onMessage", [
      "peerId": peerId,
      "deviceId": remote.deviceId,
      "messageId": message.messageId,
      "text": clear
    ])
    return receipt
`,
'signed receipt return path'
);

replaceOne(
`    characteristics.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    emit("onState", ["state": "disconnected", "detail": peerId])
  }
`,
`    characteristics.removeValue(forKey: peerId)
    sessions.removeValue(forKey: peerId)
    emit("onState", ["state": "disconnected", "detail": peerId])

    guard desiredRunning else { return }
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self, weak peripheral] in
      guard let self, let peripheral, self.desiredRunning, peripheral.state == .disconnected else { return }
      self.emit("onState", ["state": "reconnecting", "detail": peerId])
      self.centralManager?.connect(peripheral, options: nil)
    }
  }
`,
'BLE reconnect'
);

replaceOne(
`    characteristics[peerId] = map

    guard let helloCharacteristic = map[helloUUID] else {
`,
`    characteristics[peerId] = map

    if let messageCharacteristic = map[messageUUID], messageCharacteristic.properties.contains(.notify) {
      peripheral.setNotifyValue(true, for: messageCharacteristic)
    }

    guard let helloCharacteristic = map[helloUUID] else {
`,
'message notification subscription'
);

replaceOne(
`      if characteristic.uuid == helloUUID {
        try acceptHello(peerId: peripheral.identifier.uuidString, frame: data)
      } else if characteristic.uuid == receiptUUID {
        try acceptReceipt(peerId: peripheral.identifier.uuidString, frame: data)
      }
`,
`      if characteristic.uuid == helloUUID {
        try acceptHello(peerId: peripheral.identifier.uuidString, frame: data)
      } else if characteristic.uuid == messageUUID {
        let peerId = peripheral.identifier.uuidString
        let receipt = try acceptMessage(peerId: peerId, frame: data)
        guard let receiptCharacteristic = characteristics[peerId]?[receiptUUID] else {
          throw TelemetryNativeError.message("Telemetry receipt characteristic is unavailable")
        }
        guard receipt.count <= peripheral.maximumWriteValueLength(for: .withResponse) else {
          throw TelemetryNativeError.message("Signed receipt exceeds current BLE write size")
        }
        peripheral.writeValue(receipt, for: receiptCharacteristic, type: .withResponse)
      } else if characteristic.uuid == receiptUUID {
        try acceptReceipt(peerId: peripheral.identifier.uuidString, frame: data)
      }
`,
'notification receive path'
);

replaceOne(
`  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
`,
`  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
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
`,
'peripheral subscription tracking'
);

replaceOne(
`        if request.characteristic.uuid == helloUUID {
          try acceptHello(peerId: peerId, frame: value)
        } else if request.characteristic.uuid == messageUUID {
          try acceptMessage(peerId: peerId, frame: value)
        } else {
          result = .requestNotSupported
        }
`,
`        if request.characteristic.uuid == helloUUID {
          try acceptHello(peerId: peerId, frame: value)
        } else if request.characteristic.uuid == messageUUID {
          receipts[peerId] = try acceptMessage(peerId: peerId, frame: value)
        } else if request.characteristic.uuid == receiptUUID {
          try acceptReceipt(peerId: peerId, frame: value)
        } else {
          result = .requestNotSupported
        }
`,
'peripheral write routing'
);

fs.writeFileSync(sourcePath, source);
console.log('[telemetry] applied hardware-validated bidirectional BLE patch');
