package com.localair.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Hashtable
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Android 4.4's NsdServiceInfo cannot publish TXT records, so use JmDNS. */
class MdnsAdvertiser(private val context: Context) {
    @Volatile private var jmdns: JmDNS? = null
    @Volatile private var worker: Thread? = null

    fun register(port: Int, deviceId: String, name: String) {
        unregister()
        worker = Thread({
            try {
                val address = localAddress(context)
                val advertisement = AirPlayAdvertisement.create(deviceId, name)
                val dns = JmDNS.create(address, advertisement.hostName)
                jmdns = dns
                dns.registerService(ServiceInfo.create(
                    "_airplay._tcp.local.", name, port, 0, 0,
                    Hashtable(advertisement.airplayTxt)
                ))
                dns.registerService(ServiceInfo.create(
                    "_raop._tcp.local.", advertisement.raopName,
                    port, 0, 0, Hashtable(advertisement.raopTxt)
                ))
                Log.i(TAG, "advertising $name at ${address.hostAddress}:$port")
            } catch (error: Throwable) {
                Log.e(TAG, "mDNS registration failed", error)
            }
        }, "AirPlay-mDNS").apply { start() }
    }

    fun unregister() {
        worker?.interrupt()
        worker = null
        val dns = jmdns
        jmdns = null
        if (dns != null) {
            Thread({
                try {
                    dns.unregisterAllServices()
                    dns.close()
                } catch (_: Throwable) {
                }
            }, "AirPlay-mDNS-close").start()
        }
    }

    @Suppress("DEPRECATION")
    private fun localAddress(context: Context): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (network in interfaces) {
                if (!network.isUp || network.isLoopback) continue
                for (address in Collections.list(network.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return address
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo.ipAddress
        return InetAddress.getByAddress(byteArrayOf(
            (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
            (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte()
        ))
    }

    companion object { private const val TAG = "AirPlay44-mDNS" }
}
