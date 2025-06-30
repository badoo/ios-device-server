package com.badoo.automation.deviceserver.repository

import com.badoo.automation.deviceserver.data.DataPersistenceService
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.simctl.models.Simulator
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class SimulatorRegistryData(
    val mainSimulators: MutableSet<Simulator> = ConcurrentHashMap.newKeySet(),
    val clonedSimulators: MutableSet<Simulator> = ConcurrentHashMap.newKeySet(),
)

class SimulatorRegistry (
    private val data: SimulatorRegistryData = SimulatorRegistryData(),
    private val registryFile: File = File(System.getProperty("user.home"), ".iosctl/simulator_registry.json"),
    private val dataPersistenceService: DataPersistenceService = DataPersistenceService(registryFile),
    private val lock: ReentrantLock = ReentrantLock()
) {
    init {
        // Ensure the registry file exists and load existing data if available
        if (!registryFile.exists()) {
            registryFile.parentFile.mkdirs() // Create parent directories if they don't exist
            registryFile.createNewFile() // Create the file
        } else {
            val loadedData = dataPersistenceService.loadFromJsonFile<SimulatorRegistryData>()
            if (loadedData != null) {
                data.mainSimulators.addAll(loadedData.mainSimulators)
                data.clonedSimulators.addAll(loadedData.clonedSimulators)
            }
        }
    }

    fun getMainSimulators(): Set<Simulator> {
        return data.mainSimulators
    }

    fun getClonedSimulators(): Set<Simulator> {
        return data.clonedSimulators
    }

    fun addMainSimulator(simulator: Simulator) {
        lock.withLock {
            data.mainSimulators.add(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun addClonedSimulator(simulator: Simulator) {
        lock.withLock {
            data.clonedSimulators.add(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun removeMainSimulator(simulator: Simulator) {
        lock.withLock {
            data.mainSimulators.remove(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun removeMainSimulator(udid: UDID) {
        data.mainSimulators.find { it.udid == udid }?.let {
            removeMainSimulator(it)
        }
    }

    fun removeSimulatorClone(udid: UDID) {
        data.clonedSimulators.find { it.udid == udid }?.let {
            removeSimulatorClone(it)
        }
    }

    fun removeSimulatorClone(simulator: Simulator) {
        lock.withLock {
            data.clonedSimulators.remove(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun clear() {
        lock.withLock {
            data.mainSimulators.clear()
            data.clonedSimulators.clear()
            dataPersistenceService.saveToJsonFile(data)
        }
    }
}