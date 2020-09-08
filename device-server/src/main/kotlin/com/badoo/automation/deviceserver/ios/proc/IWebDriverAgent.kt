package com.badoo.automation.deviceserver.ios.proc

import java.io.File

interface IWebDriverAgent {
    val deviceAgentLog: File
    fun isHealthy(): Boolean
    fun kill()
    fun start()
    fun installHostApp()
}
