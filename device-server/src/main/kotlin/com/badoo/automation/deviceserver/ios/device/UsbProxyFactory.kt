package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote

class UsbProxyFactory(
    private val remote: IRemote
) {
    fun create(udid: UDID, localPort: Int): UsbProxy {
        return UsbProxy(udid, remote, localPort)
    }
}