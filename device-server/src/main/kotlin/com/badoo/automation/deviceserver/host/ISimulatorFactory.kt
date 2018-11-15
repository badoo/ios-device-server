package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.Simulator
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import java.io.File

interface ISimulatorFactory {
    fun newSimulator(
            ref: DeviceRef,
            remote: IRemote,
            fbdev: FBSimctlDevice,
            ports: DeviceAllocatedPorts,
            deviceSetPath: String,
            wdaRunnerXctest: File,
            concurrentBoot: ThreadPoolDispatcher,
            headless: Boolean,
            fbsimctlSubject: String,
            debugXCTest: Boolean
    ): ISimulator {
        return Simulator(
            ref,
            remote,
            DeviceInfo(fbdev),
            ports,
            deviceSetPath,
            wdaRunnerXctest,
            concurrentBoot,
            headless,
            fbsimctlSubject,
            debugXCTest
        )
    }
}