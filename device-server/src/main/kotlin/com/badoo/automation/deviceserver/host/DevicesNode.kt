package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.XcodeVersion
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.ios.device.*
import com.badoo.automation.deviceserver.ios.device.diagnostic.RealDeviceSysLog
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.WdaDeviceBundle
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.system.measureNanoTime

class DevicesNode(
    private val remote: IRemote,
    override val publicHostName: String,
    portAllocator: PortAllocator = PortAllocator(),
    configuredDevices: Set<ConfiguredDevice>,
    private val whitelistedApps: Set<String>,
    private val uninstallApps: Boolean,
    private val wdaDeviceBundles: List<WdaDeviceBundle>,
    private val fbsimctlVersion: String,
    private val appInstallerExecutorService: ExecutorService = Executors.newFixedThreadPool(4)
) : ISimulatorsNode {
    override fun updateApplicationPlist(ref: DeviceRef, plistEntry: PlistEntryDTO) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private val appBinariesCache: MutableMap<String, File> = ConcurrentHashMap(200)

    override fun deployApplication(appBundle: ApplicationBundle) {
        val appDirectory = if (remote.isLocalhost()) {
            appBundle.appDirectory!!
        } else {
            copyAppToRemoteHost(appBundle)
        }
        val key = appBundle.appUrl.toExternalForm()
        appBinariesCache[key] = appDirectory
    }

    private fun copyAppToRemoteHost(appBundle: ApplicationBundle): File {
        val marker = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to remote.publicHostName, "action_name" to "scp_application"))
        logger.debug(marker, "Copying application ${appBundle.appUrl} to $this")

        remote.exec(listOf("/bin/rm", "-rf", ApplicationConfiguration().appBundleCacheRemotePath.absolutePath), mapOf(), false, 90).stdOut.trim()

        val remoteDirectory = File(ApplicationConfiguration().appBundleCacheRemotePath, UUID.randomUUID().toString()).absolutePath
        remote.exec(listOf("/bin/mkdir", "-p", remoteDirectory), mapOf(), false, 90).stdOut.trim()

        val nanos = measureNanoTime {
            remote.scpToRemoteHost(appBundle.appDirectory!!.absolutePath, remoteDirectory)
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mapOf(LogMarkers.HOSTNAME to remote.publicHostName, "action_name" to "scp_application", "duration" to seconds)

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully copied application ${appBundle.appUrl} to $this. Took $seconds seconds")
        return File(remoteDirectory, appBundle.appDirectory!!.name)
    }

    override fun installApplication(deviceRef: DeviceRef, appBundleDto: AppBundleDto) {
        logger.info(logMarker, "Ready to install app ${appBundleDto.appUrl} on device $deviceRef")
        var appBinaryPath: File = appBinariesCache[appBundleDto.appUrl]
            ?: throw RuntimeException("Unable to find requested binary. Deploy binary first from url ${appBundleDto.appUrl}")

        if (appBinaryPath.absolutePath.endsWith("/Payload")) {
            appBinaryPath = File("${appBinaryPath.parentFile.absolutePath}.ipa")
        }

        val device = slotByExternalRef(deviceRef).device
        val udid = device.udid
        val installTask = appInstallerExecutorService.submit(Callable<Boolean> {
            return@Callable performInstall(logMarker, udid, appBinaryPath, appBundleDto.appUrl)
        })

        installTask.get()
    }

    private fun performInstall(logMarker: Marker, udid: UDID, appBinaryPath: File, appUrl: String): Boolean {
        logger.debug(logMarker, "Installing application $appUrl on device $udid")

        val nanos = measureNanoTime {
            logger.debug(logMarker, "Will install application $appUrl on device $udid using fbsimctl install ${appBinaryPath.absolutePath}")
            try {
                remote.fbsimctl.installApp(udid, appBinaryPath)
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error happened while installing the app $appUrl on $udid", e)
                return false
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application",
            "duration" to seconds
        )
        measurement.putAll(logMarkerDetails(udid))
        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed application $appUrl on device $udid. Took $seconds seconds")
        return true
    }

    private val commonLogMarkerDetails = mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    )

    private fun logMarkerDetails(udid: UDID): Map<String, String> {
        return commonLogMarkerDetails + mapOf(
            LogMarkers.DEVICE_REF to deviceRefFromUDID(
                udid,
                remote.publicHostName
            ), LogMarkers.UDID to udid
        )
    }

    private val deviceRegistrationInterval = Duration.ofMinutes(1)

    override fun resetMedia(deviceRef: DeviceRef) {
        throw(NotImplementedError("Resetting media is not supported by physical devices"))
    }

    override fun listMedia(deviceRef: DeviceRef) : List<String> {
        throw(NotImplementedError("Listing media is not supported by physical devices"))
    }

    override fun listPhotoData(deviceRef: DeviceRef) : List<String> {
        throw(NotImplementedError("Listing PhotoData is not supported by physical devices"))
    }

    override fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray) {
        throw(NotImplementedError("Adding media is not supported by physical devices"))
    }

    override fun deviceAgentLog(deviceRef: DeviceRef): File {
        return slotByExternalRef(deviceRef).device.deviceAgentLog
    }

    override fun deviceAgentLogDelete(deviceRef: DeviceRef) {
        val logFile = slotByExternalRef(deviceRef).device.deviceAgentLog
        Files.write(logFile.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING);
    }

    override fun appiumServerLog(deviceRef: DeviceRef): File {
        throw(NotImplementedError("AppiumServer log is not supported by physical devices"))
    }

    override fun appiumServerLogDelete(deviceRef: DeviceRef) {
        throw(NotImplementedError("AppiumServer log is not supported by physical devices"))
    }

    override fun syslog(deviceRef: DeviceRef): File {
        val device = slotByExternalRef(deviceRef).device
        val osLog: RealDeviceSysLog = device.osLog
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun syslogStart(deviceRef: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions) {
        val device: Device = slotByExternalRef(deviceRef).device
        device.osLog.startWritingLog(sysLogCaptureOptions)
    }

    override fun syslogStop(deviceRef: DeviceRef) {
        val device = slotByExternalRef(deviceRef).device
        device.osLog.stopWritingLog()
    }

    override fun syslogDelete(deviceRef: DeviceRef) {
        val device = slotByExternalRef(deviceRef).device
        device.osLog.deleteLogFiles()
    }

    override fun getDiagnostic(deviceRef: DeviceRef, type: DiagnosticType, query: DiagnosticQuery): Diagnostic {
        throw(NotImplementedError("Diagnostic is not supported by physical devices"))
    }

    override fun resetDiagnostic(deviceRef: DeviceRef, type: DiagnosticType) {
        throw(NotImplementedError("Diagnostic is not supported by physical devices"))
    }

    override fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String) {
        throw(NotImplementedError("Push files is not supported by physical devices"))
    }

    override fun pushFile(ref: DeviceRef, data: ByteArray, path: Path) {
        throw(NotImplementedError("Push files is not supported by physical devices"))
    }

    override fun deleteFile(ref: DeviceRef, path: Path) {
        throw(NotImplementedError("Delete file is not supported by physical devices"))
    }

    override val remoteAddress: String get() = remote.hostName

    private val deviceInfoProvider = DeviceInfoProvider(remote)
    private val slots: DeviceSlots = DeviceSlots(remote, wdaDeviceBundles, portAllocator, deviceInfoProvider, configuredDevices)

    private var deviceRegistrar: Future<Unit>? = null

    private val activeRefs = ConcurrentHashMap<DeviceRef, UDID>()

    private val supportedArchitectures = listOf("arm64")

    override fun toString(): String {
        return "<${this.javaClass.simpleName}: $remote>"
    }

    override fun supports(desiredCaps: DesiredCapabilities): Boolean {
        return desiredCaps.arch == null || supportedArchitectures.contains(desiredCaps.arch)
    }

    override fun resetAsync(deviceRef: DeviceRef) {
        throw(NotImplementedError("Reset is not supported by physical devices"))
    }

    override fun sendPushNotification(deviceRef: DeviceRef, bundleId: String, notificationContent: ByteArray) {
        throw(NotImplementedError("Simulating push notifications is not supported by physical devices"))
    }

    override fun sendPasteboard(deviceRef: DeviceRef, payload: ByteArray) {
        throw(NotImplementedError("Set pasteboard is not supported by physical devices"))
    }

    override fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto) {
        throw(NotImplementedError("Set Permissions is not supported by physical devices"))
    }

    override fun clearSafariCookies(deviceRef: DeviceRef) {
        throw(NotImplementedError("Clear Safari Cookies is not supported by physical devices"))
    }

    override fun shake(deviceRef: DeviceRef) {
        throw(NotImplementedError("Shake gesture is not supported by physical devices"))
    }

    override fun openUrl(deviceRef: DeviceRef, url: String) {
        throw(NotImplementedError("Opening URL is not supported by physical devices"))
    }

    override fun endpointFor(deviceRef: DeviceRef, port: Int): URL {
        val device = slotByExternalRef(deviceRef).device
        return device.endpointFor(port)
    }

    private fun exceptionToDto(exception: Exception): ExceptionDTO {
        return ExceptionDTO(
            type = exception.javaClass.name,
            message = exception.message ?: "",
            stackTrace = exception.stackTrace.map { it.toString() }
        )
    }

    override fun state(deviceRef: DeviceRef): SimulatorStatusDTO {
        val device = slotByExternalRef(deviceRef).device
        val status = device.status()

        return status
//        return SimulatorStatusDTO(
//            ready = status.ready,
//            wda_status = status.wda_status,
//            appium_status = status.appium_status,
//            fbsimctl_status = status.fbsimctl_status,
//            state = status.state,
//            last_error = status.last_error
//        )
    }

    override fun isReachable(): Boolean {
        return remote.isReachable()
    }

    override fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean {
        synchronized(this) {
            slotByExternalRef(deviceRef).release()
            activeRefs.remove(deviceRef)

            return true
        }
    }

    override fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO {
        val device = slotByExternalRef(deviceRef).device
        return deviceToDto(device)
    }

    override fun totalCapacity(desiredCaps: DesiredCapabilities): Int {
        if (!supports(desiredCaps)) {
            return 0
        }

        synchronized(this) {
            return slots.totalCapacity(desiredCaps)
        }
    }

    override fun capacityRemaining(desiredCaps: DesiredCapabilities): Float {
        if (!supports(desiredCaps)) {
            return 0F
        }

        synchronized(this) {
            return slots.countUnusedSlots(desiredCaps).toFloat()
        }
    }

    override fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO {
        lateinit var slot: DeviceSlot

        synchronized(this) {
            slot = slots.reserve(desiredCaps)
            activeRefs[slot.device.ref] = slot.device.udid
        }

        slot.device.renewAsync(whitelistedApps = whitelistedApps, uninstallApps = uninstallApps)
        return deviceToDto(slot.device)
    }

    override fun prepareNode() {
        logger.info(logMarker, "Preparing node ${remote.hostName}")
        checkPrerequisites()
        if (!remote.isLocalhost()) {
            copyWdaBundlesToHost()
        }
        cleanup()

        // FIXME: We need to completely reset node state here due to changes in NodeWrapper logic

        slots.registerDevices()

        periodicTasksPool.scheduleWithFixedDelay(
            {
                try {
                    slots.registerDevices()
                } catch (e: Exception) {
                    logger.error(logMarker, "Failed to register devices: $e")
                }
            },
            deviceRegistrationInterval.toMillis(),
            deviceRegistrationInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )

        logger.info(logMarker, "Prepared node ${remote.hostName}")
    }

    override fun dispose() {
        logger.info(logMarker, "Finalizing node $this")
        deviceRegistrar?.cancel(true)
        try {
            slots.dispose()
        } catch (e: Error) {
            logger.error(logMarker, e.message)
        }

        logger.info(logMarker, "Finalized node $this")
    }

    override fun reboot(): Boolean {
        return true // Not intended to reboot Real Device nodes
    }

    override fun list(): List<DeviceDTO> {
        synchronized(this) {
            val disconnected = mutableListOf<DeviceRef>()

            val list = activeRefs.map { (ref, udid) ->
                val slot = slots.tryGetSlot(udid = udid)
                if (slot == null) {
                    disconnected.add(ref)
                    return@map null
                } else {
                    return@map deviceToDto(slot.device)
                }
            }

            // TODO: replace with event driven removal of active refs on device disconnect
            disconnected.forEach { ref -> activeRefs.remove(ref) }

            return list.filterNotNull().toList()
        }
    }

    // region diagnostics

    override fun lastCrashLog(deviceRef: DeviceRef): CrashLog {
        val device = slotByExternalRef(deviceRef).device
        return device.lastCrashLog()
    }

    override fun listApps(deviceRef: DeviceRef): List<FBSimctlAppInfo> = slotByExternalRef(deviceRef).device.listApps()

    override fun locationListScenarios(deviceRef: DeviceRef): List<String> {
        throw(NotImplementedError("Location commands are not supported by physical devices"))
    }

    override fun locationClear(deviceRef: DeviceRef) {
        throw(NotImplementedError("Location commands are not supported by physical devices"))
    }

    override fun locationSet(deviceRef: DeviceRef, latitude: Double, longitude: Double) {
        throw(NotImplementedError("Location commands are not supported by physical devices"))
    }

    override fun locationRunScenario(deviceRef: DeviceRef, scenarioName: String) {
        throw(NotImplementedError("Location commands are not supported by physical devices"))
    }

    override fun locationStartLocationSequence(
        deviceRef: DeviceRef,
        speed: Int,
        distance: Int,
        interval: Int,
        waypoints: List<LocationDto>
    ) {
        throw(NotImplementedError("Location commands are not supported by physical devices"))
    }

    override fun crashLogs(deviceRef: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        throw NotImplementedError()
    }

    override fun crashLogs(deviceRef: DeviceRef, appName: String?): List<CrashLog> {
        val device = slotByExternalRef(deviceRef).device
        return device.crashLogs(appName)
    }

    override fun deleteCrashLogs(deviceRef: DeviceRef): Boolean {
        return false // crash logs are not supported on devices yet
    }

    override fun videoRecordingDelete(deviceRef: DeviceRef) {
        slotByExternalRef(deviceRef).device.videoRecorder.delete()
    }

    override fun videoRecordingGet(deviceRef: DeviceRef): ByteArray {
        return slotByExternalRef(deviceRef).device.videoRecorder.getRecording()
    }

    override fun videoRecordingStart(deviceRef: DeviceRef) {
        slotByExternalRef(deviceRef).device.videoRecorder.start()
    }

    override fun videoRecordingStop(deviceRef: DeviceRef) {
        slotByExternalRef(deviceRef).device.videoRecorder.stop()
    }

    override fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String> = throw(NotImplementedError())

    override fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray = throw(NotImplementedError())

    override fun pullFile(deviceRef: DeviceRef, path: Path): ByteArray = throw(NotImplementedError())

    // endregion

    private val appInstaller: AppInstaller = AppInstaller(remote)

    override fun uninstallApplication(deviceRef: DeviceRef, bundleId: String) {
        val device = slotByExternalRef(deviceRef).device
        device.uninstallApplication(bundleId, appInstaller)
    }

    override fun deleteAppData(deviceRef: DeviceRef, bundleId: String) = throw(NotImplementedError())

    private fun deviceToDto(device: Device): DeviceDTO {
        return DeviceDTO(
            ref = device.ref,
            state = device.deviceState,
            fbsimctl_endpoint = device.fbsimctlEndpoint,
            wda_endpoint = device.wdaEndpoint,
            calabash_port = device.calabashPort,
            calabash_endpoint = device.calabashEndpoint,
            mjpeg_server_port = device.mjpegServerPort,
            appium_port = device.appiumPort,
            appium_endpoint = device.appiumEndpoint,
            info = device.deviceInfo,
            last_error = device.lastException?.toDto(),
            capabilities = ActualCapabilities(
                setLocation = false,
                terminateApp = false,
                videoCapture = true
            )
        )
    }

    private fun slotByExternalRef(deviceRef: DeviceRef): DeviceSlot {
        val udid = activeRefs[deviceRef] ?: throw(DeviceNotFoundException("Device $deviceRef not found"))

        return slots.getSlot(udid)
    }

    private fun checkPrerequisites() {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

        if (xcodeVersion < XcodeVersion(12, 1)) {
//            throw RuntimeException("Expecting Xcode 12.1 or higher, but it is $xcodeVersion")
            logger.error(logMarker, "Expecting Xcode 12.1 or higher, but it is $xcodeVersion")
        }

        val fbsimctlPath = remote.execIgnoringErrors(listOf("readlink", remote.fbsimctl.fbsimctlBinary)).stdOut

        val match = Regex("/fbsimctl/([-.\\w]+)/bin/fbsimctl").find(fbsimctlPath)
                ?: throw RuntimeException("Could not read fbsimctl version from $fbsimctlPath")
        val actualFbsimctlVersion = match.groupValues[1]
        if (actualFbsimctlVersion != fbsimctlVersion) {
            throw RuntimeException("Expecting fbsimctl $fbsimctlVersion, but it was $actualFbsimctlVersion ${match.groupValues}")
        }

        val iproxyResult = remote.execIgnoringErrors((listOf(File(remote.homeBrewPath, "iproxy").absolutePath, "--help")))
        if (!iproxyResult.isSuccess) {
           throw RuntimeException("Expecting iproxy to be installed. Exit code: ${iproxyResult.exitCode}\nStdErr: ${iproxyResult.stdErr}. StdOut: ${iproxyResult.stdOut}")
        }

        val socatResult = remote.execIgnoringErrors((listOf(File(remote.homeBrewPath, "socat").absolutePath, "-V")))
        if (!socatResult.isSuccess) {
            throw RuntimeException("Expecting socat to be installed. Exit code: ${socatResult.exitCode}\nStdErr: ${socatResult.stdErr}. StdOut: ${socatResult.stdOut}")
        }
    }

    private fun copyWdaBundlesToHost() {
        logger.debug(logMarker, "Setting up remote node: copying WebDriverAgent to node ${remote.hostName}")
        val remoteWdaBundleRoot = wdaDeviceBundles.first().bundlePath(remote.isLocalhost()).absolutePath
        remote.rm(remoteWdaBundleRoot)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteWdaBundleRoot))
        wdaDeviceBundles.forEach {
            remote.scpToRemoteHost(it.bundlePath(true).absolutePath, remoteWdaBundleRoot)
        }
    }

    private fun cleanup() {
        // single instance of server on node is implied, so we can kill all simulators and fbsimctl processes
        remote.pkill(remote.fbsimctl.fbsimctlBinary, true)
        remote.pkill("/usl/local/bin/iproxy", true)
        remote.pkill("/opt/homebrew/bin/iproxy", true)
        remote.pkill("/usr/local/bin/socat", true)
        remote.pkill("/opt/homebrew/bin/socat", true)
        remote.pkill("appium_tmpdir_", true)
    }

    override fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>) {
        throw(NotImplementedError("Setting environment variables is not supported by physical devices"))
    }

    override fun getEnvironmentVariable(deviceRef: DeviceRef, variableName: String): String {
        throw(NotImplementedError("Getting environment variables is not supported by physical devices"))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DevicesNode

        if (publicHostName != other.publicHostName) return false

        return true
    }

    override fun appInstallationStatus(deviceRef: DeviceRef): Map<String, Boolean> {
//        return slotByExternalRef(deviceRef).device.appInstallationStatus()

        val status = mapOf<String, Boolean>(
            "task_exists" to true,
            "task_complete" to true,
            "success" to true
        )
        return status
    }
    override fun hashCode(): Int {
        return publicHostName.hashCode()
    }
}
