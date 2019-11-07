package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.XcodeVersion
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.ios.device.*
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import com.badoo.automation.deviceserver.util.AppInstaller
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class DevicesNode(
    private val remote: IRemote,
    override val publicHostName: String,
    portAllocator: PortAllocator = PortAllocator(),
    wdaRunnerXctest: File,
    knownDevices: List<KnownDevice>,
    private val whitelistedApps: Set<String>,
    private val uninstallApps: Boolean,
    private val wdaBundlePath: File,
    private val remoteWdaBundleRoot: File,
    private val fbsimctlVersion: String
) : ISimulatorsNode {
    override fun appInstallProgress(deviceRef: DeviceRef): String {
        val device = slotByExternalRef(deviceRef).device
        val udid = device.udid
        return appInstaller.installProgress(udid)
    }

    private val apps: MutableMap<String, ApplicationBundle> = ConcurrentHashMap(200)
    private val appBinaries: MutableMap<String, File> = ConcurrentHashMap(200)
    private val appInstaller: AppInstaller = AppInstaller(Executors.newFixedThreadPool(3), remote)

    override fun installApplicationAsync(deviceRef: DeviceRef, appBundle: ApplicationBundle) {
        val device = slotByExternalRef(deviceRef).device
        val udid = device.udid
        // TODO: add date of syncing for future deleting cleanup
        if (!apps.contains(appBundle.appUrl)) { // FIXME: check if app is deleted in one hour
            appBinaries[appBundle.appUrl] = appBundle.appFile
            apps[appBundle.appUrl] = appBundle
        }

        appInstaller.installApplicationAsync(udid, appBundle, appBinaries[appBundle.appUrl]!!)
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private val isWebDriverAgentDeployed = remote.execIgnoringErrors(listOf("test", "-d", remoteWdaBundleRoot.absolutePath)).isSuccess

    override val isNodePrepared: Boolean
        get() = remote.isLocalhost() || isWebDriverAgentDeployed

    private val deviceRegistrationInterval = Duration.ofMinutes(1)

    override fun resetMedia(deviceRef: DeviceRef) {
        throw(NotImplementedError("Resetting media is not supported by physical devices"))
    }

    override fun listMedia(deviceRef: DeviceRef) : String {
        throw(NotImplementedError("Listing media is not supported by physical devices"))
    }

    override fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray) {
        throw(NotImplementedError("Adding media is not supported by physical devices"))
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

    override val remoteAddress: String get() = remote.hostName

    private val deviceInfoProvider = DeviceInfoProvider(remote)
    private val slots: DeviceSlots =
        DeviceSlots(remote, wdaRunnerXctest, portAllocator, deviceInfoProvider, knownDevices)

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

    override fun approveAccess(deviceRef: DeviceRef, bundleId: String) {
        throw(NotImplementedError("Approve Access is not supported by physical devices"))
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

        return SimulatorStatusDTO(
            ready = status.ready,
            wda_status = status.wda_status,
            fbsimctl_status = status.fbsimctl_status,
            state = status.state,
            last_error = if (status.last_error == null) null else exceptionToDto(status.last_error)
        )
    }

    override fun isReachable(): Boolean {
        return remote.isReachable()
    }

    override fun count(): Int {
        // FIXME: Remove from common interface, it might be needed for simulators node only
        throw NotImplementedError("An operation is not implemented")
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
        return deviceToDto(deviceRef, device)
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
        var slot: DeviceSlot? = null
        var ref: DeviceRef? = null

        synchronized(this) {
            slot = slots.reserve(desiredCaps)
            ref = newRef(slot!!.udid)

            activeRefs[ref!!] = slot!!.udid
        }

        slot!!.device.renewAsync(whitelistedApps = whitelistedApps, uninstallApps = uninstallApps)

        return deviceToDto(ref!!, device = slot!!.device)
    }

    override fun prepareNode() {
        logger.info(logMarker, "Preparing node ${remote.hostName}")
        checkPrerequisites()
        if (!remote.isLocalhost()) {
            copyWdaBundleToHost()
        }
        cleanup()

        // FIXME: We need to completely reset node state here due to changes in NodeWrapper logic

        slots.registerDevices()

        periodicTasksPool.scheduleWithFixedDelay(
            {
                try {
                    slots.registerDevices()
                } catch (e: Exception) {
                    logger.warn(logMarker, "Failed to register devices: $e")
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

    override fun list(): List<DeviceDTO> {
        synchronized(this) {
            val disconnected = mutableListOf<DeviceRef>()

            val list = activeRefs.map { (ref, udid) ->
                val slot = slots.tryGetSlot(udid = udid)
                if (slot == null) {
                    disconnected.add(ref)
                    return@map null
                } else {
                    return@map deviceToDto(ref, slot.device)
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
        return device.lastCrashLog() ?: CrashLog("", "")
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
    // endregion

    override fun uninstallApplication(deviceRef: DeviceRef, bundleId: String) {
        val device = slotByExternalRef(deviceRef).device
        device.uninstallApplication(bundleId)
    }

    private fun deviceToDto(deviceRef: DeviceRef, device: Device): DeviceDTO {
        return DeviceDTO(
            ref = deviceRef,
            state = device.deviceState,
            fbsimctl_endpoint = device.fbsimctlEndpoint,
            wda_endpoint = device.wdaEndpoint,
            calabash_port = device.calabashPort,
            mjpeg_server_port = device.mjpegServerPort,
            user_ports = emptySet(),
            info = device.deviceInfo,
            last_error = device.lastException?.toDto(),
            capabilities = ActualCapabilities(
                setLocation = false,
                terminateApp = false,
                videoCapture = true
            )
        )
    }

    private fun newRef(udid: UDID): DeviceRef {
        val unsafe = Regex("[^\\-_a-zA-Z\\d]") // TODO: Replace with UUID 4
        return "$udid-${remote.publicHostName}".replace(unsafe, "-")
    }

    private fun slotByExternalRef(deviceRef: DeviceRef): DeviceSlot {
        val udid = activeRefs[deviceRef] ?: throw(DeviceNotFoundException("Device $deviceRef not found"))

        return slots.getSlot(udid)
    }

    private fun checkPrerequisites() {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

        if (xcodeVersion < XcodeVersion(9, 2)) {
            throw RuntimeException("Expecting Xcode 9.2 or higher, but it is $xcodeVersion")
        }

        // temp solution, prerequisites should be satisfied without having to switch anything
        val switchRes = remote.execIgnoringErrors(
            listOf("/usr/local/bin/brew", "switch", "fbsimctl", fbsimctlVersion),
            env = mapOf("RUBYOPT" to "")
        )

        if (!switchRes.isSuccess) {
            logger.warn(logMarker, "fbsimctl switch failed, see: $switchRes")
        }

        val fbsimctlPath = remote.execIgnoringErrors(listOf("readlink", FBSimctl.FBSIMCTL_BIN)).stdOut

        val match = Regex("/fbsimctl/([-.\\w]+)/bin/fbsimctl").find(fbsimctlPath)
                ?: throw RuntimeException("Could not read fbsimctl version from $fbsimctlPath")
        val actualFbsimctlVersion = match.groupValues[1]
        if (actualFbsimctlVersion != fbsimctlVersion) {
            throw RuntimeException("Expecting fbsimctl $fbsimctlVersion, but it was $actualFbsimctlVersion ${match.groupValues}")
        }

        val iproxy = remote.execIgnoringErrors((listOf(UsbProxy.IPROXY_BIN)))
        if (iproxy.exitCode != 0) {
           throw RuntimeException("Expecting iproxy to be installed")
        }

        val socat = remote.execIgnoringErrors((listOf(UsbProxy.SOCAT_BIN, "-V")))
        if (socat.exitCode != 0) {
            throw RuntimeException("Expecting socat to be installed")
        }
    }

    private fun copyWdaBundleToHost() {
        logger.debug(logMarker, "Setting up remote node: copying WebDriverAgent to node ${remote.hostName}")
        remote.rm(remoteWdaBundleRoot.absolutePath)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteWdaBundleRoot.absolutePath))
        remote.scp(wdaBundlePath.absolutePath, remoteWdaBundleRoot.absolutePath)
    }

    private fun cleanup() {
        // single instance of server on node is implied, so we can kill all simulators and fbsimctl processes
        remote.execIgnoringErrors(listOf("pkill", "-9", "/usr/local/bin/fbsimctl"))
    }

    override fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>) {
        throw(NotImplementedError("Setting environment variables is not supported by physical devices"))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DevicesNode

        if (publicHostName != other.publicHostName) return false

        return true
    }

    override fun hashCode(): Int {
        return publicHostName.hashCode()
    }
}
