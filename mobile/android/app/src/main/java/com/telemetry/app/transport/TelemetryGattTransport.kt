package com.telemetry.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.crypto.EphemeralKeyPair
import com.telemetry.app.crypto.SessionHello
import com.telemetry.app.crypto.TelemetryCrypto
import com.telemetry.app.mesh.AndroidMeshWireEndpoint
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface SecureTransportEvent {
    data class Connecting(val address: String) : SecureTransportEvent
    data class PendingVerification(
        val address: String,
        val deviceId: String,
        val safetyCode: String
    ) : SecureTransportEvent
    data class SessionTrusted(val address: String, val deviceId: String) : SecureTransportEvent
    data class MessageReceived(val address: String, val deviceId: String, val text: String) : SecureTransportEvent
    data class DeliveryConfirmed(val address: String, val messageId: String) : SecureTransportEvent
    data class MeshRouteUpdated(val deviceId: String, val acceptedRoutes: Int) : SecureTransportEvent
    data class Disconnected(val address: String) : SecureTransportEvent
    data class Error(val message: String) : SecureTransportEvent
}

private data class PeerSession(
    val address: String,
    val localEphemeral: EphemeralKeyPair,
    val localHello: SessionHello,
    var remoteHello: SessionHello? = null,
    var sessionKey: ByteArray? = null,
    var lastOutboundMessageId: String? = null
)

private class ReplayWindow(
    private val maxEntries: Int = 1024,
    private val ttlMs: Long = 24 * 60 * 60 * 1000L
) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(messageId: String, now: Long = System.currentTimeMillis()): Boolean {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttlMs) iterator.remove()
        }
        if (seen.containsKey(messageId)) return false
        seen[messageId] = now
        while (seen.size > maxEntries) seen.remove(seen.entries.first().key)
        return true
    }
}

