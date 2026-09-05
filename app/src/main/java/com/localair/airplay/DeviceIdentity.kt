package com.localair.airplay

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface
import java.util.Locale

object DeviceIdentity {
    fun macAddress(context: Context): String {
        networkInterfaceMac("wlan0")?.let { return it }
        networkInterfaceMac("eth0")?.let { return it }
        wifiManagerMac(context)?.let { return it }
        return "02:44:41:50:34:34"
    }

    fun macBytes(context: Context): ByteArray =
        macAddress(context).split(":").map { it.toInt(16).toByte() }.toByteArray()

    fun deviceName(context: Context): String {
        val configured = context.getSharedPreferences("airplay44", Context.MODE_PRIVATE)
            .getString("device_name", null)
        return configured ?: "客厅电视 AirPlay"
    }

    private fun networkInterfaceMac(ifName: String): String? {
        return try {
            val network = NetworkInterface.getByName(ifName) ?: return null
            val bytes = network.hardwareAddress ?: return null
            if (bytes.size != 6 || bytes.all { it == 0.toByte() }) return null
            bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xff) }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiManagerMac(context: Context): String? {
        return try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mac = manager.connectionInfo?.macAddress ?: return null
            if (mac == "02:00:00:00:00:00") null else mac.toUpperCase(Locale.US)
        } catch (_: Throwable) {
            null
        }
    }
}
