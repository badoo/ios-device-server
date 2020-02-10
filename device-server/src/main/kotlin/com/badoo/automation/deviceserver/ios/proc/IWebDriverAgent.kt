package com.badoo.automation.deviceserver.ios.proc

interface IWebDriverAgent {
    fun start()
    fun stop()
    fun isHealthy(): Boolean
    fun installHostApp()
}
