package com.badoo.automation.deviceserver.ios.proc

/**
 * Long running process. E.g. {@see FbsimctlProc}
 */
interface ILongRunningProc {
    val isProcessAlive: Boolean
    fun start()
    fun isHealthy(): Boolean
    fun kill()
}