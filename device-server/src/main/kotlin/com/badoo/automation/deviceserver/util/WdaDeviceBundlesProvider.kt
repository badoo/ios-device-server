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
    override val bundleName: String,
    private val bundlePath: Path, // /app/wda/DeviceAgent.app
    private val xctestRunnerPath: Path, // /app/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
    private val remoteBundlePath: Path, // /opt/wda/DeviceAgent.app
    private val remoteXctestRunnerPath: Path, // /opt/wda/DeviceAgent.app/PlugIns/WebDriverAgentRunner.xctest
    override val provisionedDevices: List<String>,
    override val deviceInstrumentationPort: Int,
    override val testIdentifier: String
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
            val bundleName = infoPlist.bundleName()
            val remoteBundlePath = remoteWdaDeviceBundleRoot.resolve(bundlePath.fileName)
            val xcTestPath: Path = if (bundleId.contains("DeviceAgent")) DA_XCTEST else WDA_XCTEST
            val deviceInstrumentationPort: Int = if (bundleId.contains("DeviceAgent")) DA_PORT else WDA_PORT
            val xctestRunnerPath: Path = bundlePath.resolve(xcTestPath)
            val remoteXctestRunnerPath: Path = remoteBundlePath.resolve(xcTestPath)
            val testIdentifier: String = if (bundleId.contains("DeviceAgent")) "TestRunner/testRunner" else "UITestingUITests/testRunner"

            WdaDeviceBundle(
                bundleId,
                bundleName,
                bundlePath,
                xctestRunnerPath,
                remoteBundlePath,
                remoteXctestRunnerPath,
                provisionedDevices,
                deviceInstrumentationPort,
                testIdentifier
            )
        }

        return wdaDeviceBundles
    }

    companion object {
        private val WDA_XCTEST = Paths.get("PlugIns/WebDriverAgentRunner.xctest")
        private val DA_XCTEST = Paths.get("PlugIns/DeviceAgent.xctest")
        private const val WDA_PORT = 8100
        private const val DA_PORT = 27753
    }
}
