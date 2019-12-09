package com.badoo.automation.deviceserver

import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    embeddedServer(Netty, commandLineEnvironment(args)) {
//        connectionGroupSize = Integer.getInteger("embedded.netty.connectionGroupSize", connectionGroupSize)
//        workerGroupSize = Integer.getInteger("embedded.netty.workerGroupSize", workerGroupSize)
//        callGroupSize = Integer.getInteger("embedded.netty.callGroupSize", callGroupSize)
        connectionGroupSize = 200
        workerGroupSize = 200
        callGroupSize = 200
    }.start(wait = true)
}