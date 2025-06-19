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
    val workers = 300

    with(server.engine.configuration) {
        this.connectionGroupSize = workers
        this.workerGroupSize = workers
        this.callGroupSize = workers
    }

    server.start(wait = true)
}
