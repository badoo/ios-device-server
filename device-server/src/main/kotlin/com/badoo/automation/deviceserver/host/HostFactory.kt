package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import com.badoo.automation.deviceserver.util.WdaDeviceBundle
import com.badoo.automation.deviceserver.util.WdaDeviceBundlesProvider
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles
import com.badoo.automation.deviceserver.util.WdaSimulatorBundlesProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

class HostFactory(
    private val remoteProvider: (hostName: String, publicHost: String) -> IRemote = { hostName, publicHostName ->
        Remote(
            hostName,
            publicHostName
        )
    },
    private val remoteTestHelperAppRoot: File,
    private val appConfiguration: ApplicationConfiguration
) : IHostFactory {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun  getWdaDeviceBundles(appConfiguration: ApplicationConfiguration): List<WdaDeviceBundle> {
        val wdaDeviceBundles: List<WdaDeviceBundle> = WdaDeviceBundlesProvider(
            Paths.get(appConfiguration.wdaDeviceBundles),
            Paths.get(appConfiguration.remoteWdaDeviceBundleRoot)
        ).getWdaDeviceBundles()

        return wdaDeviceBundles
    }

    fun getWdaSimulatorBundles(appConfiguration: ApplicationConfiguration): WdaSimulatorBundles {
        val wdaSimulatorBundles: WdaSimulatorBundles = WdaSimulatorBundlesProvider(
            Paths.get(appConfiguration.wdaSimulatorBundles),
            Paths.get(appConfiguration.remoteWdaSimulatorBundleRoot)
        ).getWdaSimulatorBundles()

        return wdaSimulatorBundles
    }

    override fun getHostFromConfig(config: NodeConfig): IDeviceNode {
        logger.info("Trying to start node $config.")

        val hostName = config.host
        val publicHostName = config.publicHost
        val remote: IRemote = remoteProvider(hostName, publicHostName)

        val nodeTypeResult = remote.exec(listOf("/usr/bin/arch"), mapOf(),true, 60)
        if (nodeTypeResult.isSuccess) {
            logger.info("ARCH: The executor arch is ${nodeTypeResult.stdOut} for node $publicHostName")
        } else {
            logger.error("ARCH: Failed to determine executor type for node $publicHostName. (maybe it's Linux). ${nodeTypeResult.stdErr}")
        }

        return if (config.type == NodeConfig.NodeType.Simulators) {
            val wdaSimulatorBundles = getWdaSimulatorBundles(appConfiguration)
            val hostChecker = SimulatorHostChecker(
                remote,
                shutdownSimulators = config.shutdownSimulators,
            )
            SimulatorsNode(
                remote = remote,
                publicHostName = publicHostName,
                hostChecker = hostChecker,
                simulatorLimit = config.simulatorLimit,
                concurrentBoots = config.concurrentBoots,
                wdaSimulatorBundles = wdaSimulatorBundles,
                disabledServices = config.disabledSimulatorServices
            )
        } else {
            DevicesNode(
                remote = remote,
                publicHostName = publicHostName,
                whitelistedApps = config.whitelistApps,
                configuredDevices = config.configuredDevices,
                uninstallApps = config.uninstallApps,
                wdaDeviceBundles = getWdaDeviceBundles(appConfiguration),
            )
        }
    }
}
