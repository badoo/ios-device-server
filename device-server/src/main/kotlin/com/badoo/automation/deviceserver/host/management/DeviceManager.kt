package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.HostFactory
import com.badoo.automation.deviceserver.host.IDeviceNode
import com.badoo.automation.deviceserver.host.NodeInfo
import com.badoo.automation.deviceserver.host.management.errors.NoAliveNodesException
import com.badoo.automation.deviceserver.ios.ActiveDevices
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.util.deleteRecursivelyIfExist
import com.badoo.automation.deviceserver.util.ensureDirectoryExists
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import kotlin.system.measureNanoTime

private val INFINITE_DEVICE_TIMEOUT: Duration = Duration.ofSeconds(Integer.MAX_VALUE.toLong())

class DeviceManager(
    config: DeviceServerConfig,
    private val appConfig: ApplicationConfiguration = ApplicationConfiguration(),
    hostFactory: HostFactory = HostFactory(appConfiguration = appConfig),
    private val activeDevices: ActiveDevices = ActiveDevices()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val deviceTimeoutInSecs: Duration
    private val nodes: List<IDeviceNode>
    @Volatile private var ready = false

    init {
        val timeoutFromConfig: Long? = config.timeouts["device"]?.toLong()
        deviceTimeoutInSecs = if (timeoutFromConfig != null && timeoutFromConfig > 0) {
            Duration.ofSeconds(timeoutFromConfig)
        } else {
            INFINITE_DEVICE_TIMEOUT
        }

        nodes = hostFactory.createNodes(config)

        if (nodes.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(nodes.size)
            nodes.map { node -> executor.submit { node.prepareNode() } }.forEach { it.get() }
            executor.shutdown()
        }

        ready = true
    }

    fun isReady(): Boolean = ready

    fun getStatus(): Map<String, Any> {
        val aliveNodesInfo: List<Pair<String, NodeInfo>> = if (nodes.isEmpty()) {
            listOf()
        } else {
            val executor = Executors.newFixedThreadPool(nodes.size)
            val tasks = nodes.map { node ->
                executor.submit(Callable { Pair(node.publicHostName, node.getNodeInfo()) })
            }
            executor.shutdown()
            val result = tasks.map { it.get() }
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                logger.error("Failed to awaitTermination while retrieving NodeInfo: ${e.message}", e)
            }
            result
        }

        return mapOf(
            "initialized" to ready,
            "alive_nodes" to aliveNodesInfo,
            "all_nodes" to nodes.map { it.publicHostName }.sorted(),
            "sessions" to listOf(activeDevices.getStatus()).toString()
        )
    }

    fun getTotalCapacity(desiredCaps: DesiredCapabilities): Map<String, Int> {
        val total = nodes.sumOf { it.totalCapacity(desiredCaps) }
        return mapOf("total" to total)
    }

    fun getGetDeviceDTO(ref: DeviceRef): DeviceDTO {
        return activeDevices.getNodeFor(ref).getDeviceDTO(ref)
    }

    fun clearSafariCookies(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).clearSafariCookies(ref)
    }

    fun sendPushNotification(ref: DeviceRef, bundleId: String, notificationContent: ByteArray) {
        activeDevices.getNodeFor(ref).sendPushNotification(ref, bundleId, notificationContent)
    }

    fun sendPasteboard(ref: DeviceRef, payload: ByteArray) {
        activeDevices.getNodeFor(ref).sendPasteboard(ref, payload)
    }

    fun setPermissions(ref: DeviceRef, permissions: AppPermissionsDto) {
        activeDevices.getNodeFor(ref).setPermissions(ref, permissions)
    }

    fun getEndpointFor(ref: DeviceRef, port: Int): URL {
        return activeDevices.getNodeFor(ref).endpointFor(ref, port)
    }

    fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        return activeDevices.getNodeFor(ref).crashLogs(ref, pastMinutes)
    }

    fun crashLogs(ref: DeviceRef, appName: String?): List<CrashLog> {
        return activeDevices.getNodeFor(ref).crashLogs(ref, appName)
    }

    fun deleteCrashLogs(ref: DeviceRef): Boolean {
        return activeDevices.getNodeFor(ref).deleteCrashLogs(ref)
    }

    fun getLastCrashLog(ref: DeviceRef): CrashLog {
        return activeDevices.getNodeFor(ref).lastCrashLog(ref)
    }

    fun listApps(ref: DeviceRef): List<FBSimctlAppInfo> = activeDevices.getNodeFor(ref).listApps(ref)

    fun shake(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).shake(ref)
    }

    fun openUrl(ref: DeviceRef, url: String) {
        activeDevices.getNodeFor(ref).openUrl(ref, url)
    }

    fun startVideo(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).videoRecordingStart(ref)
    }

    fun stopVideo(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).videoRecordingStop(ref)
    }

    fun getVideo(ref: DeviceRef): File {
        return activeDevices.getNodeFor(ref).videoRecordingGet(ref)
    }

    fun getVideoLog(ref: DeviceRef): String {
        return activeDevices.getNodeFor(ref).videoRecordingLogGet(ref)
    }

    fun deleteVideo(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).videoRecordingDelete(ref)
    }

    fun uninstallApplication(ref: DeviceRef, bundleId: String) {
        activeDevices.getNodeFor(ref).uninstallApplication(ref, bundleId)
    }

    fun deleteAppData(ref: DeviceRef, bundleId: String) {
        activeDevices.getNodeFor(ref).deleteAppData(ref, bundleId)
    }

    fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO {
        return activeDevices.getNodeFor(ref).state(ref)
    }

    fun createDeviceAsync(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO {
        val node = nodes.filter { it.supports(desiredCaps) }
            .maxByOrNull { it.capacityRemaining(desiredCaps) }
            ?: throw NoAliveNodesException("No node supports $desiredCaps")

        val dto = node.createDeviceForTests(desiredCaps)
        logger.info("Create device dto $dto")

        val logMarker = MapEntriesAppendingMarker(mutableMapOf(
            com.badoo.automation.deviceserver.LogMarkers.DEVICE_REF to dto.ref,
            com.badoo.automation.deviceserver.LogMarkers.UDID to dto.info.udid
        ))
        logger.info(logMarker, "Create device started, register with timeout ${deviceTimeoutInSecs.seconds} secs")

        activeDevices.registerDevice(dto.ref, node, userId)
        return dto
    }

    fun prebootSimulatorForTests(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO {
        val node = nodes.filter { it.supports(desiredCaps) }
            .maxByOrNull { it.capacityRemaining(desiredCaps) }
            ?: throw NoAliveNodesException("No node supports $desiredCaps")

        val dto = node.prebootSimulatorForTests(desiredCaps)
        logger.info("Preboot device dto $dto")

        val logMarker = MapEntriesAppendingMarker(mutableMapOf(
            com.badoo.automation.deviceserver.LogMarkers.DEVICE_REF to dto.ref,
            com.badoo.automation.deviceserver.LogMarkers.UDID to dto.info.udid
        ))
        logger.info(logMarker, "Preboot device started, register with timeout ${deviceTimeoutInSecs.seconds} secs")

        activeDevices.registerDevice(dto.ref, node, userId)
        return dto
    }

    fun deleteReleaseDevice(ref: DeviceRef, reason: String) {
        try {
            activeDevices.releaseDevice(ref, reason)
        } catch (e: com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException) {
            logger.warn("Skipping $ref release because no node knows about it")
        }
    }

    fun deleteReleaseDeviceWitForce(ref: DeviceRef, reason: String) {
        try {
            activeDevices.deleteSimulatorWithForce(ref, reason)
        } catch (e: com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException) {
            logger.warn("Skipping $ref release because no node knows about it")
        }
    }

    fun getDeviceRefs(): List<DeviceDTO> {
        return activeDevices.deviceList()
    }

    fun releaseUserDevices(userId: String, reason: String) {
        val devices = activeDevices.getUserDeviceRefs(userId)
        activeDevices.releaseDevices(devices, reason)
    }

    fun releaseAllDevices(reason: String) {
        val devices = activeDevices.deviceRefs().toList()
        activeDevices.releaseDevices(devices, reason)
    }

    fun locationListScenarios(ref: DeviceRef): List<String> {
        return activeDevices.getNodeFor(ref).locationListScenarios(ref)
    }

    fun locationClear(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).locationClear(ref)
    }

    fun locationSet(ref: DeviceRef, latitude: Double, longitude: Double) {
        activeDevices.getNodeFor(ref).locationSet(ref, latitude, longitude)
    }

    fun locationRunScenario(ref: DeviceRef, scenarioName: String) {
        activeDevices.getNodeFor(ref).locationRunScenario(ref, scenarioName)
    }

    fun locationStartLocationSequence(ref: DeviceRef, speed: Int, distance: Int, interval: Int, waypoints: List<LocationDto>) {
        activeDevices.getNodeFor(ref).locationStartLocationSequence(ref, speed, distance, interval, waypoints)
    }

    fun listFiles(ref: DeviceRef, dataPath: DataPath): List<String> {
        return activeDevices.getNodeFor(ref).listFiles(ref, dataPath)
    }

    fun pullFile(ref: DeviceRef, dataPath: DataPath): File {
        return activeDevices.getNodeFor(ref).pullFile(ref, dataPath)
    }

    fun pullFile(ref: DeviceRef, path: Path): File {
        return activeDevices.getNodeFor(ref).pullFile(ref, path)
    }

    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String) {
        activeDevices.getNodeFor(ref).pushFile(ref, fileName, data, bundleId)
    }

    fun pushFile(ref: DeviceRef, data: ByteArray, path: Path) {
        activeDevices.getNodeFor(ref).pushFile(ref, data, path)
    }

    fun deleteFile(ref: DeviceRef, path: Path) {
        activeDevices.getNodeFor(ref).deleteFile(ref, path)
    }

    fun setEnvironmentVariables(ref: DeviceRef, envs: Map<String, String>) {
        activeDevices.getNodeFor(ref).setEnvironmentVariables(ref, envs)
    }

    fun getEnvironmentVariable(ref: DeviceRef, variableName: String): String {
        return activeDevices.getNodeFor(ref).getEnvironmentVariable(ref, variableName)
    }

    fun resetMedia(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).resetMedia(ref)
    }

    fun listMedia(ref: DeviceRef): List<String> {
        return activeDevices.getNodeFor(ref).listMedia(ref)
    }

    fun listPhotoData(ref: DeviceRef): List<String> {
        return activeDevices.getNodeFor(ref).listPhotoData(ref)
    }

    fun addMedia(ref: DeviceRef, fileName: String, data: ByteArray) {
        activeDevices.getNodeFor(ref).addMedia(ref, fileName, data)
    }

    fun syslog(ref: DeviceRef): File {
        return activeDevices.getNodeFor(ref).syslog(ref)
    }

    fun instrumentationAgentLog(ref: DeviceRef): File {
        return activeDevices.getNodeFor(ref).instrumentationAgentLog(ref)
    }

    fun deleteInstrumentationAgentLog(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).deleteInstrumentationAgentLog(ref)
    }

    fun syslogDelete(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).syslogDelete(ref)
    }

    fun syslogStart(ref: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions) {
        activeDevices.getNodeFor(ref).syslogStart(ref, sysLogCaptureOptions)
    }

    fun syslogStop(ref: DeviceRef) {
        activeDevices.getNodeFor(ref).syslogStop(ref)
    }

    fun getDiagnostic(ref: DeviceRef, type: DiagnosticType, query: DiagnosticQuery): Diagnostic {
        return activeDevices.getNodeFor(ref).getDiagnostic(ref, type, query)
    }

    fun resetDiagnostic(ref: DeviceRef, type: DiagnosticType) {
        activeDevices.getNodeFor(ref).resetDiagnostic(ref, type)
    }

    fun installApplication(ref: String, dto: AppBundleDto) {
        activeDevices.getNodeFor(ref).installApplication(ref, dto)
    }

    fun appInstallationStatus(ref: String): Map<String, Any> {
        return activeDevices.getNodeFor(ref).appInstallationStatus(ref)
    }

    private val appBinariesCache: MutableMap<String, File> = ConcurrentHashMap(200)

    @Synchronized
    fun deployApplication(dto: AppBundleDeployDto) {
        if (isApplicationDeployed(dto)) {
            logger.debug("Application ${dto.appUrl} is already deployed on all nodes. Skipping deployment.")
            return
        }
        val marker = MapEntriesAppendingMarker(mapOf("operation" to "app_deploy"))
        val appBundle = acquireBundle(dto, marker)

        logger.debug(marker, "Starting to deploy application ${dto.appUrl}")

        nodes.forEach { it.deployApplication(appBundle) }

        logger.debug(marker, "Successfully deployed application ${dto.appUrl}")
    }

    private fun isApplicationDeployed(dto: AppBundleDeployDto): Boolean {
        val appBundle = ApplicationBundle(URI(dto.appUrl).toURL())
        return nodes.all { it.isApplicationDeployed(appBundle) }
    }

    private fun acquireBundle(dto: AppBundleDeployDto, marker: MapEntriesAppendingMarker): ApplicationBundle {
        val appBundle = ApplicationBundle(URI(dto.appUrl).toURL())
        downloadApplicationBinary(marker, appBundle)
        appBundle.unpack(logger, marker)
        return appBundle
    }

    fun resetAppBundleCache() {
        val marker = MapEntriesAppendingMarker(mapOf("operation" to "app_cleanup"))
        nodes.forEach { it.resetAppBundleCache() }
        try {
            with(appConfig.appBundleCachePath) {
                deleteRecursivelyIfExist(logger, marker)
                ensureDirectoryExists(logger, marker)
            }
        } catch (e: Exception) {
            logger.error(marker, "Cleaning out local application cache at ${appConfig.appBundleCachePath.absolutePath} failed! Error: ${e.message}", e)
        }
    }

    private fun downloadApplicationBinary(marker: MapEntriesAppendingMarker, appBundle: ApplicationBundle) {
        var size: Long = 0
        val nanos = measureNanoTime {
            logger.debug(marker, "Downloading app bundle to cache ${appBundle.appUrl}. Url: ${appBundle.appUrl}")
            appBundle.downloadApp(logger, marker)
            size = appBundle.bundleZip.length()
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "download_application",
            "duration" to seconds,
            "app_size" to size.shr(20) // Bytes to Megabytes
        )
        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully downloaded application ${appBundle.appUrl} size: $size bytes. Took $seconds seconds")
    }

    fun updateApplicationPlist(deviceRef: String, plistEntry: PlistEntryDTO) {
        return activeDevices.getNodeFor(deviceRef).updateApplicationPlist(deviceRef, plistEntry)
    }
}
