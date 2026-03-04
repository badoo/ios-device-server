package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import com.badoo.automation.deviceserver.util.NetworkUtils
import com.badoo.automation.deviceserver.util.WdaDeviceBundle
import com.badoo.automation.deviceserver.util.WdaDeviceBundlesProvider
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles
import com.badoo.automation.deviceserver.util.WdaSimulatorBundlesProvider
import org.slf4j.LoggerFactory
import java.nio.file.Paths

class HostFactory(
    private val remoteProvider: (hostName: String, publicHost: String) -> IRemote = { hostName, publicHostName ->
        Remote(hostName, publicHostName)
    },
    private val appConfiguration: ApplicationConfiguration
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun createNodes(config: DeviceServerConfig): List<IDeviceNode> {
        val publicHostName = config.publicHostName ?: NetworkUtils.getAddresses().first().ip
        val remote = remoteProvider(publicHostName, publicHostName)
        val sharedPortAllocator = PortAllocator(remote)
        val nodes = mutableListOf<IDeviceNode>()

        config.simulators?.let { sim ->
            logger.info("Creating SimulatorsNode for $publicHostName (limit=${sim.simulatorLimit})")
            nodes += SimulatorsNode(
                remote = remote,
                publicHostName = publicHostName,
                hostChecker = SimulatorHostChecker(remote, sim.shutdownSimulators),
                simulatorLimit = sim.simulatorLimit,
                concurrentBoots = sim.concurrentBoots,
                wdaSimulatorBundles = getWdaSimulatorBundles(),
                portAllocator = sharedPortAllocator,
                disabledServices = sim.disabledSimulatorServices
            )
        }

        config.devices?.let { dev ->
            logger.info("Creating DevicesNode for $publicHostName")
            nodes += DevicesNode(
                remote = remote,
                publicHostName = publicHostName,
                portAllocator = sharedPortAllocator,
                configuredDevices = dev.configuredDevices,
                whitelistedApps = dev.whitelistApps,
                uninstallApps = dev.uninstallApps,
                wdaDeviceBundles = getWdaDeviceBundles()
            )
        }

        return nodes
    }

    private fun getWdaSimulatorBundles(): WdaSimulatorBundles {
        return WdaSimulatorBundlesProvider(
            Paths.get(appConfiguration.wdaSimulatorBundles),
            Paths.get(appConfiguration.remoteWdaSimulatorBundleRoot)
        ).getWdaSimulatorBundles()
    }

    private fun getWdaDeviceBundles(): List<WdaDeviceBundle> {
        return WdaDeviceBundlesProvider(
            Paths.get(appConfiguration.wdaDeviceBundles),
            Paths.get(appConfiguration.remoteWdaDeviceBundleRoot)
        ).getWdaDeviceBundles()
    }
}
