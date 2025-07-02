package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers.Companion.DEVICE_REF
import com.badoo.automation.deviceserver.LogMarkers.Companion.HOSTNAME
import com.badoo.automation.deviceserver.LogMarkers.Companion.UDID
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.host.management.ISimulatorHostChecker
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.repository.SimulatorRegistry
import com.badoo.automation.deviceserver.repository.SimulatorRepository
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import com.badoo.automation.deviceserver.util.pollFor
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.*
import java.util.concurrent.*

class SimulatorsNode(
    val remote: IRemote,
    override val publicHostName: String,
    private val hostChecker: ISimulatorHostChecker,
    private val simulatorLimit: Int,
    concurrentBoots: Int,
    private val wdaSimulatorBundles: WdaSimulatorBundles,
    private val applicationConfiguration: ApplicationConfiguration = ApplicationConfiguration(),
    private val concurrentBootsSemaphore: Semaphore = Semaphore(concurrentBoots, true),
    private val simulatorRepository: SimulatorRepository = SimulatorRepository(remote.remoteExecutor, concurrentBootsSemaphore),
    private val simulatorRegistry: SimulatorRegistry = SimulatorRegistry(
        registryFile = File(System.getProperty("user.home"), ".iosctl/simulator_registry_$publicHostName.json")
    ),
    private val simulatorProvider: SimulatorProvider = SimulatorProvider(remote, simulatorRepository, simulatorRegistry),
    private val portAllocator: PortAllocator = PortAllocator(remote),
    private val simulatorFactory: ISimulatorFactory = object : ISimulatorFactory {}
) : IDeviceNode {
    private val simulatorsBootExecutorService: ExecutorService = Executors.newFixedThreadPool(simulatorLimit)
    private val prepareTasks = ConcurrentHashMap<String, Future<*>>()

    private val createdSimulators = ConcurrentHashMap<DeviceRef, ISimulator>()
    private val allocatedPorts = HashMap<DeviceRef, DeviceAllocatedPorts>()

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
        HOSTNAME to remote.publicHostName
    ))

    // region: Main Simulator operations: Create, Delete
    override fun createMainSimulator(desiredCaps: DesiredCapabilities, bootWaitDuration: Duration): Simulator {
        return simulatorProvider.createMainSimulator(desiredCaps, bootWaitDuration)
    }

    override fun deleteMainSimulator(udid: UDID) {
        simulatorProvider.deleteMainSimulator(udid)
    }
    // endregion

    // region: Simulator Clone operations: Create, Delete
    override fun createDeviceForTests(desiredCaps: DesiredCapabilities): DeviceDTO {
        synchronized(this) {
            if (createdSimulators.size >= simulatorLimit) {
                val message = "$this was asked for a newSimulator, but is already at capacity $simulatorLimit"
                logger.error(logMarker, message)
                throw OverCapacityException(message)
            }

            val usedUdids = createdSimulators.map { it.value.udid }.toSet()
            val simulatorModel: Simulator = simulatorProvider.createSimulatorClone(desiredCaps, usedUdids)

            if (simulatorModel == null) {
                val message = "$this could not construct or match a simulator for $desiredCaps"
                logger.error(logMarker, message)
                throw RuntimeException(message)
            }

            val ref = deviceRefFromUDID(simulatorModel.udid, remote.publicHostName)
            val simLogMarker = MapEntriesAppendingMarker(mapOf(
                HOSTNAME to remote.hostName,
                UDID to simulatorModel.udid,
                DEVICE_REF to ref
            ))

            logger.debug(simLogMarker, "Will create simulator $ref")

            val ports = portAllocator.allocateDAP()
            allocatedPorts[ref] = ports

            val simulator = simulatorFactory.newSimulator(
                ref = ref,
                remote = remote,
                simulatorModel = simulatorModel,
                ports = ports,
                wdaSimulatorBundles = wdaSimulatorBundles,
                useWda = desiredCaps.useWda
            )

            createdSimulators[ref] = simulator
            prepareTasks[ref] = simulatorsBootExecutorService.submit {
                simulator.prepareAsync(concurrentBootsSemaphore)
            }

            logger.debug(simLogMarker, "Created simulator $ref")

            return DeviceDTO(simulator)
        }
    }

    override fun deleteReleaseDeviceForTests(deviceRef: DeviceRef, reason: String): Boolean {
        val iSimulator = createdSimulators[deviceRef] ?: return false
        cancelRunningSimulatorTask(deviceRef, "deleteRelease")
        iSimulator.release("deleteRelease $reason $deviceRef")
        simulatorProvider.deleteSimulatorClone(iSimulator.udid)
        createdSimulators.remove(deviceRef)
        val entries = allocatedPorts[deviceRef] ?: return true
        portAllocator.deallocateDAP(entries)
        return true
    }

    private fun cancelRunningSimulatorTask(deviceRef: DeviceRef, reason: String) {
        val task = prepareTasks[deviceRef]
        val markerData = mutableMapOf(
            DEVICE_REF to deviceRef, "action_name" to "cancelRunningSimulatorTask", "action_reason" to reason
        )
        val marker = MapEntriesAppendingMarker(markerData)
        marker.add(logMarker)

        if (task == null) {
            logger.debug(marker, "No async task found for Simulator $deviceRef while performing $reason")
        } else {
            if (task.isDone) {
                logger.debug(marker, "Async task for Simulator $deviceRef is already done while performing $reason")
            } else {
                val startTime = System.nanoTime()
                task.cancel(true)
                val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                marker.add(MapEntriesAppendingMarker(mapOf("cancellation_duration" to duration)))
                logger.error(marker, "Cancelled async task for Simulator $deviceRef while performing $reason. Cancellation took $duration ms")

                val startWaitingTime = System.nanoTime()
                pollFor(
                    timeOut = Duration.ofSeconds(60),
                    reasonName = "Waiting for async task to finish. Device: $deviceRef, reason: $reason",
                    shouldReturnOnTimeout = true,
                    retryInterval = Duration.ofMillis(50),
                    logger = logger,
                    marker = marker
                ) {
                    task.isDone
                }

                val durationWaitingTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startWaitingTime)
                marker.add(MapEntriesAppendingMarker(mapOf("cancellation_wait_duration" to durationWaitingTime)))
                logger.error(marker, "Waited for async task to cancel and finish for Simulator $deviceRef while performing $reason. Waiting time took $durationWaitingTime ms")

            }
        }
        prepareTasks.remove(deviceRef)
    }
    // endregion

    // region: Node info operations: List Simulators, NodeInfo, getDeviceFor(ref)
    override fun list(): List<DeviceDTO> {
        return createdSimulators.map { DeviceDTO(it.value) }
    }

    override fun getNodeInfo(): NodeInfo {
        return NodeInfo.getNodeInfo(remote)
    }

    override fun getDeviceFor(ref: DeviceRef): ISimulator {
        return createdSimulators[ref]!! //FIXME: replace with explicit unwrapping
    }
    // endregion

    // region: Node operations: Prepare & Reboot & Dispose
    override fun prepareNode() {
        logger.info(logMarker, "Preparing node ${remote.hostName}")
        hostChecker.checkPrerequisites()
        hostChecker.createDirectories()
        hostChecker.cleanup()
        hostChecker.setupHost()
        portAllocator.refreshPortAvailability()
    }

    override fun dispose() {
        logger.info(logMarker, "Finalising simulator pool for ${remote.hostName}")
        val simulatorsToDelete = createdSimulators.keys

        simulatorsToDelete.parallelStream().forEach {
            deleteReleaseDeviceForTests(it, "Finalising pool for ${remote.hostName}")
        }

        hostChecker.killDiskCleanupThread()

        logger.info(logMarker, "Finalised simulator pool for ${remote.hostName}")
    }

    override fun reboot() {
        val uptimeInfoBeforeReboot = getNodeInfo()
        logger.info(logMarker, "Scheduling node for reboot $publicHostName. Current uptime: [${uptimeInfoBeforeReboot.uptime}]. Boot time: ${uptimeInfoBeforeReboot.bootTime}")

        try {
            remote.shell("sudo /sbin/reboot", returnOnFailure = true)
        } catch (e: SshConnectionException) {
            // ignore
        }

        Thread.sleep(Duration.ofSeconds(60).toMillis())

        var isReachable = false

        pollFor(
            Duration.ofSeconds(300),
            "Waiting to be reachable after reboot",
            true,
            Duration.ofSeconds(10),
            logger,
            logMarker
        ) {
            isReachable = isReachable()
            isReachable
        }

        if (!isReachable) {
            logger.error(logMarker, "Node $publicHostName node is not reachable after reboot")
            return
        }

        val uptimeInfoAfterReboot = getNodeInfo()
        val wasRebooted = uptimeInfoAfterReboot.bootTime > uptimeInfoBeforeReboot.bootTime

        if (wasRebooted) {
            logger.info(logMarker, "Node $publicHostName was rebooted successfully. Current uptime: [${uptimeInfoAfterReboot.uptime}]. Boot time: ${uptimeInfoBeforeReboot.bootTime}")
        } else {
            logger.error(logMarker, "Node $publicHostName was not rebooted. Current uptime: [${uptimeInfoAfterReboot.uptime}]. Boot time: ${uptimeInfoBeforeReboot.bootTime}")
        }
    }
    // endregion

    // region: Node capabilities & capacity operations
    override fun isReachable(): Boolean = remote.isReachable()

    override fun supports(desiredCaps: DesiredCapabilities): Boolean {
        return desiredCaps.arch == null || listOf("x86_64").contains(desiredCaps.arch)
    }

    override fun capacityRemaining(desiredCaps: DesiredCapabilities): Float {
        return (simulatorLimit - createdSimulators.size) * 1F / simulatorLimit
    }

    override fun totalCapacity(desiredCaps: DesiredCapabilities): Int {
        return if (supports(desiredCaps)) simulatorLimit else 0
    }
    // endregion

    // region: Node Standard Object methods
    override fun toString(): String {
        return "${javaClass.simpleName} at $publicHostName"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimulatorsNode

        return publicHostName == other.publicHostName
    }

    override fun hashCode(): Int {
        return publicHostName.hashCode()
    }
    // endregion

    // region: =============================================
    // endregion

    // region: Simulator App instalation operations
    private val appBinariesCache: MutableMap<String, File> = ConcurrentHashMap(200)

    override fun updateApplicationPlist(ref: DeviceRef, plistEntry: PlistEntryDTO) {
        val applicationContainer = getDeviceFor(ref).applicationContainer(plistEntry.bundleId)
        val path = File(plistEntry.fileName).toPath()
        val key = plistEntry.key
        val value = plistEntry.value

        if (plistEntry.command == "set") {
            applicationContainer.setPlistValue(path, key, value)
        } else {
            val type = plistEntry.type ?: throw RuntimeException("Unable to add new property $key as it requires value type.")
            applicationContainer.addPlistValue(path, key, value, type)
        }
    }

    private val appInstaller: AppInstaller = AppInstaller(remote)

    override fun installApplication(deviceRef: DeviceRef, appBundleDto: AppBundleDto) {
        val appBinaryPath = appBinariesCache[appBundleDto.appUrl]
            ?: throw RuntimeException("Unable to find requested binary. Deploy binary first from url ${appBundleDto.appUrl}")

        val device: ISimulator = getDeviceFor(deviceRef)
        device.installApplication(appInstaller, appBundleDto.appUrl, appBinaryPath, appBundleDto.bundleId)
    }

    override fun uninstallApplication(deviceRef: DeviceRef, bundleId: String) {
        getDeviceFor(deviceRef).uninstallApplication(bundleId, appInstaller)
    }

    override fun appInstallationStatus(deviceRef: DeviceRef): Map<String, Any> {
        return getDeviceFor(deviceRef).appInstallationStatus()
    }

    override fun deployApplication(appBundle: ApplicationBundle) {
        val appDirectory: File = appBundle.appDirectory!!
        val key = appBundle.appUrl.toExternalForm()
        appBinariesCache[key] = appDirectory
    }

    override fun deleteAppData(deviceRef: DeviceRef, bundleId: String) {
        return getDeviceFor(deviceRef).dataContainer(bundleId).delete()
    }
    // endregion

    // region: Simulator enquiry operations: State, EndpointFor(port) & DeviceDTO & List Apps
    override fun state(deviceRef: DeviceRef): SimulatorStatusDTO {
        return getDeviceFor(deviceRef).status()
    }

    override fun endpointFor(deviceRef: DeviceRef, port: Int): URL {
        return getDeviceFor(deviceRef).endpointFor(port)
    }

    override fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO {
        return DeviceDTO(getDeviceFor(deviceRef))
    }

    override fun listApps(deviceRef: DeviceRef): List<FBSimctlAppInfo> = getDeviceFor(deviceRef).listApps()
    // endregion

    // region: Simulator Media operations
    override fun resetMedia(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).media.reset()
    }

    override fun listMedia(deviceRef: DeviceRef): List<String> {
        return getDeviceFor(deviceRef).media.list()
    }

    override fun listPhotoData(deviceRef: DeviceRef): List<String> {
        return getDeviceFor(deviceRef).media.listPhotoData()
    }

    override fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray) {
        getDeviceFor(deviceRef).media.addMedia(File(fileName), data)
    }
    // endregion

    // region: Simulator Syslog operations
    override fun syslogStart(deviceRef: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions) {
        getDeviceFor(deviceRef).osLog.startWritingLog(sysLogCaptureOptions)
    }

    override fun syslogStop(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).osLog.stopWritingLog()
    }

    override fun syslogDelete(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).osLog.deleteLogFiles()
    }
    // endregion

    // region: Simulator Push Notification & Pasteboard & Permissions & Shake operations
    override fun sendPushNotification(deviceRef: DeviceRef, bundleId: String, notificationContent: ByteArray) {
        getDeviceFor(deviceRef).sendPushNotification(bundleId, notificationContent)
    }

    override fun sendPasteboard(deviceRef: DeviceRef, payload: ByteArray) {
        getDeviceFor(deviceRef).sendPasteboard(payload)
    }

    override fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto) {
        getDeviceFor(deviceRef).setPermissions(appPermissions.bundleId, appPermissions.permissions)
    }

    override fun shake(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).shake()
    }
    // endregion

    // region: Simulator Location operations
    override fun locationListScenarios(deviceRef: DeviceRef): List<String> {
        return getDeviceFor(deviceRef).locationManager.listScenarios()
    }

    override fun locationClear(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).locationManager.clear()
    }

    override fun locationSet(deviceRef: DeviceRef, latitude: Double, longitude: Double) {
        getDeviceFor(deviceRef).locationManager.setLocation(latitude, longitude)
    }

    override fun locationRunScenario(deviceRef: DeviceRef, scenarioName: String) {
        getDeviceFor(deviceRef).locationManager.runScenario(scenarioName)
    }

    override fun locationStartLocationSequence(
        deviceRef: DeviceRef, speed: Int, distance: Int, interval: Int, waypoints: List<LocationDto>
    ) {
        getDeviceFor(deviceRef).locationManager.startLocationSequence(speed, distance, interval, waypoints)
    }
    // endregion

    // region: Simulator Crash Log operations
    override fun syslog(deviceRef: DeviceRef): File {
        return getDeviceFor(deviceRef).osLog.osLogFile
    }

    override fun lastCrashLog(deviceRef: DeviceRef): CrashLog {
        return getDeviceFor(deviceRef).lastCrashLog()
    }

    override fun crashLogs(deviceRef: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        return getDeviceFor(deviceRef).crashLogs(pastMinutes)
    }

    override fun crashLogs(deviceRef: DeviceRef, appName: String?): List<CrashLog> {
        throw NotImplementedError()
    }

    override fun deleteCrashLogs(deviceRef: DeviceRef): Boolean {
        return getDeviceFor(deviceRef).deleteCrashLogs()
    }

    override fun instrumentationAgentLog(deviceRef: DeviceRef): File {
        return getDeviceFor(deviceRef).instrumentationAgentLog
    }

    override fun deleteInstrumentationAgentLog(deviceRef: DeviceRef) {
        val logFile = getDeviceFor(deviceRef).instrumentationAgentLog
        Files.write(logFile.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
    }
    // endregion

    // region: Simulator Video Recording operations
    override fun videoRecordingDelete(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.delete()
    }

    override fun videoRecordingGet(deviceRef: DeviceRef): ByteArray {
        return getDeviceFor(deviceRef).videoRecorder.getRecording()
    }

    override fun videoRecordingLogGet(deviceRef: DeviceRef): String {
        return getDeviceFor(deviceRef).videoRecorder.getRecordingLog()
    }

    override fun videoRecordingStart(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.start()
    }

    override fun videoRecordingStop(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.stop()
    }
    // endregion

    // region: Simulator Data Container operations (file operations)
    override fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String> {
        return getDeviceFor(deviceRef).dataContainer(dataPath.bundleId).listFiles(dataPath.path)
    }

    override fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray {
        return getDeviceFor(deviceRef).dataContainer(dataPath.bundleId).readFile(dataPath.path)
    }

    override fun pullFile(deviceRef: DeviceRef, path: Path): ByteArray {
        return getDeviceFor(deviceRef).sharedContainer().readFile(path)
    }

    override fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String) {
        getDeviceFor(ref).dataContainer(bundleId).writeFile(File(fileName), data)
    }

    override fun pushFile(ref: DeviceRef, data: ByteArray, path: Path) {
        getDeviceFor(ref).sharedContainer().writeFile(data, path)
    }

    override fun deleteFile(ref: DeviceRef, path: Path) {
        getDeviceFor(ref).sharedContainer().delete(path)
    }
    // endregion

    // region: Simulator Safari operations
    override fun openUrl(deviceRef: DeviceRef, url: String) {
        getDeviceFor(deviceRef).openUrl(url)
    }

    override fun clearSafariCookies(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).clearSafariCookies()
    }
    // endregion

    // region: Simulator Environment variables operations
    override fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>) {
        getDeviceFor(deviceRef).setEnvironmentVariables(envs)
    }

    override fun getEnvironmentVariable(deviceRef: DeviceRef, variableName: String): String {
        return getDeviceFor(deviceRef).getEnvironmentVariable(variableName)
    }
    // endregion
}
