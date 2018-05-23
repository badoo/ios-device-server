package com.badoo.automation.deviceserver

/**
 * Constants for log markers. not LogMarkers themselves.
 * LogMarkers are used to filter messages (for example in Elastic)
 *
 * Consider using MDC (might be too complicated as set's context on thread level).
*/
class LogMarkers {
    companion object {
        const val DEVICE_REF = "deviceRef"
        const val UDID = "udid"
        const val HOSTNAME = "hostname"
        const val SSH_PROFILING = "sshProfiling"
    }
}
