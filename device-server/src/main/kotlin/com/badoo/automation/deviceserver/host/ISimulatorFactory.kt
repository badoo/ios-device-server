package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.Simulator
import kotlinx.coroutines.experimental.ThreadPoolDispatcher

interface ISimulatorFactory {
    fun newSimulator(
            ref: DeviceRef,
            remote: IRemote,
            fbdev: FBSimctlDevice,
            ports: DeviceAllocatedPorts,
            deviceSetPath: String,
            wdaPath: String,
            concurrentBoot: ThreadPoolDispatcher,
            headless: Boolean,
            fbsimctlSubject: String
    ): ISimulator {
        return Simulator(ref, remote, DeviceInfo(fbdev), ports, deviceSetPath, wdaPath, concurrentBoot, headless, fbsimctlSubject)
    }
}