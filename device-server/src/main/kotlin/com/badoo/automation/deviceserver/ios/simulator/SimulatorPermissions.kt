package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.PermissionAllowed
import com.badoo.automation.deviceserver.data.PermissionSet
import com.badoo.automation.deviceserver.data.PermissionType
import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.nio.file.Paths

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

    fun setPermissions(bundleId: String, permissions: PermissionSet) {
        val servicePermissions = PermissionSet()

        permissions.forEach { type, allowed ->
            when (type) {
                PermissionType.Location -> setLocationPermission(bundleId, allowed)
                PermissionType.Notifications -> setNotificationsPermission(bundleId, allowed)
                else -> servicePermissions[type] = allowed
            }
        }

        setServicePermissions(bundleId, servicePermissions)
    }

    private fun setServicePermissions(bundleId: String, servicePermissions: PermissionSet) {
        val sql = StringBuilder()

        servicePermissions.forEach { type, allowed ->
            sql.append(sqlForPermission(bundleId, type, allowed))
        }

        val path = File(deviceSetPath, simulator.udid)
        val sqlCmd = "sqlite3 ${path.absolutePath}/data/Library/TCC/TCC.db \"pragma busy_timeout=1000; $sql\""

        val result = remote.shell(sqlCmd)

        if (!result.isSuccess) {
            throw(SimulatorError("Could not set permissions: $result"))
        }
    }

    private fun sqlForPermission(bundleId: String, type: PermissionType, allowed: PermissionAllowed): String? {
        val sql = StringBuilder()

        val key = serviceKeys[type]
                ?: throw(IllegalArgumentException("Permission $type is not a service type"))

        sql.append("DELETE FROM access WHERE service = '$key' AND client = '$bundleId' AND client_type = 0;")

        if (allowed == PermissionAllowed.Unset) {
            return sql.toString()
        }

        val value = when (allowed) {
            PermissionAllowed.Yes -> 1
            PermissionAllowed.No -> 0
            else -> throw IllegalArgumentException("Unsupported value $allowed for type $type")
        }

        sql.append("REPLACE INTO access (service, client, client_type, allowed, prompt_count) VALUES ('$key','$bundleId',0,$value,1);")

        return sql.toString()
    }

    private val appleSimUtils = "/usr/local/bin/applesimutils"
    private val plUtil = "/usr/bin/plutil"

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

        val plistPath = Paths.get(deviceSetPath, simulator.udid, "data", "Library", "Caches", "locationd", "clients.plist").toFile().absolutePath
        val printCmd = listOf(plUtil, "-p", plistPath)
        val result = remote.execIgnoringErrors(printCmd)

        when (allowed) {
            PermissionAllowed.Unset -> if (result.stdOut.contains(bundleId)) {
                throw RuntimeException("Resetting location permissions did not work. The $bundleId is present in $plistPath")
            }
            else -> if (!result.stdOut.contains(bundleId)) {
                throw RuntimeException("Setting location permissions did not work. The $bundleId is not present in $plistPath")
            }
        }

    }
}