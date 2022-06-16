package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import com.badoo.automation.deviceserver.util.WdaDeviceBundle
import com.badoo.automation.deviceserver.util.WdaSimulatorBundle
import org.slf4j.LoggerFactory
import java.io.File

class HostFactory(
    private val remoteProvider: (hostName: String, userName: String, publicHost: String) -> IRemote = { hostName, userName, publicHostName ->
        Remote(
            hostName,
            userName,
            publicHostName
        )
    },
    private val wdaSimulatorBundle: WdaSimulatorBundle,
    private val wdaDeviceBundles: List<WdaDeviceBundle>,
    private val fbsimctlVersion: String,
    private val remoteTestHelperAppRoot: File,
    private val remoteVideoRecorder: File
) : IHostFactory {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override fun getHostFromConfig(config: NodeConfig): ISimulatorsNode {
        logger.info("Trying to start node $config.")

        val hostName = config.host
        val userName = config.user
        val publicHostName = config.publicHost
        val remote: IRemote = remoteProvider(hostName, userName, publicHostName)

        if (userName.isBlank() && !remote.isLocalhost()) {
            throw RuntimeException("Config for non-localhost nodes must have non-empty 'user'. Current config: $config")
        }

        val nodeTypeResult = remote.exec(listOf("/usr/bin/arch"), mapOf(),true, 60)
        if (nodeTypeResult.isSuccess) {
            logger.info("ARCH: The executor arch is ${nodeTypeResult.stdOut}")
        } else {
            logger.error("ARCH: Failed to determine executor type. (maybe it's Linux). ${nodeTypeResult.stdErr}")
        }

        return if (config.type == NodeConfig.NodeType.Simulators) {
            SimulatorsNode(
                remote = remote,
                publicHostName = publicHostName,
                hostChecker = SimulatorHostChecker(
                    remote,
                    wdaSimulatorBundle = wdaSimulatorBundle,
                    remoteTestHelperAppRoot = remoteTestHelperAppRoot,
                    remoteVideoRecorder = remoteVideoRecorder,
                    fbsimctlVersion = fbsimctlVersion,
                    shutdownSimulators = config.shutdownSimulators
                ),
                simulatorLimit = config.simulatorLimit,
                concurrentBoots = config.concurrentBoots,
                wdaSimulatorBundle = wdaSimulatorBundle
            )
        } else {
            DevicesNode(
                remote = remote,
                publicHostName = publicHostName,
                whitelistedApps = config.whitelistApps,
                knownDevices = config.knownDevices,
                uninstallApps = config.uninstallApps,
                wdaDeviceBundles = wdaDeviceBundles,
                fbsimctlVersion = fbsimctlVersion
            )
        }
    }
}
