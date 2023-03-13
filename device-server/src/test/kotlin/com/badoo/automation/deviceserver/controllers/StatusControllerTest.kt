package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.mockThis
import org.hamcrest.CoreMatchers
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test

class StatusControllerTest {
    private var deviceManager: DeviceManager = mockThis()
    private var statusController = StatusController(deviceManager)
    @Test
    @Ignore
    fun getServerStatus() {
        val uptime = System.nanoTime()

        Assert.assertThat(
                statusController.getServerStatus(uptime),
                CoreMatchers.equalTo(mapOf("status" to "ok", "deviceManager" to emptyMap<String, Any>()
                )))
    }

}