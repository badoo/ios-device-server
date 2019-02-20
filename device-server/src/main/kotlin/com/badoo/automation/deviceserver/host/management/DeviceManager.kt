package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.host.management.util.AutoreleaseLooper
import com.badoo.automation.deviceserver.ios.ActiveDevices
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Duration

private val INFINITE_DEVICE_TIMEOUT: Duration = Duration.ofSeconds(Integer.MAX_VALUE.toLong())

class DeviceManager(
        config: DeviceServerConfig,
        nodeFactory: IHostFactory,
        activeDevices: ActiveDevices = ActiveDevices(),
        private val autoreleaseLooper: IAutoreleaseLooper = AutoreleaseLooper()
) : IDeviceManager {
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

    fun startAutoRegisteringDevices() {
        autoRegistrar.startAutoRegistering()
    }

    fun launchAutoReleaseLoop() {
        autoreleaseLooper.autoreleaseLoop(this)
    }

    override fun getStatus(): Map<String, Any> {
        return mapOf(
            "initialized" to nodeRegistry.getInitialRegistrationComplete(),
            "sessions" to listOf(nodeRegistry.activeDevices.getStatus()).toString()
        )
    }

    override fun readyForRelease(): List<DeviceRef> {
        return nodeRegistry.activeDevices.readyForRelease()
    }

    override fun nextReleaseAtSeconds(): Long {
        return nodeRegistry.activeDevices.nextReleaseAtSeconds()
    }

    override fun getTotalCapacity(desiredCaps: DesiredCapabilities): Map<String, Int> {
        return nodeRegistry.capacitiesTotal(desiredCaps)
    }

    override fun getGetDeviceDTO(ref: DeviceRef): DeviceDTO {
        return nodeRegistry.activeDevices.getNodeFor(ref).getDeviceDTO(ref)
    }

    override fun clearSafariCookies(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).clearSafariCookies(ref)
    }

    override fun resetAsyncDevice(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetAsync(ref)
    }

    override fun  approveAccess(ref: DeviceRef, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).approveAccess(ref, bundleId)
    }

    override fun setPermissions(ref: DeviceRef, permissions: AppPermissionsDto) {
        nodeRegistry.activeDevices.getNodeFor(ref).setPermissions(ref, permissions)
    }

    override fun getEndpointFor(ref: DeviceRef, port: Int): URL {
        return nodeRegistry.activeDevices.getNodeFor(ref).endpointFor(ref, port)
    }

    override fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<CrashLog> {
        return nodeRegistry.activeDevices.getNodeFor(ref).crashLogs(ref, pastMinutes)
    }

    override fun deleteCrashLogs(ref: DeviceRef): Boolean {
        return nodeRegistry.activeDevices.getNodeFor(ref).deleteCrashLogs(ref)
    }

    override fun getLastCrashLog(ref: DeviceRef): CrashLog {
        return nodeRegistry.activeDevices.getNodeFor(ref).lastCrashLog(ref)
    }

    override fun shake(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).shake(ref)
    }

    override fun startVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingStart(ref)
    }

    override fun stopVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingStop(ref)
    }

    override fun getVideo(ref: DeviceRef): ByteArray {
        return nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingGet(ref)
    }

    override fun deleteVideo(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).videoRecordingDelete(ref)
    }

    override fun uninstallApplication(ref: DeviceRef, bundleId: String) {
        nodeRegistry.activeDevices.getNodeFor(ref).uninstallApplication(ref, bundleId)
    }

    override fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO {
        return nodeRegistry.activeDevices.getNodeFor(ref).state(ref)
    }

    override fun createDeviceAsync(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO {
        try {
            return nodeRegistry.createDeviceAsync(desiredCaps, deviceTimeoutInSecs, userId)
        } catch(e: NoNodesRegisteredException) {
            val erredNodes = autoRegistrar.nodeWrappers.filter { n -> n.lastError != null }
            val errors = erredNodes.joinToString { n -> "${n.node.remoteAddress} -> ${n.lastError?.localizedMessage}" }
            throw(NoNodesRegisteredException(e.message+"\n$errors"))
        }
    }

    override fun deleteReleaseDevice(ref: DeviceRef, reason: String) {
        try { // using try-catch here not to expose tryGetNodeFor
            nodeRegistry.activeDevices.releaseDevice(ref, reason)
        } catch (e: DeviceNotFoundException) {
            logger.warn("Skipping $ref release because no node knows about it")
            return
        }
    }

    override fun getDeviceRefs() : List<DeviceDTO> {
        return nodeRegistry.activeDevices.deviceList()
    }

    override fun releaseUserDevices(userId: String, reason: String) {
        val devices = nodeRegistry.activeDevices.getUserDeviceRefs(userId)
        nodeRegistry.activeDevices.releaseDevices(devices, reason)
    }

    override fun isReady(): Boolean {
        return nodeRegistry.getInitialRegistrationComplete()
    }

    override fun listFiles(ref: DeviceRef, dataPath: DataPath): List<String> {
        return nodeRegistry.activeDevices.getNodeFor(ref).listFiles(ref, dataPath)
    }

    override fun pullFile(ref: DeviceRef, dataPath: DataPath): ByteArray {
        return nodeRegistry.activeDevices.getNodeFor(ref).pullFile(ref, dataPath)
    }

    override fun setEnvironmentVariables(ref: DeviceRef, envs: Map<String, String>) {
        nodeRegistry.activeDevices.getNodeFor(ref).setEnvironmentVariables(ref, envs)
    }
}
