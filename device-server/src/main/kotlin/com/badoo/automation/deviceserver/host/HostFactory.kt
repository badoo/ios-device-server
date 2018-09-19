package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.SimulatorHostChecker
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.RuntimeException

class HostFactory(
    private val remoteProvider: (hostName: String, userName: String, publicHost: String) -> IRemote =
            { hostName, userName, publicHostName -> Remote(hostName, userName, publicHostName) },
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
        logger.info("Obtaining node from $config.")
        try {
            return when {
                config.type == NodeConfig.NodeType.AndroidEmulators -> AndroidEmulatorsNode(
                        ticketHost = config.host,
                        emulatorLimit = config.simulatorLimit,
                        concurrentBoots = config.concurrentBoots
                )
                config.type == NodeConfig.NodeType.Simulators -> SimulatorsNode(
                        remote = remoteProvider(config.host, config.user, config.publicHost),
                        hostChecker = SimulatorHostChecker(
                                remoteProvider(config.host, config.user, config.publicHost),
                                wdaBundle = wdaSimulatorBundle,
                                remoteWdaBundleRoot = REMOTE_WDA_BUNDLE_ROOT,
                                fbsimctlVersion = fbsimctlVersion
                        ),
                        simulatorLimit = config.simulatorLimit,
                        concurrentBoots = config.concurrentBoots,
                        wdaRunnerXctest = getWdaRunnerXctest(
                                remoteProvider(config.host, config.user, config.publicHost).isLocalhost(),
                                wdaSimulatorBundle, REMOTE_WDA_BUNDLE_ROOT)
                )
                else -> DevicesNode(
                        remoteProvider(config.host, config.user, config.publicHost),
                        whitelistedApps = config.whitelistApps,
                        knownDevices = config.knownDevices,
                        uninstallApps = config.uninstallApps,
                        wdaBundlePath = wdaDeviceBundle,
                        remoteWdaBundleRoot = REMOTE_WDA_DEVICE_BUNDLE_ROOT,
                        wdaRunnerXctest = getWdaRunnerXctest(
                                remoteProvider(config.host, config.user, config.publicHost).isLocalhost(),
                                wdaDeviceBundle, REMOTE_WDA_DEVICE_BUNDLE_ROOT),
                        fbsimctlVersion = fbsimctlVersion
                )
            }
        }
        catch (e: Exception) {
            throw RuntimeException("Exception while creating a node from: ${config}", e)
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
