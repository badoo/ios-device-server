package com.badoo.automation.deviceserver.util

import java.net.InetAddress
import java.net.NetworkInterface

data class NetworkInterfaceInfo(
    val ip: String, val hostname: String, val networkInterfaceName: String
)

class NetworkUtils {
    companion object {
        fun getAddresses(): List<NetworkInterfaceInfo> {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.displayName.startsWith("en") || it.displayName.startsWith("eth") } // only include Ethernet/Wi-Fi interfaces on macOS / Linux
                .sortedBy { it.displayName }.flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList<InetAddress>().filter { it.address.size == 4 }.filter { !it.isLoopbackAddress }.map {
                        NetworkInterfaceInfo(
                            ip = it.hostAddress, hostname = it.hostName, networkInterfaceName = networkInterface.name
                        )
                    }
                }

            return networkInterfaces.ifEmpty {
                listOf(
                    NetworkInterfaceInfo(
                        ip = "127.0.0.1", hostname = "localhost", networkInterfaceName = "lo0"
                    )
                )
            }
        }
    }
}
