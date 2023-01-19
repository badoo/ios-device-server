package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.CrashLog
import com.badoo.automation.deviceserver.data.PermissionSet
import com.badoo.automation.deviceserver.ios.IDevice
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.data.SharedContainer
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import java.util.concurrent.locks.ReentrantLock

interface ISimulator: IDevice {
    val systemLog: SystemLog
    val media: Media
    val locationManager: LocationManager

    fun resetAsync(): Runnable
    fun sendPushNotification(bundleId: String, notificationContent: ByteArray)
    fun sendPasteboard(payload: ByteArray)
    fun setPermissions(bundleId: String, permissions: PermissionSet)
    fun clearSafariCookies(): Map<String, String>
    fun shake(): Boolean
    fun openUrl(url: String): Boolean
    fun crashLogs(pastMinutes: Long?): List<CrashLog>
    fun dataContainer(bundleId: String): DataContainer
    fun sharedContainer(): SharedContainer
    fun deleteCrashLogs(): Boolean
    fun setEnvironmentVariables(envs: Map<String, String>)
    fun getEnvironmentVariable(variableName: String): String
    fun applicationContainer(bundleId: String): DataContainer
}
