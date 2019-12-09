package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.host.management.util.AutoreleaseLooper
import com.badoo.automation.deviceserver.ios.ActiveDevices
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

private val INFINITE_DEVICE_TIMEOUT: Duration = Duration.ofSeconds(Integer.MAX_VALUE.toLong())

class DeviceManager(
        config: DeviceServerConfig,
        nodeFactory: IHostFactory,
        activeDevices: ActiveDevices = ActiveDevices(),
        private val autoreleaseLooper: IAutoreleaseLooper = AutoreleaseLooper()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val deviceTimeoutInSecs: Duration
    private val nodeRegistry = NodeRegistry(activeDevices)
    private val autoRegistrar = NodeRegistrar(
            nodesConfig = config.nodes,
            nodeFactory = nodeFactory,
            nodeRegistry = nodeRegistry
    )

    init {
        val timeoutFromConfig: Long? = config.timeouts["device"]?.toLong()

        deviceTimeoutInSecs =
                if (timeoutFromConfig != null && timeoutFromConfig > 0) {
                    Duration.ofSeconds(timeoutFromConfig)
                } else {
                    INFINITE_DEVICE_TIMEOUT
                }
    }

    fun cleanupTemporaryFiles() {
        val tmpDir: String = when {
            System.getProperty("os.name") == "Linux" -> "/tmp"
            System.getenv("TMPDIR") != null -> System.getenv("TMPDIR")
            else -> throw RuntimeException("Unknown TEMP directory to clean up")
        }

        File(tmpDir).walk().forEach {
            if (it.isFile && (it.name.contains(".app.zip.") || it.name.contains(Regex("videoRecording_.*(mjpeg|mp4)")))) {
                try {
                    logger.debug("Cleanup: deleting temporary file ${it.absolutePath}")
                    it.delete()
                } catch (e: RuntimeException) {
                    logger.error("Failed to cleanup file ${it.absolutePath}. Error: ${e.message}", e)
                }
            }
        }

        val appCache = ApplicationConfiguration().appBundleCachePath
        appCache.deleteRecursively()
        appCache.mkdirs()

        logger.debug("Cleanup complete.")
    }

    fun startAutoRegisteringDevices() {
        autoRegistrar.startAutoRegistering()
    }

    fun restartNodesGracefully(isParallelRestart: Boolean): Boolean {
        return autoRegistrar.restartNodesGracefully(isParallelRestart, INFINITE_DEVICE_TIMEOUT)
    }

    fun launchAutoReleaseLoop() {
        autoreleaseLooper.autoreleaseLoop(this)
    }

    private val zombieReaper = ZombieReaper()

    fun launchZombieReaper() {
        zombieReaper.launchReapingZombies()
    }

    fun getStatus(): Map<String, Any> {
        return mapOf(
            "initialized" to nodeRegistry.getInitialRegistrationComplete(),
            "sessions" to listOf(nodeRegistry.activeDevices.getStatus()).toString()
        )
    }

    fun readyForRelease(): List<DeviceRef> {
        return nodeRegistry.activeDevices.readyForRelease()
    }

    fun nextReleaseAtSeconds(): Long {
        return nodeRegistry.activeDevices.nextReleaseAtSeconds()
    }

    fun getTotalCapacity(desiredCaps: DesiredCapabilities): Map<String, Int> {
        return nodeRegistry.capacitiesTotal(desiredCaps)
    }

    fun getGetDeviceDTO(ref: DeviceRef): DeviceDTO {
        return nodeRegistry.activeDevices.getNodeFor(ref).getDeviceDTO(ref)
    }

    fun clearSafariCookies(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).clearSafariCookies(ref)
    }

    fun resetAsyncDevice(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetAsync(ref)
    }

    fun  approveAccess(ref: DeviceRef, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).approveAccess(ref, bundleId)
    }

    fun setPermissions(ref: DeviceRef, permissions: AppPermissionsDto) {
        nodeRegistry.activeDevices.getNodeFor(ref).setPermissions(ref, permissions)
    }

    fun getEndpointFor(ref: DeviceRef, port: Int): URL {
        return nodeRegistry.activeDevices.getNodeFor(ref).endpointFor(ref, port)
    }

    fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        return nodeRegistry.activeDevices.getNodeFor(ref).crashLogs(ref, pastMinutes)
    }

    fun crashLogs(ref: DeviceRef, appName: String?): List<CrashLog> {
        val node = nodeRegistry.activeDevices.getNodeFor(ref)
        return node.crashLogs(ref, appName)
    }

    fun deleteCrashLogs(ref: DeviceRef): Boolean {
        return nodeRegistry.activeDevices.getNodeFor(ref).deleteCrashLogs(ref)
    }

    fun getLastCrashLog(ref: DeviceRef): CrashLog {
        return nodeRegistry.activeDevices.getNodeFor(ref).lastCrashLog(ref)
    }

    fun shake(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).shake(ref)
    }

    fun openUrl(ref: DeviceRef, url: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).openUrl(ref, url)
    }

    fun startVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingStart(ref)
    }

    fun stopVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingStop(ref)
    }

    fun getVideo(ref: DeviceRef): ByteArray {
        return nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingGet(ref)
    }

    fun deleteVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingDelete(ref)
    }

    fun uninstallApplication(ref: DeviceRef, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).uninstallApplication(ref, bundleId)
    }

    fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO {
        return nodeRegistry.activeDevices.getNodeFor(ref).state(ref)
    }

    fun createDeviceAsync(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO {
        try {
            return nodeRegistry.createDeviceAsync(desiredCaps, deviceTimeoutInSecs, userId)
        } catch(e: NoNodesRegisteredException) {
            val erredNodes = autoRegistrar.nodeWrappers.filter { n -> n.lastError != null }
            val errors = erredNodes.joinToString { n -> "${n.node.remoteAddress} -> ${n.lastError?.localizedMessage}" }
            throw(NoNodesRegisteredException(e.message+"\n$errors"))
        }
    }

    fun deleteReleaseDevice(ref: DeviceRef, reason: String) {
        try { // using try-catch here not to expose tryGetNodeFor
            nodeRegistry.activeDevices.releaseDevice(ref, reason)
        } catch (e: DeviceNotFoundException) {
            logger.warn("Skipping $ref release because no node knows about it")
            return
        }
    }

    fun getDeviceRefs() : List<DeviceDTO> {
        return nodeRegistry.activeDevices.deviceList()
    }

    fun releaseUserDevices(userId: String, reason: String) {
        val devices = nodeRegistry.activeDevices.getUserDeviceRefs(userId)
        nodeRegistry.activeDevices.releaseDevices(devices, reason)
    }

    fun isReady(): Boolean {
        return nodeRegistry.getInitialRegistrationComplete()
    }

    fun listFiles(ref: DeviceRef, dataPath: DataPath): List<String> {
        return nodeRegistry.activeDevices.getNodeFor(ref).listFiles(ref, dataPath)
    }

    fun pullFile(ref: DeviceRef, dataPath: DataPath): ByteArray {
        return nodeRegistry.activeDevices.getNodeFor(ref).pullFile(ref, dataPath)
    }

    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).pushFile(ref, fileName, data, bundleId)
    }

    fun setEnvironmentVariables(ref: DeviceRef, envs: Map<String, String>) {
        nodeRegistry.activeDevices.getNodeFor(ref).setEnvironmentVariables(ref, envs)
    }

    fun resetMedia(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetMedia(ref)
    }

    fun listMedia(ref: DeviceRef): String {
        return nodeRegistry.activeDevices.getNodeFor(ref).listMedia(ref)
    }

    fun addMedia(ref: DeviceRef, fileName: String, data: ByteArray) {
        nodeRegistry.activeDevices.getNodeFor(ref).addMedia(ref, fileName, data)
    }

    fun getDiagnostic(ref: DeviceRef, type: DiagnosticType, query: DiagnosticQuery): Diagnostic {
        return nodeRegistry.activeDevices.getNodeFor(ref).getDiagnostic(ref, type, query)
    }

    fun resetDiagnostic(ref: DeviceRef, type: DiagnosticType) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetDiagnostic(ref, type)
    }

    private val applicationsCache: ConcurrentHashMap<String, ApplicationBundle> = ConcurrentHashMap()

    fun installApplication(ref: String, dto: AppBundleDto) {
        nodeRegistry.activeDevices.getNodeFor(ref).installApplication(ref, dto)
    }

    fun deployApplication(dto: AppBundleDto) {
        val marker = MapEntriesAppendingMarker(mapOf("operation" to "app_deploy"))
        val appBundle = applicationsCache.computeIfAbsent(dto.appUrl) { acquireBundle(dto, marker) }

        logger.debug(marker, "Starting to deploy application ${dto.appUrl}")

        nodeRegistry.getAll().parallelStream().forEach { nodeWrapper ->
            nodeWrapper.node.deployApplication(appBundle)
        }

        logger.debug(marker, "Successfully deployed application ${dto.appUrl}")
    }

    private fun acquireBundle(dto: AppBundleDto, marker: MapEntriesAppendingMarker): ApplicationBundle {
        val appBundle = ApplicationBundle(URL(dto.appUrl), dto.bundleId)
        downloadApplicationBinary(marker, appBundle)
        appBundle.unpack(logger, marker)
        return appBundle
    }

    private fun downloadApplicationBinary(marker: MapEntriesAppendingMarker, appBundle: ApplicationBundle) {
        var size: Long = 0
        val nanos = measureNanoTime {
            logger.debug(marker, "Downloading app bundle to cache ${appBundle.bundleId}. Url: ${appBundle.appUrl}")
            appBundle.downloadApp(logger, marker)
            size = appBundle.bundleZip.length()
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "download_application",
            "app_bundle_id" to appBundle.bundleId,
            "duration" to seconds,
            "app_size" to size.shr(20) // Bytes to Megabytes
        )
        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully downloaded application ${appBundle.bundleId} size: $size bytes. Took $seconds seconds")
    }

    fun updateApplicationPlist(deviceRef: String, plistEntry: PlistEntryDTO) {
        return nodeRegistry.activeDevices.getNodeFor(deviceRef).updateApplicationPlist(deviceRef, plistEntry)
    }
}
