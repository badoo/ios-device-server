package com.badoo.automation.deviceserver

import org.junit.Assert
import org.junit.Test

class DeviceServerConfigTest {

    private val localhostConfig = """
{
    "timeouts": {
        "device": 600
    },
    "nodes": [{}]
}
""".trimMargin()

    @Test
    fun shouldDeserialize() {
        val config = JsonMapper().fromJson<DeviceServerConfig>(localhostConfig)
        Assert.assertEquals(600, config.timeouts["device"]?.toInt())
        Assert.assertEquals(NodeConfig(), config.nodes.first())
    }

    @Test
    fun shouldIgnoreDuplicatingNodes() {
        val configWithDuplicatingNodes = """
        {
            "timeouts": {
                "device": 600
            },
            "nodes": [
                {"user":"zz","host":"node1.co.uk","simulator_limit":1},
                {"user":"zz","host":"node1.co.uk","simulator_limit":1}
            ]
        }
        """.trimMargin()
        val config = JsonMapper().fromJson<DeviceServerConfig>(configWithDuplicatingNodes)
        Assert.assertEquals(1, config.nodes.size)
    }
}