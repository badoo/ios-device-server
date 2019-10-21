package com.badoo.automation.deviceserver.ios.proc

interface IWebDriverAgent {
    fun isHealthy(): Boolean
    fun kill()
    fun start()
    fun installHostApp()
}