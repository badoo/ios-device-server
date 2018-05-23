package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.ISimulatorsNode

interface IHostFactory {
    fun getHostFromConfig(config: NodeConfig): ISimulatorsNode
}