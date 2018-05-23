package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.IDeviceManager
import com.badoo.automation.deviceserver.mockThis
import org.hamcrest.CoreMatchers
import org.junit.Assert
import org.junit.Test

class StatusControllerTest {
    private var deviceManager: IDeviceManager = mockThis()
    private var statusController = StatusController(deviceManager)
    @Test
    fun getServerStatus() {
        Assert.assertThat(
                statusController.getServerStatus(),
                CoreMatchers.equalTo(mapOf("status" to "ok", "deviceManager" to emptyMap<String, Any>()
                )))
    }

}