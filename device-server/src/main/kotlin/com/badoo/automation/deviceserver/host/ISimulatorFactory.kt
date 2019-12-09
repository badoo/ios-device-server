package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.Simulator
import java.io.File
import java.util.concurrent.ExecutorService

interface ISimulatorFactory {
    fun newSimulator(
            ref: DeviceRef,
            remote: IRemote,
            fbdev: FBSimctlDevice,
            ports: DeviceAllocatedPorts,
            deviceSetPath: String,
            wdaRunnerXctest: File,
            concurrentBoot: ExecutorService,
            headless: Boolean,
            useWda: Boolean,
            fbsimctlSubject: String
    ): ISimulator {
        return Simulator(ref, remote, DeviceInfo(fbdev), ports, deviceSetPath, wdaRunnerXctest, concurrentBoot,
                headless, useWda, fbsimctlSubject)
    }
}
