package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.command.ShellCommand
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.ios.ActiveDevices
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

private val INFINITE_DEVICE_TIMEOUT: Duration = Duration.ofSeconds(Integer.MAX_VALUE.toLong())
private const val MAX_TEMP_FILE_AGE = 7_200_000L // MILLI SEC

class DeviceManager(
        config: DeviceServerConfig,
        nodeFactory: IHostFactory,
        activeDevices: ActiveDevices = ActiveDevices()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val deviceTimeoutInSecs: Duration
    private val nodeRegistry = NodeRegistry(activeDevices)
    private val autoRegistrar = NodeRegistrar(
            nodesConfig = config.nodes,
            nodeFactory = nodeFactory,
            nodeRegistry = nodeRegistry
    )
    private val appConfig = ApplicationConfiguration()

    init {
        val timeoutFromConfig: Long? = config.timeouts["device"]?.toLong()

        deviceTimeoutInSecs =
                if (timeoutFromConfig != null && timeoutFromConfig > 0) {
                    Duration.ofSeconds(timeoutFromConfig)
                } else {
                    INFINITE_DEVICE_TIMEOUT
                }
    }

    private val File.isTestArtifact get(): Boolean {
        return name.contains(".app.zip.")
                || name.contains("fbsimctl-")
                || name.contains("videoRecording_")
                || name.contains("iOS_SysLog_")
                || name.contains("device_agent_log_")

    }

    private val shellExecutor = ShellCommand()

    fun extractTestApp() {
        val testHelperArchiveFileName = "TestHelper.app.tar.bz2"
        val testHelperRoot = File(appConfig.remoteTestHelperAppBundleRoot)
        val testHelperArchive = File(testHelperRoot, testHelperArchiveFileName)

        logger.info("Start to extract TestHelper application $testHelperArchiveFileName to ${testHelperRoot.absolutePath}")

        testHelperRoot.deleteRecursively()
        testHelperRoot.mkdirs()

        val testHelperStream = DeviceManager::class.java.classLoader.getResourceAsStream(testHelperArchiveFileName)

        if (testHelperStream == null) {
            logger.error("Failed to find test helper file $testHelperArchiveFileName in resources")
            return
        }

        testHelperStream.use { inputStream ->
            testHelperArchive.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        val result = shellExecutor.exec(listOf("tar", "--directory=$testHelperRoot", "-jxvf", testHelperArchive.absolutePath))
        check(result.isSuccess) {
            "Failed to unpack test helper app. STDOUT: ${result.stdOut}, STDERR ${result.stdErr}"
        }

        logger.info("Successfully extracted TestHelper application $testHelperArchiveFileName to ${testHelperRoot.absolutePath}")
    }

    fun extractVideoRecorder() {
        val videoRecorderFile = appConfig.remoteVideoRecorder
        videoRecorderFile.delete()
        videoRecorderFile.parentFile.mkdirs()

        logger.info("Start to copy Video recorder script ${videoRecorderFile.name} from resources to ${videoRecorderFile.absolutePath}")

        val videoRecorderFileStream = DeviceManager::class.java.classLoader.getResourceAsStream(videoRecorderFile.name)

        if (videoRecorderFileStream == null) {
            logger.error("Failed to find Video recorder script ${videoRecorderFile.name} in resources")
            return
        }

        videoRecorderFileStream.use { inputStream ->
            videoRecorderFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        videoRecorderFile.setWritable(false)
        videoRecorderFile.setExecutable(true)

        logger.info("Successfully copied Video recorder script ${videoRecorderFile.name} from resources to ${videoRecorderFile.absolutePath}")
    }

    fun cleanupTemporaryFiles() {
        val currentTime = System.currentTimeMillis()
        fun File.isOld(): Boolean {
            val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
            val lastModified = attributes.lastModifiedTime().toMillis()
            return currentTime - lastModified > MAX_TEMP_FILE_AGE
        }

        File(getTmpDir()).walk().forEach {
            if (it.isFile && it.isTestArtifact && it.isOld()) {
                try {
                    it.delete()
                } catch (e: RuntimeException) {
                    logger.error("Failed to cleanup file ${it.absolutePath}. Error: ${e.message}", e)
                }
            }
        }

        val appCacheDir: File = appConfig.appBundleCachePath
        appCacheDir.mkdirs()

        appCacheDir.walk().forEach {
            if (it != appCacheDir && it.isOld()) {
                try {
                    it.deleteRecursively()
                } catch (e: RuntimeException) {
                    logger.error("Failed to cleanup file ${it.absolutePath}. Error: ${e.message}", e)
                }
            }
        }

        logger.debug("Cleanup complete.")
    }

    private fun getTmpDir(): String {
        return when {
            System.getProperty("os.name") == "Linux" -> "/tmp"
            System.getenv("TMPDIR") != null -> System.getenv("TMPDIR")
            else -> throw RuntimeException("Unknown TEMP directory to clean up")
        }
    }

    private lateinit var cleanUpTask: ScheduledFuture<*>

    fun startPeriodicFileCleanup() {
        val runnable = Runnable {
            try {
                cleanupTemporaryFiles()
            } catch (t: Throwable) {
                logger.error(
                    "Cleanup failed. ${t.javaClass.name} ${t.message}\n${
                        t.stackTrace.map { it.toString() }.joinToString { "\n" }
                    }"
                )
            }
        }
        cleanUpTask = periodicTasksPool.scheduleWithFixedDelay(
            runnable,
            0,
            60,
            TimeUnit.MINUTES
        )

    }

    fun startAutoRegisteringDevices() {
        autoRegistrar.startAutoRegistering()
    }

    fun restartNodesGracefully(isParallelRestart: Boolean): Boolean {
        return autoRegistrar.restartNodesGracefully(isParallelRestart)
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

    fun sendPushNotification(ref: DeviceRef, bundleId: String, notificationContent: ByteArray) {
        nodeRegistry.activeDevices.getNodeFor(ref).sendPushNotification(ref, bundleId, notificationContent)
    }

    fun sendPasteboard(ref: DeviceRef, payload: ByteArray) {
        nodeRegistry.activeDevices.getNodeFor(ref).sendPasteboard(ref, payload)
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

    fun listApps(ref: DeviceRef): List<FBSimctlAppInfo> = nodeRegistry.activeDevices.getNodeFor(ref).listApps(ref)

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

    fun deleteAppData(ref: DeviceRef, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).deleteAppData(ref, bundleId)
    }

    fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO {
        return nodeRegistry.activeDevices.getNodeFor(ref).state(ref)
    }

    fun createDeviceAsync(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO {
        try {
            return nodeRegistry.createDeviceAsync(desiredCaps, deviceTimeoutInSecs, userId)
        } catch (e: NoNodesRegisteredException) {
            val erredNodes = autoRegistrar.nodeWrappers.filter { n -> n.lastError != null }
            val errors = erredNodes.joinToString { n -> "${n.node.remoteAddress} -> ${n.lastError?.localizedMessage}" }
            throw(NoNodesRegisteredException(e.message + "\n$errors"))
        }
    }

    fun deleteReleaseDevice(ref: DeviceRef, reason: String) {
        nodeRegistry.deleteReleaseDevice(ref, reason)
    }

    fun getDeviceRefs(): List<DeviceDTO> {
        return nodeRegistry.activeDevices.deviceList()
    }

    fun releaseUserDevices(userId: String, reason: String) {
        val devices = nodeRegistry.activeDevices.getUserDeviceRefs(userId)
        nodeRegistry.activeDevices.releaseDevices(devices, reason)
    }
    fun releaseAllDevices(reason: String) {
        val devices = nodeRegistry.activeDevices.deviceRefs().toList()
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

    fun pullFile(ref: DeviceRef, path: Path): ByteArray {
        return nodeRegistry.activeDevices.getNodeFor(ref).pullFile(ref, path)
    }

    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).pushFile(ref, fileName, data, bundleId)
    }

    fun pushFile(ref: DeviceRef, data: ByteArray, path: Path) {
        nodeRegistry.activeDevices.getNodeFor(ref).pushFile(ref, data, path)
    }

    fun deleteFile(ref: DeviceRef, path: Path) {
        nodeRegistry.activeDevices.getNodeFor(ref).deleteFile(ref, path)
    }

    fun setEnvironmentVariables(ref: DeviceRef, envs: Map<String, String>) {
        nodeRegistry.activeDevices.getNodeFor(ref).setEnvironmentVariables(ref, envs)
    }

    fun getEnvironmentVariable(ref: DeviceRef, variableName: String): String {
        return nodeRegistry.activeDevices.getNodeFor(ref).getEnvironmentVariable(ref, variableName)
    }

    fun resetMedia(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetMedia(ref)
    }

    fun listMedia(ref: DeviceRef): List<String> {
        return nodeRegistry.activeDevices.getNodeFor(ref).listMedia(ref)
    }

    fun listPhotoData(ref: DeviceRef): List<String> {
        return nodeRegistry.activeDevices.getNodeFor(ref).listPhotoData(ref)
    }

    fun addMedia(ref: DeviceRef, fileName: String, data: ByteArray) {
        nodeRegistry.activeDevices.getNodeFor(ref).addMedia(ref, fileName, data)
    }

    fun syslog(ref: DeviceRef): File {
        return nodeRegistry.activeDevices.getNodeFor(ref).syslog(ref)
    }

    fun deviceAgentLog(ref: DeviceRef): File {
        return nodeRegistry.activeDevices.getNodeFor(ref).deviceAgentLog(ref)
    }

    fun deviceAgentLogDelete(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).deviceAgentLogDelete(ref)
    }

    fun syslogDelete(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).syslogDelete(ref)
    }

    fun syslogStart(ref: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions) {
        nodeRegistry.activeDevices.getNodeFor(ref).syslogStart(ref, sysLogCaptureOptions)
    }

    fun syslogStop(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).syslogStop(ref)
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

    fun appInstallationStatus(ref: String): Map<String, Boolean> {
        return nodeRegistry.activeDevices.getNodeFor(ref).appInstallationStatus(ref)
    }

    fun deployApplication(dto: AppBundleDto) {
        val marker = MapEntriesAppendingMarker(mapOf("operation" to "app_deploy"))
        val appBundle = acquireBundle(dto, marker)

        logger.debug(marker, "Starting to deploy application ${dto.appUrl}")

        nodeRegistry.getAll().parallelStream().forEach { nodeWrapper ->
            nodeWrapper.node.deployApplication(appBundle)
        }

        logger.debug(marker, "Successfully deployed application ${dto.appUrl}")
    }

    private fun acquireBundle(dto: AppBundleDto, marker: MapEntriesAppendingMarker): ApplicationBundle {
        val appBundle = ApplicationBundle(URL(dto.appUrl))
        downloadApplicationBinary(marker, appBundle)
        appBundle.unpack(logger, marker)
        return appBundle
    }

    private fun downloadApplicationBinary(marker: MapEntriesAppendingMarker, appBundle: ApplicationBundle) {
        var size: Long = 0
        val nanos = measureNanoTime {
            logger.debug(marker, "Downloading app bundle to cache ${appBundle.appUrl}. Url: ${appBundle.appUrl}")
            try {
                logger.info(marker, "Cleaning out local application cache at ${appConfig.appBundleCachePath.absolutePath}")
                appConfig.appBundleCachePath.deleteRecursively()
                appConfig.appBundleCachePath.mkdirs()
                logger.info(marker, "Cleaning out local application cache at ${appConfig.appBundleCachePath.absolutePath} is done")
            } catch (t: Throwable) {
                logger.error(marker, "Cleaning out local application cache at ${appConfig.appBundleCachePath.absolutePath} failed! Error: ${t.message}", t)
            }
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
        return nodeRegistry.activeDevices.getNodeFor(deviceRef).updateApplicationPlist(deviceRef, plistEntry)
    }
}
