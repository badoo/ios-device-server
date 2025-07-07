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
    val baseSimulators: MutableSet<Simulator> = ConcurrentHashMap.newKeySet(),
    val clonedSimulators: MutableSet<Simulator> = ConcurrentHashMap.newKeySet(),
)

class SimulatorRegistry (
    private val registryFile: File,
    private val dataPersistenceService: DataPersistenceService = DataPersistenceService(registryFile),
    private val data: SimulatorRegistryData = SimulatorRegistryData(),
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
                data.baseSimulators.addAll(loadedData.baseSimulators)
                data.clonedSimulators.addAll(loadedData.clonedSimulators)
            }
        }
    }

    fun getBaseSimulators(): Set<Simulator> {
        return data.baseSimulators
    }

    fun getClonedSimulators(): Set<Simulator> {
        return data.clonedSimulators
    }

    fun addBaseSimulator(simulator: Simulator) {
        lock.withLock {
            data.baseSimulators.add(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun addClonedSimulator(simulator: Simulator) {
        lock.withLock {
            data.clonedSimulators.add(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    private fun removeBaseSimulator(simulator: Simulator) {
        lock.withLock {
            data.baseSimulators.remove(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    private fun removeBaseSimulator(udid: UDID) {
        data.baseSimulators.find { it.udid == udid }?.let {
            removeBaseSimulator(it)
        }
    }

    fun removeSimulator(udid: UDID) {
        removeBaseSimulator(udid)
        removeSimulatorClone(udid)
    }

    private fun removeSimulatorClone(udid: UDID) {
        data.clonedSimulators.find { it.udid == udid }?.let {
            removeSimulatorClone(it)
        }
    }

    private fun removeSimulatorClone(simulator: Simulator) {
        lock.withLock {
            data.clonedSimulators.remove(simulator)
            dataPersistenceService.saveToJsonFile(data)
        }
    }

    fun clear() {
        lock.withLock {
            data.baseSimulators.clear()
            data.clonedSimulators.clear()
            dataPersistenceService.saveToJsonFile(data)
        }
    }
}