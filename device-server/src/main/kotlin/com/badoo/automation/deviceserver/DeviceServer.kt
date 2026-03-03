package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.controllers.DevicesController
import com.badoo.automation.deviceserver.controllers.StatusController
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.HostFactory
import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.host.management.errors.DeviceCreationException
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.NoAliveNodesException
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import com.badoo.automation.deviceserver.util.NetworkUtils
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import kotlin.system.exitProcess

private fun jsonContent(call: ApplicationCall): JsonNode {
    val json = call.request.receiveChannel().toInputStream()
    return JsonMapper().readTree(json)
}

private inline fun <reified T> jsonContent(call: ApplicationCall): T {
    return JsonMapper().fromJson(call.request.receiveChannel().toInputStream())
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

private val appConfiguration = ApplicationConfiguration()

private fun serverConfig(): DeviceServerConfig {
    if (appConfiguration.deviceServerConfigPath.isEmpty()) {
        val defaultConfig = DeviceServerConfig(simulators = SimulatorsConfig())
        logger.info("Using default config: $defaultConfig")
        return defaultConfig
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

private val logger = LoggerFactory.getLogger(DevicesController::class.java.simpleName)

val defaultUser = UserIdPrincipal("DefaultUser")

@Suppress("unused")
fun Application.module() {
    val config = serverConfig()
    val startTime = System.nanoTime()

    val hostFactory = HostFactory(appConfiguration = ApplicationConfiguration())
    val deviceManager = DeviceManager(config, hostFactory)
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

//    authentication {
//        bearer("auth-bearer") {
//            realm = "Ktor Server"
//            authenticate { bearerTokenCredential: BearerTokenCredential ->
//                if (bearerTokenCredential.token == null || bearerTokenCredential.token.isBlank()) {
//                    null
//                } else {
//                    val userName: String = Base64.getDecoder().decode(bearerTokenCredential.token).toString(Charsets.ISO_8859_1)
//                    UserIdPrincipal(userName)
//                }
//            }
//        }
//        // FIXME: See anonymousAuthentication
//    }

    install(IgnoreTrailingSlash)

    logger.info("Server: Installing routing...")
    install(RoutingRoot) {
        get("quitquitquit") {
            call.respond(HttpStatusCode.OK, "Shutting down server...")
            Thread.sleep(2000) // Give time to respond
            exitProcess(0)
        }
        get {
            val toDoRoutes: Route? = null
            call.respondText(statusController.welcomeMessage(toDoRoutes), ContentType.Text.Html)
        }
        route("status") {
            get {
                val code = if (deviceManager.isReady()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respond(code, statusController.getServerStatus(startTime))
            }
            get("config") {
                call.respond(config)
            }
        }

        route("devices") {
            get {
                call.respond(devicesController.getDeviceRefs())
            }
            post {
//                val user = call.principal<UserIdPrincipal>()
                val user = defaultUser
                val deviceDto: DeviceDTO = devicesController.createDevice(jsonContent<DesiredCapabilities>(call), user)
                call.respond(deviceDto)
            }
            post("preboot_simulator") {
                // This route is used for prebooting simulator for tests
                // It is not used in production, but can be used in tests to speed up simulator boot time
//                val user = call.principal<UserIdPrincipal>()
                val user = defaultUser
                val deviceDto: DeviceDTO = devicesController.prebootSimulatorForTests(jsonContent<DesiredCapabilities>(call), user)
                call.respond(deviceDto)
            }
            delete {
//                val user = call.principal<UserIdPrincipal>()
                val user = defaultUser
                if (user == null) {
                    call.respond(devicesController.releaseAllDevices())
                } else {
                    call.respond(devicesController.releaseDevices(user))
                }
            }
            post("deploy_app") {
                val appBundle = jsonContent<AppBundleDeployDto>(call)
                call.respond(devicesController.deployApplication(appBundle))
            }
            post("reset_app_bundle_cache") {
                call.respond(devicesController.resetAppBundleCache())
            }
            post("-/capacity") {
                call.respond(devicesController.getTotalCapacity(jsonContent<DesiredCapabilities>(call)))
            }
            route("{ref}") {
                get {
                    val device: DeviceDTO = devicesController.getDeviceContactDetails(param(call, "ref"))
                    call.respond(device)
                }
                post {
                    call.respond(devicesController.controlDevice(param(call, "ref"), jsonContent(call)))
                }
                delete {
                    call.respond(devicesController.deleteReleaseDevice(param(call, "ref")))
                }
                delete("force") {
                    call.respond(devicesController.deleteReleaseDeviceWitForce(param(call, "ref")))
                }
                post("push_notification") {
                    val notification = jsonContent<PushNotificationDto>(call)
                    call.respond(devicesController.sendPushNotification(param(call, "ref"), notification.bundleId, notification.notificationContent))
                }
                post("pasteboard") {
                    val pasteboard = jsonContent<PasteboardDto>(call)
                    call.respond(devicesController.sendPasteboard(param(call, "ref"), pasteboard.pasteboardContent))
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
                    get("app") {
                        val appName: String? = call.request.queryParameters["appName"]
                        call.respond(devicesController.crashLogs(param(call, "ref"), appName))
                    }
                    delete {
                        val rv = devicesController.deleteCrashLogs(param(call, "ref"))
                        call.respond(mapOf("result" to rv))
                    }
                    get("last") {// this route is going to be deprecated
                        call.respond(devicesController.getLastCrashLog(param(call, "ref")))
                    }
                }
                route("shared_resources") {
                    delete {
                        val deviceRef = param(call, "ref")
                        val path = param(call, "path")
                        call.respond(devicesController.deleteFile(deviceRef, File(path).toPath()))
                    }
                    post {
                        val deviceRef = param(call, "ref")
                        val sharedResource = jsonContent<SharedResourceDto>(call)
                        call.respond(devicesController.pushFile(deviceRef, sharedResource.data, File(sharedResource.path).toPath()))
                    }
                    get {
                        // API(/devices/{ref}/shared_resources?path={file_path}") to get the file from shared resource directory.
                        val deviceRef = param(call, "ref")
                        val path = param(call, "path")
                        val file: File = devicesController.pullFile(deviceRef, File(path).toPath())
                        call.response.header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
                        call.respondFile(file)
                    }
                }
                route("data") {
                    post("pull_file") {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<DataPath>(call)
                        val file: File = devicesController.pullFile(ref, dataPath)
                        call.response.header(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.name}\"")
                        call.respondFile(file)
                    }
                    post("push_file") {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<FileDto>(call)

                        if (dataPath.bundleId == null) {
                            throw IllegalArgumentException("Bundle id is not set. Have to set 'bundle_id' to aprropriate value.")
                        }

                        call.respond(devicesController.pushFile(ref, dataPath.fileName, dataPath.data, dataPath.bundleId))
                    }
                    post("list_files") {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<DataPath>(call)
                        call.respond(devicesController.listFiles(ref, dataPath))
                    }

                    delete("{bundleId}") {
                        val ref = param(call, "ref")
                        val bundleId = param(call, "bundleId")
                        call.respond(devicesController.deleteAppData(ref, bundleId))
                    }
                }
                route("app") {
                    delete("{bundleId}") {
                        val ref = param(call, "ref")
                        val bundleId = param(call, "bundleId")
                        call.respond(devicesController.uninstallApplication(ref, bundleId))
                    }
                    post("install") {
                        val ref = param(call, "ref")
                        val appBundle = jsonContent<AppBundleDto>(call)
                        call.respond(devicesController.installApplication(ref, appBundle))
                    }
                    get("install_status") {
                        val ref = param(call, "ref")
                        call.respond(devicesController.appInstallationStatus(ref))
                    }

                    post("update_plist") {
                        val ref = param(call, "ref")
                        val plistEntries = jsonContent<PlistEntryDTO>(call)
                        call.respond(devicesController.updateApplicationPlist(ref, plistEntries))
                    }
                    get("list") {
                        call.respond(devicesController.listApps(param(call, "ref")))
                    }
                }
                route("media") {
                    get {
                        val ref = param(call, "ref")
                        call.respond(JsonMapper().toJson(MediaDto(devicesController.listMedia(ref))))
                    }
                    delete {
                        val ref = param(call, "ref")
                        call.respond(devicesController.resetMedia(ref))
                    }
                    post {
                        val ref = param(call, "ref")
                        val dataPath = jsonContent<FileDto>(call)
                        call.respond(devicesController.addMedia(ref, dataPath.fileName, dataPath.data))
                    }
                }

                route("media_data") {
                    get {
                        val ref = param(call, "ref")
                        call.respond(JsonMapper().toJson(MediaDto(devicesController.listPhotoData(ref))))
                    }
                }
                route("syslog") {
                    get {
                        val ref = param(call, "ref")
                        val logFile = devicesController.syslog(ref)
                        call.respondFile(logFile)
                    }
                    delete {
                        val ref = param(call, "ref")
                        call.respond(devicesController.syslogDelete(ref))
                    }
                    post("start") {
                        val ref = param(call, "ref")
                        call.respond(devicesController.syslogStart(ref, jsonContent<SysLogCaptureOptions>(call)))
                    }
                    post("stop") {
                        val ref = param(call, "ref")
                        call.respond(devicesController.syslogStop(ref))
                    }
                }
                route("device_agent_log") {
                    get {
                        val ref = param(call, "ref")
                        val logFile = devicesController.instrumentationAgentLog(ref)
                        if (logFile.exists()) {
                            call.respondFile(logFile)
                        } else {
                            call.respond("No instrumentation agent log found for device $ref")
                        }
                    }
                    delete {
                        val ref = param(call, "ref")
                        call.respond(devicesController.deleteInstrumentationAgentLog(ref))
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
                route("openurl") {
                    post {
                        val ref = param(call, "ref")
                        val url = jsonContent<UrlDto>(call).url
                        call.respond(devicesController.openUrl(ref, url))
                    }
                }
                route("video") {
                    get {
                        val videoFile: File = devicesController.getVideo(param(call, "ref"))
                        call.response.header(HttpHeaders.ContentType, ContentType.Video.MP4.toString())
                        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${videoFile.name}\"")
                        call.respondFile(videoFile)
                    }
                    get("log") {
                        call.respond(devicesController.getVideoLog(param(call, "ref")))
                    }
                    post {
                        call.respond(devicesController.startStopVideo(param(call, "ref"), jsonContent(call)))
                    }
                    delete {
                        call.respond(devicesController.deleteVideo(param(call, "ref")))
                    }
                }
                route("location") {
                    get("scenarios") {
                        call.respond(devicesController.locationListScenarios(param(call, "ref")))
                    }
                    delete {
                        call.respond(devicesController.locationClear(param(call, "ref")))
                    }
                    post("set") {
                        val location = jsonContent<LocationDto>(call)
                        call.respond(devicesController.locationSet(param(call, "ref"), location.latitude, location.longitude))
                    }
                    post("run") {
                        val scenario = jsonContent<LocationScenarioDto>(call)
                        call.respond(devicesController.locationRunScenario(param(call, "ref"), scenario.scenarioName))
                    }
                    post("start") {
                        val waypoints = jsonContent<LocationWaypointsDto>(call)
                        call.respond(
                            devicesController.locationStartLocationSequence(
                                param(call, "ref"), waypoints.speed, waypoints.distance, waypoints.interval, waypoints.waypoints
                            )
                        )
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
                get("environment/{variableName}") {
                    val ref = param(call, "ref")
                    val variableName = param(call, "variableName")
                    if (variableName.isNullOrEmpty()) {
                        throw IllegalArgumentException("Environment variable name shouldn't be empty")
                    } else {
                        call.respond(devicesController.getEnvironmentVariable(ref, variableName))
                    }
                }
            }
        }
    }

    logger.info("Server: Installing status pages...")
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call: ApplicationCall, status: HttpStatusCode ->
            val error = ErrorDto("RouteNotFound", call.request.uri, emptyList())
            call.respond(status, hashMapOf("error" to error))
        }

        exception<Throwable> { call: ApplicationCall, cause: Throwable ->
            val statusCode = when (cause) {
                is IllegalArgumentException -> HttpStatusCode(422, "Unprocessable Entity")
                is IllegalStateException -> HttpStatusCode.Conflict
                is DeviceNotFoundException -> HttpStatusCode.NotFound
                is FileNotFoundException -> HttpStatusCode.NotFound
                is NoAliveNodesException -> HttpStatusCode.TooManyRequests
                is OverCapacityException -> HttpStatusCode.TooManyRequests
                is DeviceCreationException -> HttpStatusCode.ServiceUnavailable
                else -> HttpStatusCode.InternalServerError
            }
            val path = call.request.path()
            val marker = MapEntriesAppendingMarker(
                mapOf(
                    "http_api" to path, "exception_class" to cause.javaClass.canonicalName
                )
            )

            if (cause !is OverCapacityException && cause !is FileNotFoundException) {
                logger.error(marker, "HTTP_API: $path | Error: ${cause.message}", cause)
            }

            call.respond(
                statusCode, hashMapOf(
                    "error" to cause.toDto()
                )
            )
        }
    }

    logger.info("Server: Installation complete. Should be available at ${NetworkUtils.getAddresses()}")
}
