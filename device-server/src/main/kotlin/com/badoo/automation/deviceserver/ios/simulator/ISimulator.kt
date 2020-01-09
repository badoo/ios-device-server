package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.CrashLog
import com.badoo.automation.deviceserver.data.PermissionSet
import com.badoo.automation.deviceserver.ios.IDevice
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import java.util.concurrent.locks.ReentrantLock

interface ISimulator: IDevice {
    val systemLog: SystemLog
    val osLog: OsLog
    val media: Media

    fun resetAsync(): Runnable
    fun approveAccess(bundleId: String, locationPermissionsLock: ReentrantLock)
    fun setPermissions(bundleId: String, permissions: PermissionSet, locationPermissionsLock: ReentrantLock)
    fun clearSafariCookies(): Map<String, String>
    fun shake(): Boolean
    fun openUrl(url: String): Boolean
    fun crashLogs(pastMinutes: Long?): List<CrashLog>
    fun dataContainer(bundleId: String): DataContainer
    fun deleteCrashLogs(): Boolean
    fun setEnvironmentVariables(envs: Map<String, String>)
    fun applicationContainer(bundleId: String): DataContainer
}
