package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.repository.SimulatorRegistry
import com.badoo.automation.deviceserver.repository.SimulatorRepository
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import java.lang.RuntimeException
import java.time.Duration

class SimulatorProvider(
    val remote: IRemote, private val simulatorRepository: SimulatorRepository, private val simulatorRegistry: SimulatorRegistry
) {
    fun createSimulatorClone(desiredCaps: DesiredCapabilities, usedUdids: Set<String>): Simulator {
        if (desiredCaps.udid != null && desiredCaps.udid.isNotBlank()) {
            return provideSimulatorByUdid(usedUdids = usedUdids, udid = desiredCaps.udid, simulators = simulatorRepository.listDevices().values.flatten())
        }

        val deviceType: DeviceType = desiredCaps.toDeviceType(simulatorRepository)
        val runtime: SimulatorRuntime = desiredCaps.toRuntime(simulatorRepository)
        val mainSimulator: Simulator = ensureMainSimulatorExists(deviceType, runtime, desiredCaps)

        val clone = simulatorRepository.cloneSimulator(mainSimulator.udid)
            ?: throw RuntimeException("Unable to clone main simulator: $mainSimulator")

        simulatorRegistry.addClonedSimulator(clone)

        return clone
    }

    fun deleteSimulatorClone(udid: UDID) {
        simulatorRepository.deleteSimulator(udid)
        simulatorRegistry.removeSimulatorClone(udid)
    }

    fun createMainSimulator(desiredCaps: DesiredCapabilities, bootWaitDuration: Duration): Simulator {
        return ensureMainSimulatorExists(desiredCaps.toDeviceType(simulatorRepository), desiredCaps.toRuntime(simulatorRepository), desiredCaps, bootWaitDuration)
    }

    fun deleteMainSimulator(udid: UDID) {
        simulatorRepository.deleteSimulator(udid)
        simulatorRegistry.removeMainSimulator(udid)
    }

    private fun ensureMainSimulatorExists(deviceType: DeviceType, runtime: SimulatorRuntime, desiredCaps: DesiredCapabilities, bootWaitDuration: Duration = Duration.ofSeconds(180)): Simulator {
        return (simulatorRegistry.getMainSimulators()
            .find { it.deviceTypeIdentifier == deviceType.identifier && it.osVersion == runtime.runtimeIdentifier }
            ?: createMainSimulator(desiredCaps.model!!, deviceType, runtime, bootWaitDuration))
    }

    private fun createMainSimulator(deviceName: String, deviceType: DeviceType, runtime: SimulatorRuntime, bootWaitDuration: Duration): Simulator {
        val createdMain: Simulator = simulatorRepository.createSimulator(deviceName, deviceType, runtime)
            ?: throw RuntimeException("Failed to create main simulator with deviceName: $deviceName, deviceType: $deviceType, runtime: $runtime")

        with(createdMain) {
            simulatorRepository.bootSimulator(udid)
            Thread.sleep(bootWaitDuration) // Wait for the simulator to boot all services // FIXME: Make it more sophisticated
            simulatorRepository.shutdownSimulator(udid, false)
            simulatorRegistry.addMainSimulator(this)
            return this
        }
    }

    private fun provideSimulatorByUdid(usedUdids: Set<String>, udid: String, simulators: List<Simulator>): Simulator {
        if (usedUdids.contains(udid)) {
            throw RuntimeException("Simulator with UDID ${udid} is already in use. List of used devices is $usedUdids")
        }

        val matched = simulators.find { it.udid == udid }

        if (matched == null) {
            throw RuntimeException("Unable to find requested device with UDID ${udid}. List of known devices is $simulators")
        }

        return matched
    }
}

private fun DesiredCapabilities.toDeviceType(simulatorRepository: SimulatorRepository): DeviceType {
    check(model != null) { "Device \"model\" cannot be null. $this" }

    return simulatorRepository.listDeviceTypes().find { it.name == model }
        ?: throw RuntimeException("Unable to device type for desired capabilities: $this. Available runtimes: ${simulatorRepository.listDeviceTypes()}")
}

private fun DesiredCapabilities.toRuntime(simulatorRepository: SimulatorRepository): SimulatorRuntime {
    check(osVersion != null) { "Device \"os\" cannot be null. $this" }

    return simulatorRepository.listRuntimes().find { it.version == osVersion }
        ?: throw RuntimeException("Unable to find runtime for desired capabilities: $this. Available runtimes: ${simulatorRepository.listRuntimes()}")
}
