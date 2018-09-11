package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.net.URI

class SimulatorWebDriverAgent(
        remote: IRemote,
        wdaRunnerXctest: File,
        udid: UDID,
        wdaEndpoint: URI
) : WebDriverAgent(
        remote = remote,
        wdaRunnerXctest = wdaRunnerXctest,
        hostApp = "com.apple.MobileAddressBook", // seems like AddressBook has the least memory usage and no alerts
        udid = udid,
        wdaEndpoint = wdaEndpoint
)