package com.telemetry.app.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.telemetry.app.mesh.NativePeerLinkState
import com.telemetry.app.mesh.WifiLocalMeshRelayPort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AndroidWifiLocalMeshPort(
    context: Context,
    private val onRelayWire: (ByteArray) -> Boolean,
    private val onMediaChunkWire: (ByteArray) -> Boolean = { false }
) : WifiLocalMeshRelayPort {
    companion object {
        const val SERVICE_TYPE = "_telemetry._tcp."
        const val MAX_WIRE_BYTES = 2 * 1024 * 1024
        private const val SERVICE_PREFIX = "tlm-"
        private const val PACKET_MAGIC = 0x544c5731 // TLW1
        private const val PACKET_KIND_RELAY = 1
        private const val PACKET_KIND_MEDIA_CHUNK = 2
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val SOCKET_TIMEOUT_MS = 3_000
    }

    private data class ResolvedService(
        val host: InetAddress,
        val port: Int
    )

    private data class PeerBinding(
        val serviceId: String,
        val authToken: String
    )

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val random = SecureRandom()
    private val executor = Executors.newCachedThreadPool()
    private val started = AtomicBoolean(false)
    private val resolved = ConcurrentHashMap<String, ResolvedService>()
    private val bindings = ConcurrentHashMap<String, PeerBinding>()

    val serviceId: String = randomHex(6)
    private val authToken: String = randomHex(16)
    val localCapability: String = "wifi-local:$serviceId:$authToken"

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val remoteId = serviceIdFromName(serviceInfo.serviceName) ?: return
            if (remoteId == serviceId) return
            @Suppress("DEPRECATION")
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val id = serviceIdFromName(serviceInfo.serviceName) ?: return
                    if (id == serviceId || serviceInfo.port !in 1..65535) return
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host ?: return
                    resolved[id] = ResolvedService(host, serviceInfo.port)
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            serviceIdFromName(serviceInfo.serviceName)?.let(resolved::remove)
        }
    }

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return true
        return runCatching {
            val server = ServerSocket(0)
            server.reuseAddress = true
            serverSocket = server
            executor.execute { acceptLoop(server) }

            val info = NsdServiceInfo().apply {
                serviceName = "$SERVICE_PREFIX$serviceId"
                serviceType = SERVICE_TYPE
                port = server.localPort
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            true
        }.getOrElse {
            started.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            false
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        runCatching { nsd.unregisterService(registrationListener) }
        runCatching { serverSocket?.close() }
        serverSocket = null
        resolved.clear()
        bindings.clear()
    }

    fun bindPeerCapabilities(peerId: String, capabilities: List<String>): Boolean {
        val parsed = capabilities.firstNotNullOfOrNull(::parseCapability) ?: run {
            bindings.remove(peerId)
            return false
        }
        bindings[peerId] = parsed
        return true
    }

    override fun peerState(peerId: String): NativePeerLinkState {
        val binding = bindings[peerId]
        val available = binding != null && resolved.containsKey(binding.serviceId)
        return NativePeerLinkState(
            available = available,
            quality = if (available) 10 else null,
            metered = false
        )
    }

    override fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean =
        sendPacket(peerId, PACKET_KIND_RELAY, bytes)

    fun sendMediaChunkWire(peerId: String, bytes: ByteArray): Boolean =
        sendPacket(peerId, PACKET_KIND_MEDIA_CHUNK, bytes)

    private fun sendPacket(peerId: String, kind: Int, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_WIRE_BYTES) return false
        val binding = bindings[peerId] ?: return false
        val service = resolved[binding.serviceId] ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.connect(InetSocketAddress(service.host, service.port), CONNECT_TIMEOUT_MS)
                val out = DataOutputStream(socket.getOutputStream().buffered())
                out.writeInt(PACKET_MAGIC)
                out.writeByte(kind)
                val auth = binding.authToken.toByteArray(Charsets.US_ASCII)
                out.writeByte(auth.size)
                out.write(auth)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                socket.getInputStream().read() == 1
            }
        }.getOrDefault(false)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (started.get() && !server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            executor.execute { handleIncoming(socket) }
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream().buffered())
                require(input.readInt() == PACKET_MAGIC) { "invalid wifi-local packet magic" }
                val kind = input.readUnsignedByte()
                require(kind == PACKET_KIND_RELAY || kind == PACKET_KIND_MEDIA_CHUNK) { "unsupported wifi-local packet kind" }
                val authLength = input.readUnsignedByte()
                require(authLength in 1..64) { "invalid wifi-local auth length" }
                val auth = ByteArray(authLength).also(input::readFully)
                require(
                    MessageDigest.isEqual(auth, authToken.toByteArray(Charsets.US_ASCII))
                ) { "wifi-local auth rejected" }
                val payloadLength = input.readInt()
                require(payloadLength in 1..MAX_WIRE_BYTES) { "invalid wifi-local payload length" }
                val payload = ByteArray(payloadLength).also(input::readFully)
                val accepted = when (kind) {
                    PACKET_KIND_RELAY -> onRelayWire(payload)
                    PACKET_KIND_MEDIA_CHUNK -> onMediaChunkWire(payload)
                    else -> false
                }
                socket.getOutputStream().write(if (accepted) 1 else 0)
                socket.getOutputStream().flush()
            }.onFailure {
                runCatching {
                    socket.getOutputStream().write(0)
                    socket.getOutputStream().flush()
                }
            }
        }
    }

    private fun parseCapability(value: String): PeerBinding? {
        val parts = value.split(':')
        if (parts.size != 3 || parts[0] != "wifi-local") return null
        val remoteServiceId = parts[1]
        val remoteAuth = parts[2]
        if (!remoteServiceId.matches(Regex("[0-9a-f]{12}"))) return null
        if (!remoteAuth.matches(Regex("[0-9a-f]{32}"))) return null
        return PeerBinding(remoteServiceId, remoteAuth)
    }

    private fun serviceIdFromName(name: String): String? {
        if (!name.startsWith(SERVICE_PREFIX)) return null
        val id = name.removePrefix(SERVICE_PREFIX)
        return id.takeIf { it.matches(Regex("[0-9a-f]{12}")) }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes)
        .also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }
}
