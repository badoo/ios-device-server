package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.locks.ReentrantLock

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
    val mjpegServerPort: Int
    val videoRecorder: VideoRecorder
    val fbsimctlSubject: String
    val systemLog: SystemLog
    val osLog: OsLog
    val media: Media

    fun prepareAsync()
    fun resetAsync()
    fun status(): SimulatorStatusDTO
    fun endpointFor(port: Int): URL
    fun approveAccess(bundleId: String, locationPermissionsLock: ReentrantLock)
    fun setPermissions(bundleId: String, permissions: PermissionSet, locationPermissionsLock: ReentrantLock)
    fun release(reason: String)
    fun clearSafariCookies(): Map<String, String>
    fun shake(): Boolean
    fun openUrl(url: String): Boolean
    fun lastCrashLog(): CrashLog
    fun crashLogs(pastMinutes: Long?): List<CrashLog>
    fun dataContainer(bundleId: String): DataContainer
    fun uninstallApplication(bundleId: String)
    fun deleteCrashLogs(): Boolean
    fun setEnvironmentVariables(envs: Map<String, String>)
}
