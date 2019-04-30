package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.LogMarkers.Companion.DEVICE_REF
import com.badoo.automation.deviceserver.LogMarkers.Companion.HOSTNAME
import com.badoo.automation.deviceserver.LogMarkers.Companion.UDID
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ISimulatorHostChecker
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.simulatorsThreadPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SimulatorsNode(
        val remote: IRemote,
        private val publicHostName: String,
        private val hostChecker: ISimulatorHostChecker,
        private val simulatorLimit: Int,
        concurrentBoots: Int,
        private val wdaRunnerXctest: File,
        private val simulatorProvider: ISimulatorProvider = SimulatorProvider(remote),
        private val portAllocator: PortAllocator = PortAllocator(),
        private val simulatorFactory: ISimulatorFactory = object : ISimulatorFactory {}
) : ISimulatorsNode {

    override val remoteAddress: String get() = publicHostName

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            HOSTNAME to remote.hostName
    ))

    override val isNodePrepared: Boolean
        get() = remote.isLocalhost() || hostChecker.isWdaBundleDeployed

    override fun prepareNode() {
        logger.info(logMarker, "Preparing node ${remote.hostName}")
        hostChecker.checkPrerequisites()

        if (!remote.isLocalhost()) {
            hostChecker.copyWdaBundleToHost()
        }

        hostChecker.cleanup()
        hostChecker.setupHost()
        logger.info(logMarker, "Prepared node ${remote.hostName}")
    }

    private val supportedArchitectures = listOf("x86_64")
    private val deviceSetPath: String by lazy { remote.fbsimctl.defaultDeviceSet() }
    private val concurrentBoot = newFixedThreadPoolContext(concurrentBoots, "sim_boot_${remote.hostName}")

    private fun getDeviceFor(ref: DeviceRef): ISimulator {
        return devicePool[ref]!! //FIXME: replace with explicit unwrapping
    }

    private val devicePool = ConcurrentHashMap<DeviceRef, ISimulator>()
    private val allocatedPorts = HashMap<DeviceRef, DeviceAllocatedPorts>()

    override fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO {
        synchronized(this) { // FIXME: synchronize in some other place?
            if (devicePool.size >= simulatorLimit) {
                val message = "$this was asked for a newSimulator, but is already at capacity $simulatorLimit"
                logger.error(logMarker, message)
                throw OverCapacityException(message)
            }

            val usedUdids = devicePool.map { it.value.udid }.toSet()
            val fbSimctlDevice = simulatorProvider.match(desiredCaps, usedUdids)
            if (fbSimctlDevice == null) {
                val message = "$this could not construct or match a simulator for $desiredCaps"
                logger.error(logMarker, message)
                throw RuntimeException(message)
            }

            val ref = newRef(fbSimctlDevice.udid)
            val ports = portAllocator.allocateDAP()
            allocatedPorts[ref] = ports

            val simLogMarker = MapEntriesAppendingMarker(mapOf(
                    HOSTNAME to remote.hostName,
                    UDID to fbSimctlDevice.udid,
                    DEVICE_REF to ref
            ))

            logger.debug(simLogMarker, "Will create simulator $ref")

            val simulator = simulatorFactory.newSimulator(ref, remote, fbSimctlDevice, ports, deviceSetPath,
                    wdaRunnerXctest, concurrentBoot, desiredCaps.headless, desiredCaps.useWda, fbSimctlDevice.toString())
            simulator.prepareAsync()
            devicePool[ref] = simulator

            logger.debug(simLogMarker, "Created simulator $ref")

            return simulatorToDTO(simulator)
        }
    }

    override fun resetMedia(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).media.reset()
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
                state,
                fbsimctlEndpoint,
                wdaEndpoint,
                calabashPort,
                device.userPorts.toSet(),
                device.info,
                device.lastError?.toDto(),
                capabilities = ActualCapabilities(
                    setLocation = true,
                    terminateApp = true,
                    videoCapture = true
                )
            )
        }
    }

    private fun newRef(udid: String): DeviceRef = "$udid-${remote.publicHostName}".replace(Regex("[^-\\w]"), "-")

    override fun approveAccess(deviceRef: DeviceRef, bundleId: String) {
        getDeviceFor(deviceRef).approveAccess(bundleId)
    }

    override fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto) {
        getDeviceFor(deviceRef).setPermissions(appPermissions.bundleId, appPermissions.permissions)
    }

    override fun capacityRemaining(desiredCaps: DesiredCapabilities): Float {
        return (simulatorLimit - count()) * 1F / simulatorLimit
    }

    override fun clearSafariCookies(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).clearSafariCookies()
    }

    override fun shake(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).shake()
    }

    override fun count(): Int = devicePool.size

    override fun dispose() {
        logger.info(logMarker, "Finalising simulator pool for ${remote.hostName}")

        val disposeJobs = devicePool.map {
            launch(context = simulatorsThreadPool) {
                try {
                    it.value.release("Finalising pool for ${remote.hostName}")
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

    override fun crashLogs(deviceRef: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        return getDeviceFor(deviceRef).crashLogs(pastMinutes)
    }

    override fun deleteCrashLogs(deviceRef: DeviceRef): Boolean {
        return getDeviceFor(deviceRef).deleteCrashLogs()
    }

    override fun list(): List<DeviceDTO> {
        return devicePool.map { simulatorToDTO(it.value) }
    }

    override fun isReachable(): Boolean = remote.isReachable()

    override fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean {
        val iSimulator = devicePool[deviceRef] ?: return false
        iSimulator.release("deleteRelease $reason $deviceRef")
        devicePool.remove(deviceRef)
        val entries = allocatedPorts[deviceRef] ?: return true
        portAllocator.deallocateDAP(entries)
        return true
    }

    override fun resetAsync(deviceRef: DeviceRef) {
        getDeviceFor(deviceRef).resetAsync()
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

    override fun uninstallApplication(deviceRef: DeviceRef, bundleId: String) {
        getDeviceFor(deviceRef).uninstallApplication(bundleId)
    }

    override fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>) {
        getDeviceFor(deviceRef).setEnvironmentVariables(envs)
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
