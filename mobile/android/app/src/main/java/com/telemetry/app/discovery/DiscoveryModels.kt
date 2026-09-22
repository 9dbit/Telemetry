package com.telemetry.app.discovery

data class PeerCandidate(
    val ephemeralId: String,
    val rssi: Int,
    val lastSeenAtMs: Long
)

sealed interface DiscoveryEvent {
    data class Started(val advertising: Boolean) : DiscoveryEvent
    data class PeerSeen(val peer: PeerCandidate) : DiscoveryEvent
    data class Error(val message: String) : DiscoveryEvent
    data object Stopped : DiscoveryEvent
}

object PresencePrivacyPolicy {
    const val STABLE_DEVICE_ID_IN_ADVERTISEMENT = false
    const val PRECISE_LOCATION_DERIVED_FROM_SCAN = false
}
