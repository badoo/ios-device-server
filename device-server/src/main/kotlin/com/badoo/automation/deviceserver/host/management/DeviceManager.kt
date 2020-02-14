package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.ios.ActiveDevices
import org.slf4j.LoggerFactory
import java.net.URL

class DeviceManager(
        config: DeviceServerConfig,
        nodeFactory: IHostFactory,
        activeDevices: ActiveDevices = ActiveDevices()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val nodeRegistry = NodeRegistry(activeDevices)
    private val autoRegistrar = NodeRegistrar(
            nodesConfig = config.nodes,
            nodeFactory = nodeFactory,
            nodeRegistry = nodeRegistry
    )

    fun startAutoRegisteringDevices() {
        autoRegistrar.startAutoRegistering()
    }

    fun restartNodesGracefully(isParallelRestart: Boolean): Boolean {
        return autoRegistrar.restartNodesGracefully(isParallelRestart)
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
            return nodeRegistry.createDeviceAsync(desiredCaps, userId)
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

    fun setEnvironmentVariables(ref: DeviceRef, envs: Map<String, String>) {
        nodeRegistry.activeDevices.getNodeFor(ref).setEnvironmentVariables(ref, envs)
    }

    fun resetMedia(ref: DeviceRef) {
        nodeRegistry.activeDevices.getNodeFor(ref).resetMedia(ref)
    }

    fun listMedia(ref: DeviceRef): List<String> {
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

    fun updateApplicationPlist(deviceRef: String, plistEntry: PlistEntryDTO) {
        return nodeRegistry.activeDevices.getNodeFor(deviceRef).updateApplicationPlist(deviceRef, plistEntry)
    }
}
