package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.deviceDTOStub
import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.json
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.doNothing
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.junit.Assert.assertThat
import org.junit.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.File
import java.net.URL

private val happyEmpty: Map<Unit, Unit> = mapOf()

class DevicesControllerTest {
    private var deviceManager: DeviceManager = mockThis()
    private var expectedArray = ByteArray(3)

    private var deviceServer = DevicesController(deviceManager)
    private val deviceRef: DeviceRef = "foobar"
    private val model = "MODEL"
    private val udid = "UDID-1"
    private val os = "OS"
    private val headless = true
    private val desiredCaps = DesiredCapabilities(udid, model, os, headless)
    private val desiredCapsNoUdid = DesiredCapabilities(null, model, os)
    private val expectedDeviceRef: DeviceRef = "hello"
    private val expectedDeviceDTO = deviceDTOStub(expectedDeviceRef)

    @Test
    fun getStatus() {
        val expectedDevices = listOf(deviceDTOStub("one"), deviceDTOStub("two"))
        whenever(deviceManager.getDeviceRefs()).thenReturn(expectedDevices)

        val actualDevices = deviceServer.getDeviceRefs()

        verify(deviceManager).getDeviceRefs()
        assertThat(actualDevices, equalTo(expectedDevices))
    }

    @Test
    fun getTotalCapacity() {
        val expectedCapacity = mapOf("total" to 42)
        whenever(deviceManager.getTotalCapacity(desiredCaps)).thenReturn(expectedCapacity)

        val actualCapacity = deviceServer.getTotalCapacity(desiredCaps)

        verify(deviceManager).getTotalCapacity(desiredCaps)
        assertThat(actualCapacity, equalTo(expectedCapacity))
    }

    @Test
    fun createDevice() {
        whenever(deviceManager.createDeviceAsync(desiredCaps, null)).thenReturn(expectedDeviceDTO)

        val actualDeviceRef = deviceServer.createDevice(desiredCaps, null)

        verify(deviceManager, times(1)).createDeviceAsync(desiredCaps, null)
        assertThat(actualDeviceRef, equalTo(expectedDeviceDTO))
    }

    @Test
    fun createDeviceNoUdid() {
        val desiredCapsWithEmptyUdid = DesiredCapabilities(null, model, os, headless)
        whenever(deviceManager.createDeviceAsync(desiredCapsWithEmptyUdid, null)).thenReturn(expectedDeviceDTO)

        val actualDeviceRef = deviceServer.createDevice(desiredCapsNoUdid, null)

        verify(deviceManager, times(1)).createDeviceAsync(desiredCapsWithEmptyUdid, null)
        assertThat(actualDeviceRef, equalTo(expectedDeviceDTO))
    }

    @Test
    fun getDeviceContactDetails() {
        whenever(deviceManager.getGetDeviceDTO(deviceRef)).thenReturn(expectedDeviceDTO)

        val actualDto = deviceServer.getDeviceContactDetails(deviceRef)

        verify(deviceManager, times(1)).getGetDeviceDTO(deviceRef)
        assertThat(actualDto, equalTo(expectedDeviceDTO))
    }

