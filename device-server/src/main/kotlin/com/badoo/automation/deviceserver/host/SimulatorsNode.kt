package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers.Companion.DEVICE_REF
import com.badoo.automation.deviceserver.LogMarkers.Companion.HOSTNAME
import com.badoo.automation.deviceserver.LogMarkers.Companion.UDID
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.host.management.ISimulatorHostChecker
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.simulatorsThreadPool
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.*
import kotlin.collections.HashMap
import kotlin.system.measureNanoTime

class SimulatorsNode(
        val remote: IRemote,
        override val publicHostName: String,
        private val hostChecker: ISimulatorHostChecker,
        private val simulatorLimit: Int,
        concurrentBoots: Int,
        private val wdaSimulatorBundles: WdaSimulatorBundles,
        private val applicationConfiguration: ApplicationConfiguration = ApplicationConfiguration(),
        private val simulatorProvider: SimulatorProvider = SimulatorProvider(remote, applicationConfiguration.simulatorBackupPath),
        private val portAllocator: PortAllocator = PortAllocator(),
        private val simulatorFactory: ISimulatorFactory = object : ISimulatorFactory {}
) : ISimulatorsNode {
    private val appBinariesCache: MutableMap<String, File> = ConcurrentHashMap(200)
    private val simulatorsBootExecutorService: ExecutorService = Executors.newFixedThreadPool(simulatorLimit)
    private val concurrentBoot: ExecutorService = Executors.newFixedThreadPool(concurrentBoots)
    private val prepareTasks = ConcurrentHashMap<String, Future<*>>()

    override fun updateApplicationPlist(ref: DeviceRef, plistEntry: PlistEntryDTO) {
        val applicationContainer = getDeviceFor(ref).applicationContainer(plistEntry.bundleId)
        val path = File(plistEntry.file_name).toPath()
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
        logger.info(logMarker, "Ready to install app ${appBundleDto.appUrl} on device $deviceRef")
        val appBinaryPath = appBinariesCache[appBundleDto.appUrl]
            ?: throw RuntimeException("Unable to find requested binary. Deploy binary first from url ${appBundleDto.appUrl}")

        val device: ISimulator = getDeviceFor(deviceRef)
        device.installApplication(appInstaller, appBundleDto.appUrl, appBinaryPath)
    }

    override fun appInstallationStatus(deviceRef: DeviceRef): Map<String, Boolean> {
        return getDeviceFor(deviceRef).appInstallationStatus()
    }

    override fun deployApplication(appBundle: ApplicationBundle) {
        val appDirectory = if (remote.isLocalhost()) {
            appBundle.appDirectory!!
        } else {
            copyAppToRemoteHost(appBundle)
        }
        val key = appBundle.appUrl.toExternalForm()
        appBinariesCache[key] = appDirectory
    }

    override fun deleteAppData(deviceRef: DeviceRef, bundleId: String) {
        return getDeviceFor(deviceRef).dataContainer(bundleId).delete()
    }

    private fun copyAppToRemoteHost(appBundle: ApplicationBundle): File {
        val marker = MapEntriesAppendingMarker(mapOf(HOSTNAME to remote.publicHostName, "action_name" to "scp_application"))
        logger.debug(marker, "Copying application ${appBundle.appUrl} to $this")

        val remoteDirectory = File(applicationConfiguration.appBundleCacheRemotePath, UUID.randomUUID().toString()).absolutePath
        remote.exec(listOf("/bin/rm", "-rf", remoteDirectory), mapOf(), false, 90).stdOut.trim()
        remote.exec(listOf("/bin/mkdir", "-p", remoteDirectory), mapOf(), false, 90).stdOut.trim()

        val nanos = measureNanoTime {
            remote.scpToRemoteHost(appBundle.appDirectory!!.absolutePath, remoteDirectory)
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mapOf(HOSTNAME to remote.publicHostName, "action_name" to "scp_application", "duration" to seconds)

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully copied application ${appBundle.appUrl} to $this. Took $seconds seconds")
        return File(remoteDirectory, appBundle.appDirectory!!.name)
    }

    override val remoteAddress: String get() = publicHostName

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            HOSTNAME to remote.publicHostName
    ))

    override fun prepareNode() {
        logger.info(logMarker, "Preparing node ${remote.hostName}")
        hostChecker.checkPrerequisites()
        hostChecker.createDirectories()

        if (!remote.isLocalhost()) {
            hostChecker.copyWdaBundleToHost()
            if (applicationConfiguration.useTestHelperApp) {
                hostChecker.copyTestHelperBundleToHost()
            }
            hostChecker.copyVideoRecorderHelperToHost()
        }

        hostChecker.cleanup()
        hostChecker.setupHost()
        logger.info(logMarker, "Prepared node ${remote.hostName}")
    }

    private val supportedArchitectures = listOf("x86_64")

    private fun getDeviceFor(ref: DeviceRef): ISimulator {
        return createdSimulators[ref]!! //FIXME: replace with explicit unwrapping
    }

    private val createdSimulators = ConcurrentHashMap<DeviceRef, ISimulator>()
    private val allocatedPorts = HashMap<DeviceRef, DeviceAllocatedPorts>()

    override fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO {
        synchronized(this) { // FIXME: synchronize in some other place?
            if (createdSimulators.size >= simulatorLimit) {
                val message = "$this was asked for a newSimulator, but is already at capacity $simulatorLimit"
                logger.error(logMarker, message)
                throw OverCapacityException(message)
            }

            val usedUdids = createdSimulators.map { it.value.udid }.toSet()
            val fbSimctlDevice = simulatorProvider.provideSimulator(desiredCaps, usedUdids)

            if (fbSimctlDevice == null) {
                val message = "$this could not construct or match a simulator for $desiredCaps"
                logger.error(logMarker, message)
                throw RuntimeException(message)
            }

            val ref = deviceRefFromUDID(fbSimctlDevice.udid, remote.publicHostName)
            val ports = portAllocator.allocateDAP()
            allocatedPorts[ref] = ports

            val simLogMarker = MapEntriesAppendingMarker(mapOf(
                    HOSTNAME to remote.hostName,
                    UDID to fbSimctlDevice.udid,
                    DEVICE_REF to ref
            ))

            logger.debug(simLogMarker, "Will create simulator $ref")

            val simulator = simulatorFactory.newSimulator(ref, remote, fbSimctlDevice, ports, simulatorProvider.deviceSetPath,
                    wdaSimulatorBundles, concurrentBoot, desiredCaps.headless, desiredCaps.useWda, desiredCaps.useAppium)

            cancelRunningSimulatorTask(ref, "createDeviceAsync")

            prepareTasks[ref] = simulatorsBootExecutorService.submit {
                simulator.prepareAsync()
            }

            createdSimulators[ref] = simulator

            logger.debug(simLogMarker, "Created simulator $ref")

            return simulatorToDTO(simulator)
        }
    }

    override fun resetMedia(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).media.reset()
    }

    override fun listMedia(deviceRef: DeviceRef) : List<String> {
        return getDeviceFor(deviceRef).media.list()
    }

    override fun listPhotoData(deviceRef: DeviceRef) : List<String> {
        return getDeviceFor(deviceRef).media.listPhotoData()
    }

    override fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray) {
        getDeviceFor(deviceRef).media.addMedia(File(fileName), data)
    }

    override fun syslog(deviceRef: DeviceRef) : File {
        return getDeviceFor(deviceRef).osLog.osLogFile
    }

    override fun deviceAgentLog(deviceRef: DeviceRef): File {
        return getDeviceFor(deviceRef).deviceAgentLog
    }

    override fun deviceAgentLogDelete(deviceRef: DeviceRef) {
        val logFile = getDeviceFor(deviceRef).deviceAgentLog
        Files.write(logFile.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING);
    }

    override fun syslogStart(deviceRef: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions) {
        getDeviceFor(deviceRef).osLog.startWritingLog(sysLogCaptureOptions)
    }

    override fun syslogStop(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).osLog.stopWritingLog()
    }

    override fun syslogDelete(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).osLog.deleteLogFiles()
    }

    override fun getDiagnostic(deviceRef: DeviceRef, type: DiagnosticType, query: DiagnosticQuery): Diagnostic {
        return when (type) {
            DiagnosticType.SystemLog -> Diagnostic(
                type = type,
                content = getDeviceFor(deviceRef).systemLog.content()
            )
            DiagnosticType.OsLog -> Diagnostic(
                type = type,
                content = getDeviceFor(deviceRef).osLog.content(query.process)
            )
        }
    }

    override fun resetDiagnostic(deviceRef: DeviceRef, type: DiagnosticType) {
        when (type) {
            DiagnosticType.SystemLog -> getDeviceFor(deviceRef).systemLog.truncate()
            DiagnosticType.OsLog -> getDeviceFor(deviceRef).osLog.truncate()
        }
    }

    private fun simulatorToDTO(device: ISimulator): DeviceDTO {
        with(device) {
            return DeviceDTO(
                ref,
                deviceState,
                fbsimctlEndpoint,
                wdaEndpoint,
                calabashPort,
                calabashEndpoint,
                mjpegServerPort,
                appiumPort,
                appiumEndpoint,
                device.deviceInfo,
                device.lastException?.toDto(),
                capabilities = ActualCapabilities(
                    setLocation = true,
                    terminateApp = true,
                    videoCapture = true
                )
            )
        }
    }

    override fun sendPushNotification(deviceRef: DeviceRef, bundleId: String, notificationContent: ByteArray) {
        getDeviceFor(deviceRef).sendPushNotification(bundleId, notificationContent)
    }

    override fun sendPasteboard(deviceRef: DeviceRef, payload: ByteArray) {
        getDeviceFor(deviceRef).sendPasteboard(payload)
    }

    override fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto) {
        getDeviceFor(deviceRef).setPermissions(appPermissions.bundleId, appPermissions.permissions)
    }

    override fun capacityRemaining(desiredCaps: DesiredCapabilities): Float {
        return (simulatorLimit - createdSimulators.size) * 1F / simulatorLimit
    }

    override fun clearSafariCookies(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).clearSafariCookies()
    }

    override fun shake(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).shake()
    }

    override fun dispose() {
        logger.info(logMarker, "Finalising simulator pool for ${remote.hostName}")

        val disposeJobs = createdSimulators.map {
            launch(context = simulatorsThreadPool) {
                try {
                    val simulator = it.value
                    cancelRunningSimulatorTask(simulator.ref, "dispose")
                    simulator.release("Finalising pool for ${remote.hostName}")
                } catch (e: Throwable) {
                    logger.error(logMarker, "While releasing '${it.key}' for ${remote.hostName}: $e")
                }
            }
        }

        runBlocking {
            disposeJobs.forEach { it.join() }
        }

        hostChecker.killDiskCleanupThread()

        logger.info(logMarker, "Finalised simulator pool for ${remote.hostName}")
    }

    override fun endpointFor(deviceRef: DeviceRef, port: Int): URL {
        return getDeviceFor(deviceRef).endpointFor(port)
    }

    override fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO {
        return simulatorToDTO(getDeviceFor(deviceRef))
    }

    override fun lastCrashLog(deviceRef: DeviceRef): CrashLog {
        return getDeviceFor(deviceRef).lastCrashLog()
    }

    override fun listApps(deviceRef: DeviceRef): List<FBSimctlAppInfo> = getDeviceFor(deviceRef).listApps()

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
        deviceRef: DeviceRef,
        speed: Int,
        distance: Int,
        interval: Int,
        waypoints: List<LocationDto>
    ) {
        getDeviceFor(deviceRef).locationManager.startLocationSequence(speed, distance, interval, waypoints)
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

    override fun list(): List<DeviceDTO> {
        return createdSimulators.map { simulatorToDTO(it.value) }
    }

    override fun isReachable(): Boolean = remote.isReachable()

    override fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean {
        val iSimulator = createdSimulators[deviceRef] ?: return false

        cancelRunningSimulatorTask(deviceRef, "deleteRelease")

        iSimulator.release("deleteRelease $reason $deviceRef")

        createdSimulators.remove(deviceRef)
        val entries = allocatedPorts[deviceRef] ?: return true
        portAllocator.deallocateDAP(entries)

        return true
    }

    private fun cancelRunningSimulatorTask(deviceRef: DeviceRef, reason: String) {
        prepareTasks[deviceRef]?.let { oldPrepareTask ->
            if (!oldPrepareTask.isDone) {
                logger.error(logMarker, "Cancelling async task for Simulator $deviceRef while performing $reason")
                oldPrepareTask.cancel(true)
            }
        }
    }

    override fun resetAsync(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).resetAsync().let { resetProc ->
            cancelRunningSimulatorTask(deviceRef, "resetAsync")
            prepareTasks[deviceRef] = simulatorsBootExecutorService.submit(resetProc)
        }
    }

    override fun state(deviceRef: DeviceRef): SimulatorStatusDTO {
        return getDeviceFor(deviceRef).status()
    }

    override fun supports(desiredCaps: DesiredCapabilities): Boolean {
        return desiredCaps.arch == null || supportedArchitectures.contains(desiredCaps.arch)
    }

    override fun totalCapacity(desiredCaps: DesiredCapabilities): Int {
        return if (supports(desiredCaps)) simulatorLimit else 0
    }

    override fun videoRecordingDelete(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.delete()
    }

    override fun videoRecordingGet(deviceRef: DeviceRef): ByteArray {
        return getDeviceFor(deviceRef).videoRecorder.getRecording()
    }

    override fun videoRecordingStart(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.start()
    }

    override fun videoRecordingStop(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).videoRecorder.stop()
    }

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

    override fun openUrl(deviceRef: DeviceRef, url: String) {
        getDeviceFor(deviceRef).openUrl(url)
    }

    override fun uninstallApplication(deviceRef: DeviceRef, bundleId: String) {
        getDeviceFor(deviceRef).uninstallApplication(bundleId, appInstaller)
    }

    override fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>) {
        getDeviceFor(deviceRef).setEnvironmentVariables(envs)
    }

    override fun getEnvironmentVariable(deviceRef: DeviceRef, variableName: String): String {
        return getDeviceFor(deviceRef).getEnvironmentVariable(variableName)
    }

    override fun toString(): String {
        return "${javaClass.simpleName} at $remoteAddress"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimulatorsNode

        if (publicHostName != other.publicHostName) return false

        return true
    }

    override fun hashCode(): Int {
        return publicHostName.hashCode()
    }
}
