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

    fun setServicePermission(bundleId: String, type: PermissionType, value: PermissionAllowed) {
        val key = serviceKeys[type]
                ?: throw(IllegalArgumentException("Permission $type is not a service type"))

        val path = File(deviceSetPath, simulator.udid)
        val sqlCmd = "sqlite3 ${path.absolutePath}/data/Library/TCC/TCC.db"

        val delete = "$sqlCmd \"DELETE FROM access WHERE service = '$key' AND client = '$bundleId' AND client_type = 0;\""

        if (!remote.shell(delete).isSuccess) {
            throw(SimulatorError("Failed to unset type $type for $this "))
        }

        if (value == PermissionAllowed.Unset) {
            return
        }

        val allowed = when (value) {
            PermissionAllowed.Yes -> 1
            PermissionAllowed.No -> 0
            else -> throw(IllegalArgumentException("Unsupported value $value for type $type"))
        }

        val replace =
            "$sqlCmd \"REPLACE INTO access (service, client, client_type, allowed, prompt_count) VALUES ('$key','$bundleId',0,$allowed,1);\""

        if (!remote.shell(replace).isSuccess) {
            throw(SimulatorError("Failed to update type $type for $this"))
        }
    }
}