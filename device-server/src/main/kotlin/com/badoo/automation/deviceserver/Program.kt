package com.badoo.automation.deviceserver

import io.ktor.server.netty.*

//fun main(args: Array<String>) {
//    embeddedServer(Netty, commandLineEnvironment(args)) {
//        connectionGroupSize = Integer.getInteger("embedded.netty.connectionGroupSize", connectionGroupSize)
//        workerGroupSize = Integer.getInteger("embedded.netty.workerGroupSize", workerGroupSize)
//        callGroupSize = Integer.getInteger("embedded.netty.callGroupSize", callGroupSize)
//    }.start(wait = true)
//}

fun main(args: Array<String>) {
    System.setProperty("io.netty.eventLoopThreads", "200")
    val server = EngineMain.createServer(args)

    with(server.engine.configuration) {
        this.tcpKeepAlive = true
        this.runningLimit = 300
        this.connectionGroupSize = 200
        this.workerGroupSize = 200
        this.callGroupSize = 200
    }

    server.start(wait = true)
}
