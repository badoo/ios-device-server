package com.badoo.automation.deviceserver.simctl

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.slf4j.Marker
import java.time.Duration

internal class SimCtlUtility(
    private val commandExecutor: IShellCommand
) {
    companion object {
        const val SIMCTL_LIST_DEVICES_JSON = "/usr/bin/xcrun simctl list devices --json"
        const val SIMCTL_LIST_DEVICE_TYPES_JSON = "/usr/bin/xcrun simctl list devicetypes --json"
        const val SIMCTL_LIST_RUNTIMES_JSON = "/usr/bin/xcrun simctl runtime list --json"
        const val SIMCTL_DYLD_SHARED_CACHE_UPDATE = "/usr/bin/xcrun simctl runtime dyld_shared_cache update --all"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        prettyPrintIndent = "  "
        allowTrailingComma = true
        encodeDefaults = true
        coerceInputValues = true
        allowComments = true
    }

    // region: Create & Clone Simulators
    // Usage: simctl create <name> <device type id> [<runtime id>]
    fun createSimulator(name: String, deviceTypeId: String, runtimeIdentifier: String): UDID {
        val command = listOf(
            "/usr/bin/xcrun", "simctl", "create", name, deviceTypeId, runtimeIdentifier
        )
        val result = commandExecutor.exec(command)
        if (result.isSuccess) {
            val udid = result.stdOut.trim()

            if (udid.isEmpty() || udid.isBlank()) {
                throw SimCtlException("Simulator creation command returned an empty UDID.")
            }

            return udid
        } else {
            throw SimCtlException("Failed to create simulator: ${result.stdErr}")
        }
    }

    // Usage: simctl clone <device> <new name> [<destination device set>]
    fun cloneSimulator(sourceUdid: UDID, cloneName: String): UDID {
        val command = listOf(
            "/usr/bin/xcrun", "simctl", "clone", sourceUdid, cloneName
        )
        val result = commandExecutor.exec(command)
        if (result.isSuccess) {
            val cloneUdid = result.stdOut.trim()
            if (cloneUdid.isEmpty() || cloneUdid.isBlank()) {
                throw SimCtlException("Clone command returned an empty UDID.")
            }
            return cloneUdid
        } else {
            throw SimCtlException("Failed to clone simulator: ${result.stdErr}")
        }
    }
    // endregion

    // region: List Simulators, Device Types, and Runtimes

    private val cachedRuntimes: Set<SimulatorRuntime> by lazy { performListRuntimes() }
    private val cachedDeviceTypes: Set<DeviceType> by lazy { performListDeviceTypes() }

    fun listRuntimes(): Set<SimulatorRuntime> = cachedRuntimes
    fun listDeviceTypes(): Set<DeviceType> = cachedDeviceTypes

    private fun performListRuntimes(): Set<SimulatorRuntime> {
        val result = commandExecutor.exec(SIMCTL_LIST_RUNTIMES_JSON.split(" "))
        if (result.isSuccess) {
            return try {
                jsonParser.decodeFromString<Map<String, SimulatorRuntime>>(result.stdOut).values.toSet()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }

    // Update the shared cache after installing a new runtime.
    // https://developer.apple.com/documentation/xcode-release-notes/xcode-26_1-release-notes
    // Simulators may fail to boot during the first build after upgrading macOS. (152328794)
    fun dyldSharedCacheUpdate() {
        val result = commandExecutor.exec(SIMCTL_DYLD_SHARED_CACHE_UPDATE.split(" "))
        if (result.isSuccess) {

        } else {
            throw SimCtlException("Failed to update cache: ${result.stdErr}")
        }
    }

    private fun performListDeviceTypes(): Set<DeviceType> {
        val result = commandExecutor.exec(SIMCTL_LIST_DEVICE_TYPES_JSON.split(" "))
        if (result.isSuccess) {
            return try {
                jsonParser.decodeFromString<Map<String, List<DeviceType>>>(result.stdOut).values.first().toSet()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }

    fun listDevices(): Map<String, List<Simulator>> {
        val runtimes = listRuntimes()
        val result = commandExecutor.exec(SIMCTL_LIST_DEVICES_JSON.split(" "))
        if (result.isSuccess) {
            val devicesByRuntimeIdentifier: Map<String, List<Simulator>> = try {
                jsonParser.decodeFromString<Map<String, Map<String, List<Simulator>>>>(result.stdOut).values.first()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
            return devicesByRuntimeIdentifier.map { (runtimeIdentifier, simulators) ->
                simulators.forEach { simulator ->
                    simulator.runtimeIdentifier = runtimeIdentifier
                    runtimes.find { runtime -> runtime.runtimeIdentifier == runtimeIdentifier }?.version?.let { version ->
                        simulator.osVersion = version
                    }
                }
                runtimeIdentifier to simulators
            }.toMap()
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }
    // endregion

    // region: Boot & Shutdown Simulators
    fun bootSimulator(udid: UDID, disabledServices: List<String>, timeOut: Duration, logMarker: Marker) {
        val command = mutableListOf("/usr/bin/xcrun", "simctl", "boot", udid)
        command.addAll(disabledServices)
        val result = commandExecutor.exec(
            command = command,
            timeOut = timeOut,
            returnFailure = true,
            logMarker = logMarker
        )
        if (!result.isSuccess) {
            throw SimCtlException("Failed to boot simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun bootStatusSimulator(udid: UDID, timeOut: Duration, logMarker: Marker) {
        val command = listOf("/usr/bin/xcrun", "simctl", "bootstatus", udid)
        val result = commandExecutor.exec(
            command = command,
            timeOut = timeOut,
            returnFailure = true,
            logMarker = logMarker
        )
        if (!result.isSuccess) {
            throw SimCtlException("Failed to boot simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun shutdownSimulator(udid: UDID, ignoreError: Boolean): CommandResult {
        val command = listOf("/usr/bin/xcrun", "simctl", "shutdown", udid)
        val result = commandExecutor.exec(command)
        if (!result.isSuccess && !ignoreError) {
            throw SimCtlException("Failed to shutdown simulator with UDID '$udid': ${result.stdErr}")
        }

        return result
    }

    fun shutdownAllSimulators() {
        shutdownSimulator("all", ignoreError = true)
    }
    // endregion

    // region: Delete Simulators
    // Usage: simctl delete <device> [... <device n>] | unavailable | all
    fun deleteSimulator(udid: UDID) {
        val command = listOf("/usr/bin/xcrun", "simctl", "delete", udid)
        val result = commandExecutor.exec(command)
        if (!result.isSuccess) {
            throw SimCtlException("Failed to delete simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun deleteAllSimulators() {
        deleteSimulator("all")
    }

    fun deleteUnavailableSimulators() {
        deleteSimulator("unavailable")
    }
    // endregion
}