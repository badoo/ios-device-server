package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.device.KnownDevice
import org.junit.Assert
import org.junit.Test

class NodeConfigTest {
    private val config = """
         {
            "type": "devices",
            "user": "user",
            "host": "host",
            "simulator_limit": 1,
            "concurrent_boots": 1,
            "whitelist_apps": [ "bundle.id" ],
            "uninstall_apps": true,
            "devices": [
                {
                    "udid": "c865bdbe652d17cbe2c79566fb046b73fed66a38",
                    "ip": "127.0.0.1",
                    "wifi_address": "00:00:00:00:00:00"
                }
            ]
        }
    """

    @Test
    fun shouldDeserialize() {
        val config = JsonMapper().fromJson<NodeConfig>(config)

        val expected = NodeConfig(
            type = NodeConfig.NodeType.Devices,
            user = "user",
            host = "host",
            simulatorLimit = 1,
            concurrentBoots = 1,
            whitelistApps = setOf("bundle.id"),
            uninstallApps = true,
            knownDevices = listOf(
                KnownDevice(
                    "c865bdbe652d17cbe2c79566fb046b73fed66a38",
                    ipAddress = "127.0.0.1"
                )
            )
        )

        Assert.assertEquals(expected, config)
    }

    @Test
    fun shouldHaveDefaults() {
        val config = JsonMapper().fromJson<NodeConfig>("{}")

        val expected = NodeConfig(
            type = NodeConfig.NodeType.Simulators,
            user = "",
            host = "localhost",
            simulatorLimit = 6,
            concurrentBoots = 3,
            whitelistApps = emptySet(),
            uninstallApps = false,
            knownDevices = emptyList()
        )

        Assert.assertEquals(expected, config)
    }
}