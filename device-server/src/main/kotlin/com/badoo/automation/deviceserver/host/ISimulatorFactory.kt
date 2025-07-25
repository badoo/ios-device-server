package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.Simulator
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles

interface ISimulatorFactory {
    fun newSimulator(
        ref: DeviceRef,
        remote: IRemote,
        simulatorModel: com.badoo.automation.deviceserver.simctl.models.Simulator,
        ports: DeviceAllocatedPorts,
        wdaSimulatorBundles: WdaSimulatorBundles,
        useWda: Boolean,
        disabledServices: List<String>
    ): ISimulator {
        return Simulator(
            deviceRef = ref,
            remote = remote,
            deviceInfo = DeviceInfo(simulatorModel),
            allocatedPorts = ports,
            wdaSimulatorBundles = wdaSimulatorBundles,
            useWda = useWda,
            disabledServices = disabledServices
        )
    }
}
