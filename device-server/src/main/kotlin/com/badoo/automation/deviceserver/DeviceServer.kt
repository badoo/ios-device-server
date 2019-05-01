package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.controllers.DevicesController
import com.badoo.automation.deviceserver.controllers.StatusController
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.HostFactory
import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.host.management.errors.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.UnauthorizedResponse
import io.ktor.auth.UserIdPrincipal
import io.ktor.auth.authentication
import io.ktor.auth.principal
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.server.engine.ApplicationEngineEnvironmentReloading
import io.ktor.server.engine.ShutDownUrl
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.IllegalStateException
import java.net.NetworkInterface
import java.util.*

typealias EmptyMap = Map<Unit, Unit>

private fun jsonContent(call: ApplicationCall): JsonNode {
    val json = call.request.receiveContent().inputStream()
    return JsonMapper().readTree(json)
}

private inline fun <reified T> jsonContent(call: ApplicationCall): T {
    return JsonMapper().fromJson(call.request.receiveContent().inputStream())
}

private fun param(call: ApplicationCall, s: String): String {
    return call.parameters[s] ?: throw Exception("Parameter $s missing from ${call.request.uri}")
}

private fun paramInt(call: ApplicationCall, s: String): Int {
    try {
        return param(call, s).toInt()
    } catch (e: NumberFormatException) {
        throw Exception("Parameter $s was not an integer in ${call.request.uri}")
    }
}

fun getAddresses(): List<String> {
    return NetworkInterface.getNetworkInterfaces().toList().flatMap {
        it.inetAddresses.toList()
                .filter { it.address.size == 4 }
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress + "/" + it.hostName }
    }
}

private val appConfiguration = ApplicationConfiguration()

private fun serverConfig(): DeviceServerConfig {
    if (appConfiguration.deviceServerConfigPath.isEmpty()) {
        val defaultNodeConfig = NodeConfig()
        logger.info("Using default config: $defaultNodeConfig")
        return DeviceServerConfig(nodes = setOf(defaultNodeConfig), timeouts = emptyMap())
    }

    val configFile = File(appConfiguration.deviceServerConfigPath)

    if (!configFile.exists()) {
        val msg = "Config file ${configFile.path} not found"
        logger.error(msg)
        throw RuntimeException(msg)
    }

    logger.info("Using config file: ${configFile.path}")
    return JsonMapper().fromJson(configFile.readText())
}

var routes: Route? = null

private val logger = LoggerFactory.getLogger(DevicesController::class.java.simpleName)

