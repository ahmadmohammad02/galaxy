package com.galaxytv.app

import android.util.Log
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

internal object DeviceMacAddress {
    private const val TAG = "DeviceMacAddress"
    private val preferredInterfaceNames = listOf("wlan0", "eth0", "en0")

    fun read(): String? {
        return try {
            val availableInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val interfaces = Collections.list(availableInterfaces)
            val orderedInterfaces = preferredInterfaceNames.mapNotNull { name ->
                interfaces.firstOrNull { it.name == name }
            } + interfaces.filterNot { networkInterface ->
                preferredInterfaceNames.contains(networkInterface.name)
            }

            orderedInterfaces.firstNotNullOfOrNull { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@firstNotNullOfOrNull null
                }

                val address = networkInterface.hardwareAddress
                    ?.takeIf(::isUsableAddress)
                    ?: return@firstNotNullOfOrNull null
                address.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            }
        } catch (exception: SocketException) {
            Log.w(TAG, "Unable to read a network interface MAC address", exception)
            null
        }
    }

    private fun isUsableAddress(address: ByteArray): Boolean {
        if (address.size != 6) return false
        if (address.all { it == 0.toByte() } || address.all { it == 0xFF.toByte() }) return false
        return !address.contentEquals(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00))
    }
}
