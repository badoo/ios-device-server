package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.mockThis
import org.hamcrest.CoreMatchers
import org.junit.Assert
import org.junit.Test

class StatusControllerTest {
    private var deviceManager: DeviceManager = mockThis()
    private var statusController = StatusController(deviceManager)
    @Test
    fun getServerStatus() {
        Assert.assertThat(
                statusController.getServerStatus(),
                CoreMatchers.equalTo(mapOf("status" to "ok", "deviceManager" to emptyMap<String, Any>()
                )))
    }

}