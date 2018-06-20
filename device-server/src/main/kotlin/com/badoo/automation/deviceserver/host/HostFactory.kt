package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.Configuration
import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import org.slf4j.LoggerFactory
import java.io.File

class HostFactory(
        private val remoteProvider: (hostName: String, userName: String, publicHost: String) -> IRemote = { hostName, userName, publicHostName -> Remote(hostName, userName, publicHostName) },
        private val simulatorHostProvider: ISimulatorHostProvider = DefaultSimulatorHostProvider
) : IHostFactory {
    companion object {
        val WDA_BUNDLE = File(Configuration.WDA_BUNDLE_PATH).canonicalFile!! // can't be null. Configuration will blow up otherwise
        val WDA_DEVICE_BUNDLE = File(Configuration.WDA_DEVICE_BUNDLE_PATH).canonicalFile!!
        val WDA_XCTEST = File("PlugIns/WebDriverAgentRunner.xctest")
        const val REMOTE_WDA_BUNDLE_ROOT = "/tmp/web_driver_agent/"
        const val REMOTE_WDA_DEVICE_BUNDLE_ROOT = "/tmp/web_driver_agent_devices/"
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

        if (config.type == NodeConfig.NodeType.Simulators) {
            return simulatorHostProvider.simulatorsNode(
                remote,
                config.simulatorLimit,
                config.concurrentBoots,
                getWdaSimulatorsPath(remote.isLocalhost())
            )
        } else {
            return DevicesNode(
                remote,
                whitelistedApps = config.whitelistApps,
                wdaPath = getWdaDevicesPath(remote.isLocalhost()),
                knownDevices = config.knownDevices,
                uninstallApps = config.uninstallApps
            )
        }
    }

    private fun getWdaSimulatorsPath(isLocalhost: Boolean): File {
        return if (isLocalhost) {
            File(WDA_BUNDLE, WDA_XCTEST.path)
        } else {
            val xcTestPath = File(WDA_BUNDLE.name, WDA_XCTEST.path).path
            File(REMOTE_WDA_BUNDLE_ROOT, xcTestPath)
        }
    }

    private fun getWdaDevicesPath(isLocalhost: Boolean): File {
        return if (isLocalhost) {
            File(WDA_DEVICE_BUNDLE, WDA_XCTEST.path)
        } else {
            val xcTestPath = File(WDA_DEVICE_BUNDLE.name, WDA_XCTEST.path).path
            File(REMOTE_WDA_DEVICE_BUNDLE_ROOT, xcTestPath)
        }
    }
}
