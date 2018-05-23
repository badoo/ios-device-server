package com.badoo.automation.deviceserver.host

import java.io.File

object DefaultSimulatorHostProvider : ISimulatorHostProvider {
    override fun simulatorsNode(remote: IRemote, simulatorLimit: Int, concurrentBoots: Int, wdaPath: File): ISimulatorsNode {
        return SimulatorsNode(remote, simulatorLimit = simulatorLimit, concurrentBoots = concurrentBoots, wdaPath = wdaPath)
    }
}