package com.telemetry.app.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.telemetry.app.transport.TelemetryGattTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TelemetryBleDiscovery(
    private val context: Context,
    private val onEvent: (DiscoveryEvent) -> Unit
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager.adapter
    private val peerLabels = ConcurrentHashMap<String, String>()
    private val peerCounter = AtomicInteger(1)
    private val serviceUuid = ParcelUuid(TelemetryGattTransport.SERVICE_UUID)

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val localKey = result.device.address
            val ephemeralId = peerLabels.computeIfAbsent(localKey) {
                "peer-${peerCounter.getAndIncrement().toString().padStart(3, '0')}"
            }
            onEvent(
                DiscoveryEvent.PeerSeen(
                    PeerCandidate(
                        ephemeralId = ephemeralId,
                        radioAddress = localKey,
                        rssi = result.rssi,
                        lastSeenAtMs = System.currentTimeMillis()
                    )
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            onEvent(DiscoveryEvent.Error("BLE scan failed: $errorCode"))
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onEvent(DiscoveryEvent.Error("BLE advertise failed: $errorCode"))
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasRequiredPermissions()) {
            onEvent(DiscoveryEvent.Error("Nearby-device permission is required"))
            return
        }
        val currentAdapter = adapter
        if (currentAdapter == null || !currentAdapter.isEnabled) {
            onEvent(DiscoveryEvent.Error("Bluetooth is unavailable or disabled"))
            return
        }
        val scanner = currentAdapter.bluetoothLeScanner
        if (scanner == null) {
            onEvent(DiscoveryEvent.Error("BLE scanner unavailable"))
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, scanCallback)

        val advertiser = currentAdapter.bluetoothLeAdvertiser
        if (advertiser != null) {
            val advertiseSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(serviceUuid)
                .build()
            advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }
        onEvent(DiscoveryEvent.Started(advertising = advertiser != null))
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasRequiredPermissions()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        peerLabels.clear()
        onEvent(DiscoveryEvent.Stopped)
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