    @Test
    fun controlDeviceReset() {
        val reset = json("""{"action": "reset"}""")

        val actualResult = deviceServer.controlDevice(deviceRef, reset)

        verify(deviceManager, times(1)).resetAsyncDevice(deviceRef)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun controlDeviceClearCookies() {
        val clearSafariCookies = json("""{"action":"clear_safari_cookies"}""")

        val actualResult = deviceServer.controlDevice(deviceRef, clearSafariCookies)

        verify(deviceManager, times(1)).clearSafariCookies(deviceRef)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun getEndpointFor() {
        val port = 1234
        val expectedResult = URL("http://foo:$port")
        whenever(deviceManager.getEndpointFor(deviceRef, port)).thenReturn(expectedResult)
        val actualResult = deviceServer.getEndpointFor(deviceRef, port)
        verify(deviceManager).getEndpointFor(deviceRef, port)
        assertThat(actualResult, equalTo(mapOf("endpoint" to expectedResult.toString())))
    }

    @Test
    fun getLastCrashLog() {
        val expectedCrashLog = mapOf("filename" to "some_path_name", "content" to "cat of pathname")
        whenever(deviceManager.getLastCrashLog(deviceRef)).thenReturn(CrashLog(
                expectedCrashLog["filename"]!!,
                expectedCrashLog["content"]!!))

        val actualCrashLog = deviceServer.getLastCrashLog(deviceRef)

        verify(deviceManager).getLastCrashLog(deviceRef)
        assertThat(actualCrashLog, equalTo(expectedCrashLog))
    }

    @Test
    fun startStopVideoStarts() {
        val startVideo = json(
                "{\"start\": true}"
        )

        val actualResult = deviceServer.startStopVideo(deviceRef, startVideo)

        verify(deviceManager, times(1)).startVideo(deviceRef)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun startStopVideoStops() {
        val startVideo = json(
                "{\"start\": false}"
        )

        val actualResult = deviceServer.startStopVideo(deviceRef, startVideo)

        verify(deviceManager, times(1)).stopVideo(deviceRef)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test(expected = IllegalArgumentException::class)
    fun startStopVideoFails() {
        val startVideo = json(
                """{"start": "start"}"""
        )

        deviceServer.startStopVideo(deviceRef, startVideo)
        verify(deviceManager, times(1)).stopVideo(deviceRef)
    }

    @Test
    fun getVideo() {
        whenever(deviceManager.getVideo(deviceRef)).thenReturn(expectedArray)

        val actualResult = deviceServer.getVideo(deviceRef)

        assertThat(actualResult, sameInstance(expectedArray))
    }

    @Test
    fun deleteVideo() {
        val actualResult = deviceServer.deleteVideo(deviceRef)

        verify(deviceManager, times(1)).deleteVideo(deviceRef)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun getDeviceState() {
        val expectedState = SimulatorStatusDTO(
                true, true, true, true, DeviceState.NONE.value, null)
        whenever(deviceManager.getDeviceState(deviceRef)).thenReturn(expectedState)
        val actualResult = deviceServer.getDeviceState(deviceRef)
        assertThat(actualResult, equalTo(expectedState))
    }

    @Test
    fun setEnvironmentVariables() {
        val environmentVariables = mapOf(
                "ENV_NAME1" to "ENV_VALUE1",
                "ENV_NAME2" to "ENV_VALUE2"
        )
        val actualResult = deviceServer.setEnvironmentVariables(deviceRef, environmentVariables)

        verify(deviceManager).setEnvironmentVariables(deviceRef, environmentVariables)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun getEnvironmentVariable() {
        val environmentVariable = "ENV_NAME1"
        val expectedValue = "ENV_VAL1"
        whenever(deviceManager.getEnvironmentVariable(deviceRef, environmentVariable)).thenReturn(expectedValue)
        val actualResult = deviceServer.getEnvironmentVariable(deviceRef, environmentVariable)

        assertThat(actualResult, equalTo(expectedValue))
    }

    @Test
    fun pushFile() {
        val fakeData = ByteArray(3)
        val fakeFile = File("fakeFile").toPath()
        doNothing().`when`(deviceManager).pushFile(deviceRef, fakeData, fakeFile)
        val actualResult = deviceServer.pushFile(deviceRef, fakeData, fakeFile)

        verify(deviceManager, times(1)).pushFile(deviceRef, fakeData, fakeFile)
        assertThat(actualResult, equalTo(happyEmpty))
    }

    @Test
    fun deleteFile() {
        val fakeFilePath = File("fakeFilePath").toPath()
        doNothing().`when`(deviceManager).deleteFile(deviceRef, fakeFilePath)
        deviceServer.deleteFile(deviceRef, fakeFilePath)

        verify(deviceManager, times(1)).deleteFile(deviceRef, fakeFilePath)
    }
}
