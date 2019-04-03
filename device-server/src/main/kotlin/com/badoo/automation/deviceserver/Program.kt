package com.badoo.automation.deviceserver

import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    embeddedServer(Netty, commandLineEnvironment(args)) {
        workerGroupSize = Integer.getInteger("embedded.netty.workerGroupSize", workerGroupSize)
        callGroupSize = Integer.getInteger("embedded.netty.callGroupSize", callGroupSize)
    }.start(wait = true)
}