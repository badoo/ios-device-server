package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.LocationDto
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote

class LocationManager(
    private val remote: IRemote,
    private val udid: UDID
) {
    fun clear() {
        val result = remote.shell("/usr/bin/xcrun simctl location $udid clear")

        if (!result.isSuccess) {
            throw RuntimeException("Could not clear location on device $udid: $result")
        }
    }

    fun listScenarios(): List<String> {
        val result = remote.shell("/usr/bin/xcrun simctl location $udid list")

        if (!result.isSuccess) {
            throw RuntimeException("Could not list location scenarios on device $udid: $result")
        }

        return result.stdOut.lines()
    }

    fun setLocation(latitude: Double, longitude: Double) {
        val result = remote.shell("/usr/bin/xcrun simctl location $udid set ${latitude},${longitude}")

        if (!result.isSuccess) {
            throw RuntimeException("Could not set location to ${latitude},${longitude} on device $udid: $result")
        }
    }

    fun runScenario(scenarioName: String) {
        val result = remote.shell("/usr/bin/xcrun simctl location $udid run \"$scenarioName\"")

        if (!result.isSuccess) {
            throw RuntimeException("Could not run scenario \"$scenarioName\" on device $udid: $result")
        }
    }

    fun startLocationSequence(speed: Int, distance: Int, interval: Int, coords: List<LocationDto>) {
        check(!(distance > 0 && interval > 0)) {
            "Distance and Interval are mutually exclusive parameters. Please set only one. Current values are: distance:$distance, interval:$interval"
        }

        check(coords.isNotEmpty()) {
            "Coordinates list must not be empty."
        }

        val command = StringBuilder("/usr/bin/xcrun simctl location $udid start")

        if (speed > 0) {
            command.append(" --speed=$speed")
        }

        if (distance > 0) {
            command.append(" --distance=$distance")
        }

        if (interval > 0) {
            command.append(" --interval=$interval")
        }

        command.append(" ")
        command.append(coords.joinToString(" ") { "${it.latitude},${it.longitude}" })

        val result = remote.shell(command.toString())

        if (!result.isSuccess) {
            throw RuntimeException("Could not start location sequence on device $udid: $result")
        }
    }
}
