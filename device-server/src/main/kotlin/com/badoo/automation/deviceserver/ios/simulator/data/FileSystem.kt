package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.*
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.nio.file.Paths

class FileSystem(
    private val remote: IRemote,
    private val udid: UDID
) {
    private val logger = LoggerFactory.getLogger(DataContainer::class.java.simpleName)
    private val deviceRef = deviceRefFromUDID(udid, remote.publicHostName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
        LogMarkers.HOSTNAME to remote.hostName,
        LogMarkers.UDID to udid,
        LogMarkers.DEVICE_REF to deviceRef
    ))

    fun dataContainer(bundleId: String): DataContainer {
        for (i in 1..3) {
            try {
                val appInfo = applicationInfo(bundleId)
                val dataContainer = appInfo.data_container
                    ?: throw(DataContainerNotFoundError("No data container for $bundleId for $udid on $deviceRef"))

                return DataContainer(
                    remote,
                    Paths.get(dataContainer),
                    bundleId
                )
            } catch (e: EmptyApplicationsListError) {
                logger.error(logMarker, "No applications returned by fbsimctl list_apps for $udid")
                applicationInfo(bundleId)
            } catch (e: ApplicationNotFoundError) {
                logger.error(logMarker, "Application $bundleId is not found for $udid by fbsimctl")
                applicationInfo(bundleId)
            } catch (e: DataContainerNotFoundError) {
                logger.error(logMarker, "Data container is not found for application $bundleId for $udid by fbsimctl")
                applicationInfo(bundleId)
            }
        }

        throw(DataContainerNotFoundError("Unable to find data container for $bundleId for $udid on $deviceRef"))
    }

    fun applicationContainer(bundleId: String): DataContainer {
        for (i in 1..3) {
            try {
                val appInfo = applicationInfo(bundleId)
                val dataContainer = appInfo.bundle.path
                    ?: throw(ApplicationContainerNotFoundError("No application container for $bundleId for $udid on $deviceRef"))

                return DataContainer(
                    remote,
                    Paths.get(dataContainer),
                    bundleId
                )
            } catch (e: EmptyApplicationsListError) {
                logger.error(logMarker, "No applications returned by fbsimctl list_apps for $udid")
                applicationInfo(bundleId)
            } catch (e: ApplicationNotFoundError) {
                logger.error(logMarker, "Application $bundleId is not found for $udid by fbsimctl")
                applicationInfo(bundleId)
            } catch (e: DataContainerNotFoundError) {
                logger.error(logMarker, "Data container is not found for application $bundleId for $udid by fbsimctl")
                applicationInfo(bundleId)
            }
        }

        throw(ApplicationContainerNotFoundError("Unable to find application container for $bundleId for $udid on $deviceRef"))
    }

    private fun applicationInfo(bundleId: String): FBSimctlAppInfo {
        val apps = remote.fbsimctl.listApps(udid)

        if (apps.isEmpty()) {
            throw EmptyApplicationsListError("No applications returned by fbsimctl list_apps")
        }

        val app = apps.find { it.bundle.bundle_id == bundleId }
            ?: throw ApplicationNotFoundError("Application $bundleId is not found for $udid by fbsimctl")

        return app
    }
}
