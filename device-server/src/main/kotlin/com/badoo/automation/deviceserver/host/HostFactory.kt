package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import org.slf4j.LoggerFactory
import java.io.File

class HostFactory(
    private val remoteProvider: (hostName: String, userName: String, publicHost: String) -> IRemote = { hostName, userName, publicHostName -> Remote(hostName, userName, publicHostName) },
    private val wdaSimulatorBundle: File,
    private val remoteWdaSimulatorBundleRoot: File,
    private val wdaDeviceBundle: File,
    private val remoteWdaDeviceBundleRoot: File,
    private val fbsimctlVersion: String,
    private val remoteTestHelperAppRoot: File
) : IHostFactory {
    companion object {
        val WDA_XCTEST = File("PlugIns/WebDriverAgentRunner.xctest")
        val DA_XCTEST = File("PlugIns/DeviceAgent.xctest")
    }

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

        return if (config.type == NodeConfig.NodeType.Simulators) {
            SimulatorsNode(
                remote = remote,
                publicHostName = publicHostName,
                hostChecker = SimulatorHostChecker(
                    remote,
                    wdaBundle = wdaSimulatorBundle,
                    remoteWdaBundleRoot = remoteWdaSimulatorBundleRoot,
                    remoteTestHelperAppRoot = remoteTestHelperAppRoot,
                    fbsimctlVersion = fbsimctlVersion,
                    shutdownSimulators = config.shutdownSimulators
                ),
                simulatorLimit = config.simulatorLimit,
                concurrentBoots = config.concurrentBoots,
                wdaRunnerXctest = getWdaRunnerXctest(remote.isLocalhost(), wdaSimulatorBundle, remoteWdaSimulatorBundleRoot)
            )
        } else {
            DevicesNode(
                remote = remote,
                publicHostName = publicHostName,
                whitelistedApps = config.whitelistApps,
                knownDevices = config.knownDevices,
                uninstallApps = config.uninstallApps,
                wdaBundlePath = wdaDeviceBundle,
                remoteWdaBundleRoot = remoteWdaDeviceBundleRoot,
                wdaRunnerXctest = getWdaRunnerXctest(remote.isLocalhost(), wdaDeviceBundle, remoteWdaDeviceBundleRoot),
                fbsimctlVersion = fbsimctlVersion
            )
        }
    }

    private fun getWdaRunnerXctest(isLocalHost: Boolean, wdaBundle: File, remoteWdaBundleRoot: File): File {
        val xcTestPath = if (wdaBundle.name.contains("DeviceAgent")) DA_XCTEST.path else WDA_XCTEST.path
        val wdaRunnerXctest =  File(wdaBundle.name, xcTestPath).path

        val wdaBundleRoot = if (isLocalHost) {
            wdaBundle.parentFile
        } else {
            remoteWdaBundleRoot
        }

        return File(wdaBundleRoot, wdaRunnerXctest)
    }
}
