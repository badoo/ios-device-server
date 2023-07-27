package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.IDeviceNode

interface IHostFactory {
    fun getHostFromConfig(config: NodeConfig): IDeviceNode
}
