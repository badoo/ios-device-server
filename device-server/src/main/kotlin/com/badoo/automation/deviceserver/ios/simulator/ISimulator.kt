package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import java.net.URI
import java.net.URL

interface ISimulator {
    // FIXME: cleanup unnecessary properties from interface (copied attr_reader from ruby as is)
    val ref: DeviceRef
    val udid: UDID
    val state: DeviceState
    val fbsimctlEndpoint: URI
    val wdaEndpoint: URI
    val userPorts: DeviceAllocatedPorts
    val info: DeviceInfo
    val lastError: Exception?
    val calabashPort: Int
    val videoRecorder: SimulatorVideoRecorder
    val fbsimctlSubject: String

    fun prepareAsync()
    fun resetAsync()
    fun status(): SimulatorStatusDTO
    fun endpointFor(port: Int): URL
    fun approveAccess(bundleId: String)
    fun release(reason: String)
    fun clearSafariCookies(): Map<String, String>
    fun shake(): Boolean
    fun lastCrashLog(): CrashLog
    fun dataContainer(bundleId: String): DataContainer
}
