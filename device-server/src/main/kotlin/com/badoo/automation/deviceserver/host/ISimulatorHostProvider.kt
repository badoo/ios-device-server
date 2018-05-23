package com.badoo.automation.deviceserver.host

import java.io.File

interface ISimulatorHostProvider {
    fun simulatorsNode(
            remote: IRemote,
            simulatorLimit: Int,
            concurrentBoots: Int,
            wdaPath: File
    ): ISimulatorsNode
}