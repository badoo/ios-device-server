package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import org.slf4j.LoggerFactory
import java.io.File

class HostFactory(
    private val remoteProvider: (hostName: String, userName: String, publicHost: String) -> IRemote = { hostName, userName, publicHostName -> Remote(hostName, userName, publicHostName) },
    private val wdaSimulatorBundle: File,
    private val wdaDeviceBundle: File,
    private val fbsimctlVersion: String
) : IHostFactory {
    companion object {
        val WDA_XCTEST = File("PlugIns/WebDriverAgentRunner.xctest")
        private val REMOTE_WDA_BUNDLE_ROOT = File("/tmp/web_driver_agent/")
        private val REMOTE_WDA_DEVICE_BUNDLE_ROOT = File("/tmp/web_driver_agent_devices/")
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
                hostChecker = SimulatorHostChecker(
                    remote,
                    wdaBundle = wdaSimulatorBundle,
                    remoteWdaBundleRoot = REMOTE_WDA_BUNDLE_ROOT,
                    fbsimctlVersion = fbsimctlVersion
                ),
                simulatorLimit = config.simulatorLimit,
                concurrentBoots = config.concurrentBoots,
                wdaRunnerXctest = getWdaRunnerXctest(remote.isLocalhost(), wdaSimulatorBundle, REMOTE_WDA_BUNDLE_ROOT)
            )
        } else {
            DevicesNode(
                remote,
                whitelistedApps = config.whitelistApps,
                knownDevices = config.knownDevices,
                uninstallApps = config.uninstallApps,
                wdaBundlePath = wdaDeviceBundle,
                remoteWdaBundleRoot = REMOTE_WDA_DEVICE_BUNDLE_ROOT,
                wdaRunnerXctest = getWdaRunnerXctest(remote.isLocalhost(), wdaDeviceBundle, REMOTE_WDA_DEVICE_BUNDLE_ROOT),
                fbsimctlVersion = fbsimctlVersion
            )
        }
    }

    private fun getWdaRunnerXctest(isLocalHost: Boolean, wdaBundle: File, remoteWdaBundleRoot: File): File {
        val wdaRunnerXctest = File(wdaBundle.name, WDA_XCTEST.path).path

        val wdaBundleRoot = if (isLocalHost) {
            wdaBundle.parentFile
        } else {
            remoteWdaBundleRoot
        }

        return File(wdaBundleRoot, wdaRunnerXctest)
    }
}
