package com.telemetry.app.discovery

import org.junit.Assert.assertFalse
import org.junit.Test

class PresencePrivacyPolicyTest {
    @Test
    fun `BLE advertisement does not expose stable identity`() {
        assertFalse(PresencePrivacyPolicy.STABLE_DEVICE_ID_IN_ADVERTISEMENT)
        assertFalse(PresencePrivacyPolicy.PRECISE_LOCATION_DERIVED_FROM_SCAN)
    }
}
