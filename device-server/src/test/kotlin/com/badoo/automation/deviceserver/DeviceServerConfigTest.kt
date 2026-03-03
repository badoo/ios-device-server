package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.device.ConfiguredDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceServerConfigTest {

    @Test
    fun shouldDeserializeSimulatorsOnly() {
        val json = """
            {
                "timeouts": { "device": 600 },
                "simulators": { "limit": 10, "concurrent_boots": 2 }
            }
        """.trimIndent()

        val config = JsonMapper().fromJson<DeviceServerConfig>(json)

        assertEquals(600, config.timeouts["device"]?.toInt())
        assertNotNull(config.simulators)
        assertEquals(10, config.simulators!!.simulatorLimit)
        assertEquals(2, config.simulators.concurrentBoots)
        assertNull(config.devices)
        assertNull(config.publicHostName)
    }

    @Test
    fun shouldDeserializeDevicesOnly() {
        val json = """
            {
                "timeouts": {},
                "devices": {
                    "whitelist_apps": ["com.example.app"],
                    "uninstall_apps": true,
                    "configured": [{ "udid": "abc123" }]
                }
            }
        """.trimIndent()

        val config = JsonMapper().fromJson<DeviceServerConfig>(json)

        assertNull(config.simulators)
        assertNotNull(config.devices)
        assertEquals(setOf("com.example.app"), config.devices!!.whitelistApps)
        assertEquals(true, config.devices.uninstallApps)
        assertEquals(setOf(ConfiguredDevice("abc123")), config.devices.configuredDevices)
    }

    @Test
    fun shouldDeserializeSimulatorsAndDevices() {
        val json = """
            {
                "timeouts": { "device": 300 },
                "public_host": "myhost.local",
                "simulators": { "limit": 5 },
                "devices": { "configured": [] }
            }
        """.trimIndent()

        val config = JsonMapper().fromJson<DeviceServerConfig>(json)

        assertEquals("myhost.local", config.publicHostName)
        assertNotNull(config.simulators)
        assertNotNull(config.devices)
        assertEquals(5, config.simulators!!.simulatorLimit)
    }

    @Test
    fun shouldDeserializeDefaults() {
        val config = JsonMapper().fromJson<DeviceServerConfig>("{}")

        assertEquals(emptyMap<String, String>(), config.timeouts)
        assertNull(config.publicHostName)
        assertNull(config.simulators)
        assertNull(config.devices)
    }

    @Test
    fun simulatorsConfigShouldHaveDefaults() {
        val config = JsonMapper().fromJson<SimulatorsConfig>("{}")

        assertEquals(5, config.simulatorLimit)
        assertEquals(1, config.concurrentBoots)
        assertEquals(emptyList<String>(), config.disabledSimulatorServices)
        assertEquals(false, config.shutdownSimulators)
    }

    @Test
    fun devicesConfigShouldHaveDefaults() {
        val config = JsonMapper().fromJson<DevicesConfig>("{}")

        assertEquals(emptySet<String>(), config.whitelistApps)
        assertEquals(false, config.uninstallApps)
        assertEquals(emptySet<ConfiguredDevice>(), config.configuredDevices)
    }
}
