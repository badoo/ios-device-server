package com.badoo.automation.deviceserver.repository

import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.ShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.simctl.SimCtlException
import com.badoo.automation.deviceserver.simctl.SimCtlUtility
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorPlatform
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SimulatorRepository(
    commandExecutor: IShellCommand = ShellCommand()
) {
    private val simCtlUtility: SimCtlUtility = SimCtlUtility(commandExecutor)
    private val lock: ReentrantLock = ReentrantLock()

    fun findSimulatorByUdid(sourceUdid: UDID): Simulator? {
        val devices = simCtlUtility.listDevices()
        val simulator = devices.values.flatten().firstOrNull { it.udid == sourceUdid } ?: throw SimCtlException("Simulator with UDID '$sourceUdid' not found.")
        setModel(simulator, sourceUdid)
        return simulator
    }

    fun findSimulatorByDeviceTypeAndRuntime(deviceType: DeviceType, deviceRuntime: SimulatorRuntime): Simulator? {
        val devices = simCtlUtility.listDevices()
        val simulator = devices.values.flatten().firstOrNull { it.deviceTypeIdentifier == deviceType.identifier && it.runtimeIdentifier == deviceRuntime.runtimeIdentifier}
        simulator?.let {
            setModel(it, it.udid)
        }
        return simulator
    }

    private fun setModel(simulator: Simulator, sourceUdid: UDID) {
        val deviceTypes = simCtlUtility.listDeviceTypes()
        val model = deviceTypes.find { it.identifier == simulator.deviceTypeIdentifier }?.name
            ?: throw SimCtlException("Device type for UDID '$sourceUdid' not found. $simulator : $deviceTypes")
        simulator.model = model
    }

    fun platformNameToId(platformName: String): String {
        return SimulatorPlatform.entries.firstOrNull { it.platformName == platformName }?.platformIdentifier
            ?: throw SimCtlException(
                "Platform '$platformName' is not valid. Available platforms: ${
                    SimulatorPlatform.entries.joinToString(", ") { it.platformName }
                }")
    }

    fun deviceTypeNameToId(deviceTypeName: String): String {
        val deviceTypes = simCtlUtility.listDeviceTypes()
        return deviceTypes.firstOrNull { it.name == deviceTypeName }?.identifier
            ?: throw SimCtlException("Device type '$deviceTypeName' is not valid. Available types: ${deviceTypes.joinToString(", ") { it.name }}")
    }

    fun runtimeNameToId(osVersion: String, platformIdentifier: String): String {
        val runtimes = simCtlUtility.listRuntimes()
        return runtimes.filter { it.platformIdentifier == platformIdentifier }.firstOrNull { it.version == osVersion }?.runtimeIdentifier
            ?: throw SimCtlException("Runtime '$osVersion' is not valid. Available runtimes: ${runtimes.joinToString(", ") { it.version }}")
    }

    fun createSimulator(name: String, deviceTypeName: String, osVersion: String, platformName: String = SimulatorPlatform.IOS.platformName): Simulator? {
        val deviceTypeIdentifier: String = deviceTypeNameToId(deviceTypeName)
        val runtimeIdentifier: String = runtimeNameToId(osVersion, platformNameToId(platformName))
        return lock.withLock {
            val udid = simCtlUtility.createSimulator(name, deviceTypeIdentifier, runtimeIdentifier)
            findSimulatorByUdid(udid)
        }
    }

    fun createSimulator(deviceName: String, deviceType: DeviceType, deviceRuntime: SimulatorRuntime): Simulator? {
        return lock.withLock {
            val udid = simCtlUtility.createSimulator(
                name = deviceName,
                deviceTypeId = deviceType.identifier,
                runtimeIdentifier = deviceRuntime.runtimeIdentifier
            )
            findSimulatorByUdid(udid)
        }
    }

    fun cloneSimulator(sourceSimulator: Simulator): Simulator? {
        val cloneName = "${sourceSimulator.name} Clone ${System.currentTimeMillis() / 1000}"
        return lock.withLock {
            val udid = simCtlUtility.cloneSimulator(sourceSimulator.udid, cloneName)
            findSimulatorByUdid(udid)
        }
    }

    fun deleteSimulator(udid: UDID) {
        lock.withLock {
            simCtlUtility.deleteSimulator(udid)
        }
    }

    fun listDevices() = simCtlUtility.listDevices()
    fun listDeviceTypes() = simCtlUtility.listDeviceTypes()
    fun listRuntimes() = simCtlUtility.listRuntimes()
}