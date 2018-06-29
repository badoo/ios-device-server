package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import java.net.URI

class SimulatorWebDriverAgent(
        remote: IRemote,
        wdaPath: String,
        udid: UDID,
        wdaEndpoint: URI,
        hostApp: String = "com.apple.MobileAddressBook" // seems like AddressBook has the least memory usage and no alerts
) : WebDriverAgent(
        remote = remote,
        wdaPath = wdaPath,
        hostApp = hostApp,
        udid = udid,
        wdaEndpoint = wdaEndpoint
)