package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.host.management.IDeviceManager
import io.ktor.routing.Route

class StatusController(private val deviceManager: IDeviceManager) {
    fun welcomeMessage(route: Route?): String {
        return "<html><body>Welcome to the device server.<pre>\n" +
                (if (route == null) "No routes set yet!?" else childHierarchy(route)) + "\n" +
                "</pre>Minimal /status, but /quitquitquit works</body></html>\n"
    }

    fun getServerStatus(): Map<String, Any> {
        val status = deviceManager.getStatus()

        return mapOf(
                "status" to "ok",
                "deviceManager" to status
        )
    }

    private fun childHierarchy(route: Route, path: String = "", margin: String = "\n. "): String {
        val selector = route.selector.toString()
        val maybe_real_endpoint =
                if (!selector.startsWith("(method:"))
                    ""
                else
                {
                    val path_or_link =
                            if (selector == "(method:GET)" && !path.contains('{'))
                                "<a href='${path}'>$path</a>" // Safe to provide as a link: idempotent.
                            else
                                path
                    "$margin $path_or_link ${route.selector}"
                }
        return maybe_real_endpoint +
               route.children.sortedBy { it.toString() }
                       .joinToString("" ) { childHierarchy(it, route.toString(), "$margin. ") }
    }
}