@Suppress("unused")
fun Application.module() {
    val config = serverConfig()
    val hostFactory = HostFactory(
        wdaSimulatorBundle = File(appConfiguration.wdaSimulatorBundlePath).canonicalFile,
        remoteWdaSimulatorBundleRoot = File(appConfiguration.remoteWdaSimulatorBundleRoot).canonicalFile,
        wdaDeviceBundle = File(appConfiguration.wdaDeviceBundlePath).canonicalFile,
        remoteWdaDeviceBundleRoot = File(appConfiguration.remoteWdaDeviceBundleRoot).canonicalFile,
        fbsimctlVersion = appConfiguration.fbsimctlVersion
    )
    val deviceManager = DeviceManager(config, hostFactory)
    deviceManager.startAutoRegisteringDevices()
    deviceManager.launchAutoReleaseLoop()

    val devicesController = DevicesController(deviceManager)
    val statusController = StatusController(deviceManager)

    install(DefaultHeaders)
    install(CallLogging)
    install(ContentNegotiation) {
        jackson {
            configure(SerializationFeature.INDENT_OUTPUT, true)
            registerModule(JavaTimeModule())
        }
    }

    install(ShutDownUrl.ApplicationCallFeature) {
        shutDownUrl = "/quitquitquit"
        exitCodeSupplier = { 1 }
    }

    authentication {
        bearerAuthentication("default") { token ->
            val name = Base64.getDecoder().decode(token).toString(Charsets.ISO_8859_1)
            when {
                name.isEmpty() -> null
                else -> UserIdPrincipal(name)
            }
        }
        anonymousAuthentication()
    }

    logger.info("Server: Installing routing...")
    routes = install(Routing) {
        get {
            call.respondText(statusController.welcomeMessage(routes), ContentType.Text.Html)
        }
        route("status") {
            get {
              val code = if (deviceManager.isReady()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
              call.respond(code, statusController.getServerStatus())
            }
            get("config") {
                call.respond(config)
            }
        }

        route("nodes") {
            post("restart_gracefully") {
                val params = jsonContent(call)
                val isParallelRestart = params["parallel"]?.asBoolean() ?: false
                val restartScheduled = deviceManager.restartNodesGracefully(isParallelRestart)

                if (restartScheduled) {
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "Scheduled graceful restart of nodes"))
                } else {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("status" to "Nodes restart is already in progress"))
                }
            }
        }

        route("devices") {
            get {
                call.respond(devicesController.getDeviceRefs())
            }
            post {
                val user = call.principal<UserIdPrincipal>()
                call.respond(devicesController.createDevice(jsonContent<DesiredCapabilities>(call), user))
            }
            delete {
                val user = call.principal<UserIdPrincipal>()
                if (user == null) {
                    call.respond(UnauthorizedResponse())
                } else {
                    call.respond(devicesController.releaseDevices(user))
                }
            }
            post("-/capacity") {
                call.respond(devicesController.getTotalCapacity(jsonContent<DesiredCapabilities>(call)))
            }
            route("{ref}") {
                get {
                    call.respond(devicesController.getDeviceContactDetails(param(call, "ref")))
                }
                post {
                    call.respond(devicesController.controlDevice(param(call, "ref"), jsonContent(call)))
                }
                delete {
                    call.respond(devicesController.deleteReleaseDevice(param(call, "ref")))
                }
                post("permissions") {
                    call.respond(devicesController.setPermissions(param(call, "ref"), jsonContent(call)))
                }
                get("endpoint/{port}") {
                    call.respond(devicesController.getEndpointFor(param(call, "ref"), paramInt(call, "port")))
                }
                route("crashes") {
                    get {
                        val pastMinutes = call.request.queryParameters["pastMinutes"]?.toLong()

                        call.respond(devicesController.crashLogs(param(call, "ref"), pastMinutes))
                    }
                    delete {
                        val rv = devicesController.deleteCrashLogs(param(call, "ref"))
                        call.respond(mapOf("result" to rv))
                    }
                    get("last") {// this route is going to be deprecated
                        call.respond(devicesController.getLastCrashLog(param(call, "ref")))
                    }
                }
                route("data") {
                    post("pull_file") {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<DataPath>(call)
                        call.respond(devicesController.pullFile(ref, dataPath))
                    }
                    post("list_files") {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<DataPath>(call)
                        call.respond(devicesController.listFiles(ref, dataPath))
                    }
                }
                route("app") {
                    delete("{bundleId}") {
                        val ref = param(call, "ref")
                        val bundleId = param(call, "bundleId")
                        call.respond(devicesController.uninstallApplication(ref, bundleId))
                    }
                }
                route("media") {
                    delete {
                        val ref = param(call, "ref")
                        call.respond(devicesController.resetMedia(ref))
                    }
                }
                route("diagnose/{type}") {
                    get {
                        val ref = param(call, "ref")
                        val type = param(call, "type")
                        call.respond(devicesController.getDiagnostic(ref, type, DiagnosticQuery()))
                    }
                    post {
                        val ref = param(call, "ref")
                        val type = param(call, "type")
                        val query = jsonContent<DiagnosticQuery>(call)
                        call.respond(devicesController.getDiagnostic(ref, type, query))
                    }
                    delete {
                        val ref = param(call, "ref")
                        val type = param(call, "type")
                        call.respond(devicesController.resetDiagnostic(ref, type))
                    }
                }
                route("video") {
                    get {
                        //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigating ByteReadChannel
                        //FIXME: see [call.respondFile] basically - read from ssh proc listener's ByteBuffer
                        call.respond(devicesController.getVideo(param(call, "ref")))
                    }
                    post {
                        call.respond(devicesController.startStopVideo(param(call, "ref"), jsonContent(call)))
                    }
                    delete {
                        call.respond(devicesController.deleteVideo(param(call, "ref")))
                    }
                }
                get("state") {
                    call.respond(devicesController.getDeviceState(param(call, "ref")))
                }
                post("environment") {
                    val ref = param(call, "ref")
                    val environmentVariables = jsonContent<Map<String, String>>(call)
                    call.respond(devicesController.setEnvironmentVariables(ref, environmentVariables))
                }
            }
        }
    }

    logger.info("Server: Installing status pages...")
    install(StatusPages) {
        status(HttpStatusCode.NotFound) {
            val msg =  "${it.value} ${it.description} : ${call.request.uri}"
            val error = ErrorDto("RouteNotFound",msg, emptyList())
            call.respond(HttpStatusCode.NotFound,
                    hashMapOf("error" to error)
            )
        }
        exception { exception: Throwable ->
            val statusCode = when (exception) {
                is IllegalArgumentException -> HttpStatusCode(422, "Unprocessable Entity")
                is IllegalStateException -> HttpStatusCode.Conflict
                is DeviceNotFoundException -> HttpStatusCode.NotFound
                is NoAliveNodesException -> HttpStatusCode.TooManyRequests
                is OverCapacityException -> HttpStatusCode.TooManyRequests
                is DeviceCreationException -> HttpStatusCode.ServiceUnavailable
                else -> HttpStatusCode.InternalServerError
            }

            logger.error(call.request.toString(), exception)
            call.respond(statusCode,
                    hashMapOf(
                            "error" to exception.toDto()
                    )
            )
        }
    }

    logger.info("Server: Installation complete. Should be available at ${connectors()} ${getAddresses()}")
}

private fun Application.connectors(): String {
    return (this.environment as ApplicationEngineEnvironmentReloading).connectors.toString()
}
