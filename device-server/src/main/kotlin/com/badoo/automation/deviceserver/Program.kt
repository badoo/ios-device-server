package com.badoo.automation.deviceserver

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

//fun main(args: Array<String>) {
//    embeddedServer(Netty, commandLineEnvironment(args)) {
//        connectionGroupSize = Integer.getInteger("embedded.netty.connectionGroupSize", connectionGroupSize)
//        workerGroupSize = Integer.getInteger("embedded.netty.workerGroupSize", workerGroupSize)
//        callGroupSize = Integer.getInteger("embedded.netty.callGroupSize", callGroupSize)
//    }.start(wait = true)
//}

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}



fun Application.module() {
    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

//    configureHTTP()
//    configureRouting()
//    configureSerialization()
//    configureSecurity()
}
