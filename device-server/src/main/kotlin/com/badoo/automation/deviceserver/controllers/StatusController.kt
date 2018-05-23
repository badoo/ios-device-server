package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.IDeviceManager
import io.ktor.routing.Route

class StatusController(private val deviceManager: IDeviceManager) {
    fun welcomeMessage(routes: Route?): String {
        return "Welcome to the device server.\n" +
                childHierarchy(routes?.children ?: emptyList()) + "\n" +
                "Minimal /status, but /quitquitquit works\n"
    }

    fun getServerStatus(): Map<String, Any> {
        val status = deviceManager.getStatus()

        return mapOf(
                "status" to "ok",
                "deviceManager" to status
        )
    }

    private fun childHierarchy(children: List<Route>, margin: String = "\n. "): Map<String, String> {
        return children.sortedBy { it.toString() }
                .map { margin + it to childHierarchy(it.children, "$margin. ").toString() }
                .toMap()
    }
}
