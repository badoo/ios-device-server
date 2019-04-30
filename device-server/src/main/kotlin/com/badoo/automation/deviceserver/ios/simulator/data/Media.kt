package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import java.lang.RuntimeException
import java.nio.file.Paths

class Media(
    private val remote: IRemote,
    udid: UDID,
    deviceSetPath: String
) {
    private val mediaPath = Paths.get(deviceSetPath, udid, "data", "Media")

    fun reset() {
        val imagesPath = mediaPath.resolve("DCIM").toString()
        val photoDataPath = mediaPath.resolve("PhotoData/Photos.sqlite").toString()

        val shellCmd = "rm $imagesPath/**/* $photoDataPath*; touch $photoDataPath"

        val result = remote.shell(shellCmd)

        if (!result.isSuccess) {
            throw RuntimeException("Could not reset Media: $result")
        }
    }
}
