package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.PermissionAllowed
import com.badoo.automation.deviceserver.data.PermissionSet
import com.badoo.automation.deviceserver.data.PermissionType
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote

class SimulatorPermissions(
    private val remote: IRemote,
    private val udid: UDID
) {
    fun setPermissions(
        bundleId: String,
        permissions: PermissionSet
    ) {
        permissions.forEach { service, action ->
            setPrivacy(bundleId, service, action)
        }
    }

    private fun setPrivacy(bundleId: String, service: PermissionType, action: PermissionAllowed) {
        val cmd = listOf("/usr/bin/xcrun", "simctl", "privacy", udid, action.value, service.value, bundleId)
        val env = mapOf("PATH" to "/usr/bin")
        val result = remote.execIgnoringErrors(cmd, env, timeOutSeconds = 60)

        if (!result.isSuccess){
            throw RuntimeException("Could not [${action.value}] permissions for service [${service.value}] for bundle $bundleId: $result")
        }
    }
}
