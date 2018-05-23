package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.DeviceDTO
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import java.time.Duration

interface IActiveDevices {
    fun deviceRefs(): Set<DeviceRef>
    fun registerDevice(ref: DeviceRef, node: ISimulatorsNode, releaseTimeout: Duration, userId: String?)
    fun getNodeFor(ref: DeviceRef): ISimulatorsNode
    fun unregisterDeleteDevice(ref: DeviceRef)
    fun readyForRelease(): List<DeviceRef>
    fun nextReleaseAtSeconds(): Long
    fun unregisterNodeDevices(node: ISimulatorsNode)
    fun getStatus(): String
    fun deviceList(): List<DeviceDTO>
    fun releaseDevice(ref: DeviceRef, reason: String)
    fun getUserDeviceRefs(userId: String) : List<DeviceRef>
    fun releaseDevices(entries: List<DeviceRef>, reason: String)
}