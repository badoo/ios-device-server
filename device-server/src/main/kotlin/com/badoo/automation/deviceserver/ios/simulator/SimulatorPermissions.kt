package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.PermissionAllowed
import com.badoo.automation.deviceserver.data.PermissionType
import com.badoo.automation.deviceserver.host.IRemote
import java.io.File

class SimulatorPermissions(
    private val remote: IRemote,
    private val deviceSetPath: String,
    private val simulator: ISimulator
) {

    private val serviceKeys = mapOf(
        PermissionType.Calendar to "kTCCServiceCalendar",
        PermissionType.Camera to "kTCCServiceCamera",
        PermissionType.Contacts to "kTCCServiceAddressBook",
        PermissionType.HomeKit to "kTCCServiceWillow",
        PermissionType.Microphone to "kTCCServiceMicrophone",
        PermissionType.Photos to "kTCCServicePhotos",
        PermissionType.Reminders to "kTCCServiceReminders",
        PermissionType.MediaLibrary to "kTCCServiceMediaLibrary",
        PermissionType.Motion to "kTCCServiceMotion",
        PermissionType.Health to "kTCCServiceMSO",
        PermissionType.Siri to "kTCCServiceSiri",
        PermissionType.Speech to "kTCCServiceSpeechRecognition"
    )

    fun setPermission(bundleId: String, type: PermissionType, value: PermissionAllowed) {
        when (type) {
            PermissionType.Location -> setLocationPermission(bundleId, value)
            PermissionType.Notifications -> setNotificationsPermission(bundleId, value)
            else -> setServicePermission(bundleId, type, value)
        }
    }

    fun setServicePermission(bundleId: String, type: PermissionType, allowed: PermissionAllowed) {
        val key = serviceKeys[type]
                ?: throw(IllegalArgumentException("Permission $type is not a service type"))

        val path = File(deviceSetPath, simulator.udid)
        val sqlCmd = "sqlite3 ${path.absolutePath}/data/Library/TCC/TCC.db"

        val delete = "$sqlCmd \"DELETE FROM access WHERE service = '$key' AND client = '$bundleId' AND client_type = 0;\""

        if (!remote.shell(delete).isSuccess) {
            throw(SimulatorError("Failed to unset type $type for $this "))
        }

        if (allowed == PermissionAllowed.Unset) {
            return
        }

        val value = when (allowed) {
            PermissionAllowed.Yes -> 1
            PermissionAllowed.No -> 0
            else -> throw IllegalArgumentException("Unsupported value $allowed for type $type")
        }

        val replace =
            "$sqlCmd \"REPLACE INTO access (service, client, client_type, allowed, prompt_count) VALUES ('$key','$bundleId',0,$value,1);\""

        if (!remote.shell(replace).isSuccess) {
            throw(SimulatorError("Failed to update type $type for $this"))
        }
    }

    private val appleSimUtils = "/usr/local/bin/applesimutils"

    @Suppress("UNUSED_PARAMETER")
    private fun setNotificationsPermission(bundleId: String, allowed: PermissionAllowed) {
        // Setting notifications permission is disallowed as it results in SpringBoard restart
        // which breaks WebDriverAgent. Restarting SpringBoard and WebDriverAgent will take too much time.
        throw RuntimeException("Setting notifications permission is not supported")
    }

    private fun setLocationPermission(bundleId: String, allowed: PermissionAllowed) {
        // map to applesimutils values
        val value = when (allowed) {
            PermissionAllowed.Always -> "always"
            PermissionAllowed.Inuse -> "inuse"
            PermissionAllowed.Never -> "never"
            PermissionAllowed.Unset -> "unset"
            else -> throw IllegalArgumentException("Unsupported value $allowed for type ${PermissionType.Location}")
        }

        val cmd = listOf(appleSimUtils, "--byId", simulator.udid, "--bundle", bundleId, "--setPermissions", "location=$value")

        // Without PATH applesimutils will crash with 'NSInvalidArgumentException', reason: 'must provide a launch path'
        val env = mapOf("PATH" to "/usr/bin")
        val rv = remote.execIgnoringErrors(cmd, env)

        if (!rv.isSuccess){
            throw RuntimeException("Could not set location permission: $rv")
        }
    }
}