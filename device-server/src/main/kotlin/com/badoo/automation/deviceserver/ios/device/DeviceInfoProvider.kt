package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.host.IRemote

class DeviceInfoProvider(private val remote: IRemote) {
    fun list(): List<DeviceInfo> {
        return remote.fbsimctl.listDevices().map { DeviceInfo(it) }
    }
}