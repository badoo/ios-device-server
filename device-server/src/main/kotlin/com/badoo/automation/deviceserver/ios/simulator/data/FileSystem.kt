package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import java.nio.file.Paths

class FileSystem(
    private val remote: IRemote,
    private val udid: UDID
) {
    fun dataContainer(bundleId: String): DataContainer {
        val app = remote.fbsimctl.listApps(udid).find { it.bundle.bundle_id == bundleId }

        if (app?.data_container == null) {
            throw(IllegalArgumentException("No data container for $bundleId"))
        }

        return DataContainer(
            remote,
            Paths.get(app.data_container),
            bundleId
        )
    }
}
