package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.PermissionAllowed
import com.badoo.automation.deviceserver.data.PermissionSet
import com.badoo.automation.deviceserver.data.PermissionType
import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    fun setPermissions(
        bundleId: String,
        permissions: PermissionSet,
        locationPermissionsLock: ReentrantLock
    ) {
        val servicePermissions = PermissionSet()

        permissions.forEach { type, allowed ->
            when (type) {
                PermissionType.Location -> locationPermissionsLock.withLock { setLocationPermissionXcode114(bundleId, allowed) }
                PermissionType.Notifications -> setNotificationsPermission(bundleId, allowed)
                else -> servicePermissions[type] = allowed
            }
        }

        setServicePermissions(bundleId, servicePermissions)
    }

    private fun setServicePermissions(bundleId: String, servicePermissions: PermissionSet) {
        if (servicePermissions.isEmpty()) {
            return
        }

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

    @Suppress("UNUSED_PARAMETER")
    private fun setNotificationsPermission(bundleId: String, allowed: PermissionAllowed) {
        // Setting notifications permission is disallowed as it results in SpringBoard restart
        // which breaks WebDriverAgent. Restarting SpringBoard and WebDriverAgent will take too much time.
        throw RuntimeException("Setting notifications permission is not supported")
    }

    /**
     * $ xcrun simctl privacy
    Grant, revoke, or reset privacy and permissions
    Usage: simctl privacy <device> <action> <service> [<bundle identifier>]

    action
        The action to take:
            grant - Grant access without prompting. Requires bundle identifier.
            revoke - Revoke access, denying all use of the service. Requires bundle identifier.
            reset - Reset access, prompting on next use. Bundle identifier optional.
        Some permission changes will terminate the application if running.
    service
        The service:
            all - Apply the action to all services.
            calendar - Allow access to calendar.
            contacts-limited - Allow access to basic contact info.
            contacts - Allow access to full contact details.
            location - Allow access to location services when app is in use.
            location-always - Allow access to location services at all times.
            photos-add - Allow adding photos to the photo library.
            photos - Allow full access to the photo library.
            media-library - Allow access to the media library.
            microphone - Allow access to audio input.
            motion - Allow access to motion and fitness data.
            reminders - Allow access to reminders.
            siri - Allow use of the app with Siri.
    bundle identifier
        The bundle identifier of the target application.

    Examples:
        reset all permissions: privacy <device> reset all
        grant test host photo permissions: privacy <device> grant photos com.example.app.test-host

    Warning:
    Normally applications must have valid Info.plist usage description keys and follow the API guidelines to request access to services.
    Using this command to bypass those requirements can mask bugs.

     */
    private fun setLocationPermissionXcode114(bundleId: String, allowed: PermissionAllowed) {
        // map to xcrun simctl privacy
        val permission = when (allowed) {
            PermissionAllowed.Always -> "grant"
            PermissionAllowed.Inuse -> "grant"
            PermissionAllowed.Never -> "revoke"
            PermissionAllowed.Unset -> "reset"
            else -> throw IllegalArgumentException("Unsupported value $allowed for type ${PermissionType.Location}")
        }
        // service: "location-always" "location"

        val cmd = listOf("/usr/bin/xcrun", "simctl", "privacy", simulator.udid, permission, "location", bundleId)
        val env = mapOf("PATH" to "/usr/bin")
        val rv = remote.execIgnoringErrors(cmd, env, timeOutSeconds = 60)

        if (!rv.isSuccess){
            throw RuntimeException("Could not set location permissions [${allowed.value}] for bundle $bundleId: $rv")
        }
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
        val rv = remote.execIgnoringErrors(cmd, env, timeOutSeconds = 60)

        if (!rv.isSuccess){
            throw RuntimeException("Could not set location permission: $rv")
        }
    }
}
