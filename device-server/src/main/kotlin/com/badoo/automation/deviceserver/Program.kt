package com.badoo.automation.deviceserver

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.*

fun main(args: Array<String>) {
    embeddedServer(CIO, port = 4567, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}
