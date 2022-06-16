package com.badoo.automation.deviceserver.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * bundlePath=/Users/qa/.iosctl/device-agent-runner/devices/DeviceAgent-Runner.dev4.app
 * bundleId=sh.calaba.DeviceAgent.dev
 * provisionedDevices=listOf("udid1", "udid2")
 */
data class WdaDeviceBundle(
    override val bundleId: String,
    private val bundlePath: Path, // /app/wda/DeviceAgent.app
    private val xctestRunnerPath: Path, // /app/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
    private val remoteBundlePath: Path, // /opt/wda/DeviceAgent.app
    private val remoteXctestRunnerPath: Path, // /opt/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
    val provisionedDevices: List<String>
) : WdaBundle {
    override fun xctestRunnerPath(isLocalhost: Boolean): File =
        if (isLocalhost) xctestRunnerPath.toFile() else remoteXctestRunnerPath.toFile()

    override fun bundlePath(isLocalhost: Boolean): File =
        if (isLocalhost) bundlePath.toFile() else remoteBundlePath.toFile()
}

class WdaDeviceBundlesProvider(private val wdaDeviceBundlesBase: Path, private val remoteWdaDeviceBundleRoot: Path) {
    fun getWdaDeviceBundles(): List<WdaDeviceBundle> {
        val bundlePaths: Set<Path> =
            Files.list(wdaDeviceBundlesBase).filter { Files.isDirectory(it) && it.fileName.toString().endsWith(".app") }
                .collect(Collectors.toSet())

        val wdaDeviceBundles = bundlePaths.map { bundlePath ->
            val infoPlist = InfoPlist(bundlePath.resolve("Info.plist").toFile())
            val provisioningProfile = ProvisioningProfile(bundlePath.resolve("embedded.mobileprovision").toFile())
            val provisionedDevices = provisioningProfile.provisionedDevices()
            val bundleId = infoPlist.bundleIdentifier()
            val remoteBundlePath = remoteWdaDeviceBundleRoot.resolve(bundlePath.fileName)
            val xcTestPath: Path = if (bundlePath.toString().contains("DeviceAgent")) DA_XCTEST else WDA_XCTEST
            val xctestRunnerPath: Path = bundlePath.resolve(xcTestPath)
            val remoteXctestRunnerPath: Path = remoteBundlePath.resolve(xcTestPath)

            WdaDeviceBundle(
                bundleId,
                bundlePath,
                xctestRunnerPath,
                remoteBundlePath,
                remoteXctestRunnerPath,
                provisionedDevices
            )
        }

        return wdaDeviceBundles
    }

    companion object {
        val WDA_XCTEST = Paths.get("PlugIns/WebDriverAgentRunner.xctest")
        val DA_XCTEST = Paths.get("PlugIns/DeviceAgent.xctest")
    }
}
