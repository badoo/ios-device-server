package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.DeviceManager
import io.ktor.server.routing.Route
import java.util.concurrent.TimeUnit

class StatusController(private val deviceManager: DeviceManager) {
    fun welcomeMessage(route: Route?): String {
        return "<html><body>Welcome to the device server.<pre>\n" +
                (if (route == null) "No routes set yet!?" else childHierarchy(route)) + "\n" +
                "</pre>Minimal /status, but /quitquitquit works</body></html>\n"
    }

    fun getServerStatus(serverStartTime: Long): Map<String, Any> {
        val requestStartTime = System.nanoTime()
        val status = deviceManager.getStatus()
        val uptime = TimeUnit.MINUTES.convert(System.nanoTime() - serverStartTime, TimeUnit.NANOSECONDS)

        return mapOf(
            "status" to "ok",
            "uptime" to "$uptime minutes",
            "deviceManager" to status,
            "elapsedTimeSeconds" to TimeUnit.SECONDS.convert(System.nanoTime() - requestStartTime, TimeUnit.NANOSECONDS)
        )
    }

    private fun childHierarchy(route: Route?, path: String = "", margin: String = "\n. "): String {
        // TODO: Implement route hierarchy display for welcome page
        return "FIXME: Here should be routes hierarchy"
    }
}
