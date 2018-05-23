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
}