class TelemetryGattTransport(
    private val context: Context,
    private val identity: DeviceIdentity,
    private val trustStore: AndroidTrustStore,
    private val onEvent: (SecureTransportEvent) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d59")
        val HELLO_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d01")
        val MESSAGE_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d02")
        val RECEIPT_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d03")
        val MESH_RELAY_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d04")
        val MESH_ROUTE_UUID: UUID = UUID.fromString("f0a0c0de-7e1e-4e7f-9a11-54454c454d05")
        const val MAX_TEXT_BYTES = 160
        const val MAX_MESH_GATT_WIRE_BYTES = 480
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val receipts = ConcurrentHashMap<String, ByteArray>()
    private val pendingMeshRoutes = ConcurrentHashMap<String, ByteArray>()
    private val replay = ReplayWindow()
    private val controlCodec = AndroidControlEnvelopeCodec(identity)
    private var server: BluetoothGattServer? = null
    private var meshEndpoint: AndroidMeshWireEndpoint? = null

    fun attachMeshEndpoint(endpoint: AndroidMeshWireEndpoint) {
        meshEndpoint = endpoint
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessions.remove(device.address)
                receipts.remove(device.address)
                pendingMeshRoutes.remove(device.address)
                onEvent(SecureTransportEvent.Disconnected(device.address))
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                HELLO_UUID -> TelemetryFrameCodec.encodeHello(ensureSession(device.address).localHello)
                RECEIPT_UUID -> receipts[device.address] ?: byteArrayOf()
                MESH_ROUTE_UUID -> runCatching {
                    val remote = trustedRemote(device.address)
                    meshEndpoint?.createRouteWire(remote.deviceId, System.currentTimeMillis())
                        ?: error("mesh endpoint unavailable")
                }.getOrNull()
                else -> null
            }
            @SuppressLint("MissingPermission")
            if (value == null || offset != 0) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val ok = when (characteristic.uuid) {
                HELLO_UUID -> acceptHello(device.address, value)
                MESSAGE_UUID -> acceptMessage(device.address, value)
                MESH_RELAY_UUID -> !preparedWrite && offset == 0 && acceptMeshRelay(device.address, value)
                MESH_ROUTE_UUID -> !preparedWrite && offset == 0 && acceptMeshRoute(device.address, value)
                else -> false
            }
            if (responseNeeded) {
                @SuppressLint("MissingPermission")
                server?.sendResponse(
                    device,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    null
                )
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clientGatts.remove(address)
                    sessions.remove(address)
                    pendingMeshRoutes.remove(address)
                    gatt.close()
                    onEvent(SecureTransportEvent.Disconnected(address))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent(SecureTransportEvent.Error("GATT service discovery failed: $status"))
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val hello = service?.getCharacteristic(HELLO_UUID)
            if (hello == null) {
                onEvent(SecureTransportEvent.Error("Nearby device is not a Telemetry M1B peer"))
                return
            }
            val bytes = TelemetryFrameCodec.encodeHello(ensureSession(gatt.device.address).localHello)
            hello.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            hello.value = bytes
            if (!gatt.writeCharacteristic(hello)) {
                onEvent(SecureTransportEvent.Error("Unable to start secure hello exchange"))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent(SecureTransportEvent.Error("GATT write failed: $status"))
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: return
            when (characteristic.uuid) {
                HELLO_UUID -> service.getCharacteristic(HELLO_UUID)?.let(gatt::readCharacteristic)
                MESSAGE_UUID -> service.getCharacteristic(RECEIPT_UUID)?.let(gatt::readCharacteristic)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onEvent(SecureTransportEvent.Error("GATT read failed: $status"))
                return
            }
            when (characteristic.uuid) {
                HELLO_UUID -> acceptHello(gatt.device.address, characteristic.value ?: byteArrayOf())
                RECEIPT_UUID -> acceptReceipt(gatt.device.address, characteristic.value ?: byteArrayOf())
                MESH_ROUTE_UUID -> acceptMeshRoute(gatt.device.address, characteristic.value ?: byteArrayOf())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasConnectPermission()) {
            onEvent(SecureTransportEvent.Error("Bluetooth connect permission is required"))
            return
        }
        if (server != null) return
        val opened = manager.openGattServer(context, serverCallback)
        if (opened == null) {
            onEvent(SecureTransportEvent.Error("Unable to open Telemetry GATT server"))
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                HELLO_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                MESSAGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RECEIPT_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                MESH_RELAY_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                MESH_ROUTE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        opened.addService(service)
        server = opened
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (hasConnectPermission()) {
            clientGatts.values.forEach { runCatching { it.disconnect(); it.close() } }
            runCatching { server?.close() }
        }
        clientGatts.clear()
        sessions.clear()
        receipts.clear()
        pendingMeshRoutes.clear()
        server = null
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasConnectPermission()) {
            onEvent(SecureTransportEvent.Error("Bluetooth connect permission is required"))
            return
        }
        val device = runCatching { manager.adapter.getRemoteDevice(address) }.getOrElse {
            onEvent(SecureTransportEvent.Error("Invalid BLE peer address"))
            return
        }
        ensureSession(address)
        onEvent(SecureTransportEvent.Connecting(address))
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        clientGatts[address] = gatt
    }

    fun trustPeer(deviceId: String): Boolean {
        val entry = sessions.values.firstOrNull { it.remoteHello?.deviceId == deviceId } ?: return false
        val hello = entry.remoteHello ?: return false
        trustStore.verify(hello)
        pendingMeshRoutes.remove(entry.address)?.let { acceptMeshRoute(entry.address, it) }
        onEvent(SecureTransportEvent.SessionTrusted(entry.address, hello.deviceId))
        readRemoteMeshRoute(entry.address)
        return true
    }

    fun isMeshPeerAvailable(peerDeviceId: String): Boolean {
        val session = sessions.values.firstOrNull { it.remoteHello?.deviceId == peerDeviceId } ?: return false
        val remote = session.remoteHello ?: return false
        if (!trustStore.isVerified(remote)) return false
        val gatt = clientGatts[session.address] ?: return false
        val service = gatt.getService(SERVICE_UUID) ?: return false
        return service.getCharacteristic(MESH_RELAY_UUID) != null && service.getCharacteristic(MESH_ROUTE_UUID) != null
    }

    fun createControlEnvelopeWire(
        peerDeviceId: String,
        contentType: String,
        plaintext: ByteArray,
        conversationId: String = UUID.randomUUID().toString(),
        messageId: String = UUID.randomUUID().toString(),
        hopLimit: Int = 8
    ): ByteArray? = runCatching {
        val session = sessions.values.firstOrNull { it.remoteHello?.deviceId == peerDeviceId }
            ?: error("no secure session for control peer")
        val remote = session.remoteHello ?: error("control peer identity unavailable")
        require(trustStore.isVerified(remote)) { "control peer is not trusted" }
        val key = session.sessionKey ?: error("control session key unavailable")
        controlCodec.createAndEncode(
            sessionKey = key,
            recipientId = remote.deviceId,
            contentType = contentType,
            plaintext = plaintext,
            conversationId = conversationId,
            messageId = messageId,
            hopLimit = hopLimit
        )
    }.getOrElse {
        onEvent(SecureTransportEvent.Error("Unable to create secure control envelope: ${it.message}"))
        null
    }

    fun openControlEnvelopeWire(bytes: ByteArray): NativeOpenedControlEnvelope? = runCatching {
        val decoded = controlCodec.decode(bytes)
        val session = sessions.values.firstOrNull { it.remoteHello?.deviceId == decoded.senderId }
            ?: error("no trusted session for control sender")
        val remote = session.remoteHello ?: error("control sender identity unavailable")
        require(trustStore.isVerified(remote)) { "control sender is not trusted" }
        val key = session.sessionKey ?: error("control session key unavailable")
        controlCodec.verifyAndOpen(
            bytes = bytes,
            sessionKey = key,
            signingPublicKey = remote.signingPublicKey,
            expectedRecipientId = identity.deviceId
        )
    }.getOrElse {
        onEvent(SecureTransportEvent.Error("Secure control envelope rejected: ${it.message}"))
        null
    }

    @SuppressLint("MissingPermission")
    fun sendMeshRelayWire(peerDeviceId: String, wire: ByteArray): Boolean =
        sendMeshWire(peerDeviceId, MESH_RELAY_UUID, wire)

    @SuppressLint("MissingPermission")
    fun sendMeshRouteWire(peerDeviceId: String, wire: ByteArray): Boolean =
        sendMeshWire(peerDeviceId, MESH_ROUTE_UUID, wire)

    @SuppressLint("MissingPermission")
    private fun sendMeshWire(peerDeviceId: String, characteristicUuid: UUID, wire: ByteArray): Boolean {
        if (wire.isEmpty() || wire.size > MAX_MESH_GATT_WIRE_BYTES) return false
        val session = sessions.values.firstOrNull { it.remoteHello?.deviceId == peerDeviceId } ?: return false
        val remote = session.remoteHello ?: return false
        if (!trustStore.isVerified(remote)) return false
        val gatt = clientGatts[session.address] ?: return false
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(characteristicUuid) ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = wire
        return gatt.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(address: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_TEXT_BYTES) {
            onEvent(SecureTransportEvent.Error("M1B text must be 1-$MAX_TEXT_BYTES UTF-8 bytes"))
            return false
        }
        val session = sessions[address] ?: return false.also {
            onEvent(SecureTransportEvent.Error("No secure session for peer"))
        }
        val remote = session.remoteHello ?: return false.also {
            onEvent(SecureTransportEvent.Error("Peer identity handshake is incomplete"))
        }
        val key = session.sessionKey ?: return false.also {
            onEvent(SecureTransportEvent.Error("Session key is unavailable"))
        }
        if (!trustStore.isVerified(remote)) {
            onEvent(SecureTransportEvent.Error("Compare the safety code and trust the peer first"))
            return false
        }
        val gatt = clientGatts[address] ?: return false.also {
            onEvent(SecureTransportEvent.Error("Peer GATT connection is not active"))
        }
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_UUID)
            ?: return false.also { onEvent(SecureTransportEvent.Error("Telemetry message channel unavailable")) }
        val message = TelemetryCrypto.encryptText(key, identity.deviceId, remote.deviceId, text)
        val frame = TelemetryFrameCodec.encodeMessage(message)
        session.lastOutboundMessageId = message.messageId
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = frame
        return gatt.writeCharacteristic(characteristic)
    }

    private fun ensureSession(address: String): PeerSession = sessions.computeIfAbsent(address) {
        val ephemeral = TelemetryCrypto.newEphemeral()
        PeerSession(address, ephemeral, TelemetryCrypto.createHello(identity, ephemeral))
    }

    private fun acceptHello(address: String, frame: ByteArray): Boolean = runCatching {
        val remote = TelemetryFrameCodec.decodeHello(frame)
        require(TelemetryCrypto.verifyHello(remote)) { "invalid signed peer hello" }
        require(remote.deviceId != identity.deviceId) { "self connection rejected" }
        val session = ensureSession(address)
        session.remoteHello = remote
        session.sessionKey = TelemetryCrypto.deriveSessionKey(session.localEphemeral, session.localHello, remote)
        val safetyCode = TelemetryCrypto.safetyCode(session.localHello, remote)
        if (trustStore.isVerified(remote)) {
            pendingMeshRoutes.remove(address)?.let { acceptMeshRoute(address, it) }
            onEvent(SecureTransportEvent.SessionTrusted(address, remote.deviceId))
            readRemoteMeshRoute(address)
        } else {
            onEvent(SecureTransportEvent.PendingVerification(address, remote.deviceId, safetyCode))
        }
        true
    }.getOrElse {
        onEvent(SecureTransportEvent.Error("Secure hello rejected: ${it.message}"))
        false
    }

    private fun acceptMessage(address: String, frame: ByteArray): Boolean = runCatching {
        val session = sessions[address] ?: error("unknown peer session")
        val remote = session.remoteHello ?: error("peer identity not established")
        val key = session.sessionKey ?: error("session key unavailable")
        require(trustStore.isVerified(remote)) { "peer is not trusted" }
        val message = TelemetryFrameCodec.decodeMessage(frame)
        require(message.senderId == remote.deviceId) { "sender identity mismatch" }
        require(message.recipientId == identity.deviceId) { "wrong recipient" }
        require(replay.accept(message.messageId)) { "duplicate message rejected" }
        val text = TelemetryCrypto.decryptText(key, message)
        onEvent(SecureTransportEvent.MessageReceived(address, remote.deviceId, text))
        receipts[address] = TelemetryFrameCodec.encodeReceipt(TelemetryCrypto.createReceipt(identity, message))
        true
    }.getOrElse {
        onEvent(SecureTransportEvent.Error("Encrypted message rejected: ${it.message}"))
        false
    }

    private fun acceptMeshRelay(address: String, wire: ByteArray): Boolean = runCatching {
        require(wire.isNotEmpty() && wire.size <= MAX_MESH_GATT_WIRE_BYTES) { "mesh relay wire exceeds BLE limit" }
        val endpoint = meshEndpoint ?: error("mesh endpoint unavailable")
        val remote = trustedRemote(address)
        require(remote.deviceId != identity.deviceId) { "self relay rejected" }
        endpoint.ingestRelayWire(wire, System.currentTimeMillis()) ?: error("mesh relay frame rejected")
        true
    }.getOrElse {
        false
    }

    private fun acceptMeshRoute(address: String, wire: ByteArray): Boolean = runCatching {
        require(wire.isNotEmpty() && wire.size <= MAX_MESH_GATT_WIRE_BYTES) { "mesh route wire exceeds BLE limit" }
        val endpoint = meshEndpoint ?: error("mesh endpoint unavailable")
        val session = sessions[address] ?: error("unknown peer session")
        val remote = session.remoteHello ?: error("peer identity not established")
        require(session.sessionKey != null) { "session key unavailable" }
        if (!trustStore.isVerified(remote)) {
            pendingMeshRoutes[address] = wire.copyOf()
            return@runCatching true
        }
        val now = System.currentTimeMillis()
        val accepted = endpoint.ingestRouteWire(
            bytes = wire,
            fromPeerId = remote.deviceId,
            signingPublicKey = remote.signingPublicKey,
            linkQuality = 0,
            linkTransport = "ble",
            nowEpochMs = now
        )
        if (accepted > 0) {
            endpoint.flush(now)
            onEvent(SecureTransportEvent.MeshRouteUpdated(remote.deviceId, accepted))
        }
        accepted > 0
    }.getOrElse {
        false
    }

    @SuppressLint("MissingPermission")
    private fun readRemoteMeshRoute(address: String) {
        val gatt = clientGatts[address] ?: return
        val route = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESH_ROUTE_UUID) ?: return
        runCatching { gatt.readCharacteristic(route) }
    }

    private fun trustedRemote(address: String): SessionHello {
        val session = sessions[address] ?: error("unknown peer session")
        val remote = session.remoteHello ?: error("peer identity not established")
        require(session.sessionKey != null) { "session key unavailable" }
        require(trustStore.isVerified(remote)) { "peer is not trusted" }
        return remote
    }

    private fun acceptReceipt(address: String, frame: ByteArray): Boolean = runCatching {
        require(frame.isNotEmpty()) { "empty delivery receipt" }
        val session = sessions[address] ?: error("unknown peer session")
        val remote = session.remoteHello ?: error("peer identity not established")
        val receipt = TelemetryFrameCodec.decodeReceipt(frame)
        require(receipt.messageId == session.lastOutboundMessageId) { "receipt does not match outbound message" }
        require(TelemetryCrypto.verifyReceipt(receipt, remote.signingPublicKey)) { "invalid receipt signature" }
        session.lastOutboundMessageId = null
        onEvent(SecureTransportEvent.DeliveryConfirmed(address, receipt.messageId))
        true
    }.getOrElse {
        onEvent(SecureTransportEvent.Error("Delivery receipt rejected: ${it.message}"))
        false
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
