package com.badoo.automation.deviceserver.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * bundlePath=/Users/qa/.iosctl/device-agent-runner/simulators/DeviceAgent-Runner.dev4.app
 * bundleId=sh.calaba.DeviceAgent.dev
 */
data class WdaSimulatorBundle(
    override val bundleId: String,
    private val bundlePath: Path, // /app/wda/DeviceAgent.app
    private val xctestRunnerPath: Path, // /app/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
    private val remoteBundlePath: Path, // /opt/wda/DeviceAgent.app
    private val remoteXctestRunnerPath: Path // /opt/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
) : WdaBundle {
    override fun xctestRunnerPath(isLocalhost: Boolean): File =
        if (isLocalhost) xctestRunnerPath.toFile() else remoteXctestRunnerPath.toFile()

    override fun bundlePath(isLocalhost: Boolean): File =
        if (isLocalhost) bundlePath.toFile() else remoteBundlePath.toFile()
}

data class WdaSimulatorBundles(
    val deviceAgentBundle: WdaSimulatorBundle,
    val webDriverAgentBundle: WdaSimulatorBundle
)

class WdaSimulatorBundlesProvider(
    private val wdaSimulatorBundlesBase: Path, private val remoteBundleRoot: Path
) {
    fun getWdaSimulatorBundles(): WdaSimulatorBundles {
        val bundlePaths: Set<Path> = Files.list(wdaSimulatorBundlesBase)
            .filter { Files.isDirectory(it) && it.fileName.toString().endsWith(".app") }
            .collect(Collectors.toSet())
        val deviceAgentBundlePath = bundlePaths.find { it.toString().contains("DeviceAgent-Runner") }
            ?: throw RuntimeException("Unable to find DeviceAgent-Runner at path $wdaSimulatorBundlesBase")
        val webDriverAgentBundlePath = bundlePaths.find { it.toString().contains("WebDriverAgentRunner-Runner") }
            ?: throw RuntimeException("Unable to find WebDriverAgentRunner-Runner at path $wdaSimulatorBundlesBase")

        return WdaSimulatorBundles(
            deviceAgentBundle = createWdaSimulatorBundle(deviceAgentBundlePath),
            webDriverAgentBundle = createWdaSimulatorBundle(webDriverAgentBundlePath)
        )
    }

    private fun createWdaSimulatorBundle(bundlePath: Path): WdaSimulatorBundle {
        val infoPlist = InfoPlist(bundlePath.resolve("Info.plist").toFile())
        val bundleId = infoPlist.bundleIdentifier()
        val remoteBundlePath = remoteBundleRoot.resolve(bundlePath.fileName)
        val xcTestPath: Path = if (bundlePath.toString().contains("DeviceAgent")) DA_XCTEST else WDA_XCTEST
        val xctestRunnerPath: Path = bundlePath.resolve(xcTestPath)
        val remoteXctestRunnerPath: Path = remoteBundlePath.resolve(xcTestPath)

        return WdaSimulatorBundle(bundleId, bundlePath, xctestRunnerPath, remoteBundlePath, remoteXctestRunnerPath)
    }

    companion object {
        val WDA_XCTEST = Paths.get("PlugIns/WebDriverAgentRunner.xctest")
        val DA_XCTEST = Paths.get("PlugIns/DeviceAgent.xctest")
    }
}
