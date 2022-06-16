package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import com.badoo.automation.deviceserver.util.WdaSimulatorBundle
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface ISimulatorHostChecker {
    fun checkPrerequisites()
    fun createDirectories()
    fun cleanup()
    fun setupHost()
    fun killDiskCleanupThread()
    fun copyWdaBundleToHost()
    fun copyTestHelperBundleToHost()
    fun copyVideoRecorderHelperToHost()
}

class SimulatorHostChecker(
        val remote: IRemote,
        private val diskCleanupInterval: Duration = Duration.ofMinutes(15),
        private val wdaSimulatorBundle: WdaSimulatorBundle,
        private val remoteTestHelperAppRoot: File,
        private val fbsimctlVersion: String,
        private val shutdownSimulators: Boolean,
        private val remoteVideoRecorder: File
) : ISimulatorHostChecker {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remote.hostName
    ))

    private lateinit var cleanUpTask: ScheduledFuture<*>
    private val applicationConfiguration = ApplicationConfiguration()

    override fun createDirectories() {
        remote.shell("mkdir -p ${applicationConfiguration.simulatorBackupPath}")
    }
    override fun copyWdaBundleToHost() {
        logger.debug(logMarker, "Setting up remote node: copying WebDriverAgent to node ${remote.hostName}")
        val remoteBundleRoot = wdaSimulatorBundle.bundlePath(remote.isLocalhost()).parent
        remote.rm(remoteBundleRoot)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteBundleRoot))
        remote.scpToRemoteHost(wdaSimulatorBundle.bundlePath(true).absolutePath, remoteBundleRoot)
    }

    override fun copyTestHelperBundleToHost() {
        logger.debug(logMarker, "Setting up remote node: copying TestHelper app to node ${remote.hostName}")
        val testHelperAppBundle = File(remoteTestHelperAppRoot, "TestHelper.app")

        if (!testHelperAppBundle.exists()) {
            logger.error(logMarker, "Failed to copy TestHelper app to node ${remote.hostName}. TestHelper app does not exist")
            return
        }

        remote.rm(testHelperAppBundle.absolutePath)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteTestHelperAppRoot.absolutePath))
        remote.scpToRemoteHost(testHelperAppBundle.absolutePath, remoteTestHelperAppRoot.absolutePath)
    }

    override fun copyVideoRecorderHelperToHost() {
        logger.debug(logMarker, "Setting up remote node: Copying Video recorder helper to node ${remote.hostName}")

        if (!remoteVideoRecorder.exists()) {
            logger.error(logMarker, "Failed to copy Video recorder to node ${remote.hostName}. Video recorder does not exist")
            return
        }

        remote.rm(remoteVideoRecorder.absolutePath)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteVideoRecorder.parent))
        remote.scpToRemoteHost(remoteVideoRecorder.absolutePath, remoteVideoRecorder.absolutePath)
        remote.execIgnoringErrors(listOf("/bin/chmod", "555", remoteVideoRecorder.absolutePath))
    }

    override fun killDiskCleanupThread() {
        if (::cleanUpTask.isInitialized) {
            cleanUpTask.cancel(true)
        }
    }

    override fun checkPrerequisites() {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

        if (xcodeVersion < XcodeVersion(12, 1)) {
            throw RuntimeException("Expecting Xcode 12.1 or higher, but received $xcodeVersion. $xcodeOutput")
        }


        val fbsimctlPath = remote.execIgnoringErrors(listOf("readlink", remote.fbsimctl.fbsimctlBinary )).stdOut
        val match = Regex("/fbsimctl/([-.\\w]+)/bin/fbsimctl").find(fbsimctlPath)
                ?: throw RuntimeException("Could not read fbsimctl version from $fbsimctlPath")
        val actualFbsimctlVersion = match.groupValues[1]
        if (actualFbsimctlVersion != fbsimctlVersion) {
            throw RuntimeException("Expecting fbsimctl $fbsimctlVersion, but it was $actualFbsimctlVersion ${match.groupValues}")
        }
    }

    override fun cleanup() {
        try {
            logger.info(logMarker, "Will shutdown booted simulators")
            remote.fbsimctl.shutdownAllBooted()
            logger.info(logMarker, "Done shutting down booted simulators")
            logger.info(logMarker, "Will kill abandoned long living fbsimctl processes")
            remote.pkill(remote.fbsimctl.fbsimctlBinary, true)
        } catch (e: Exception) {
            logger.warn(logMarker, "Failed to shutdown simulator because: ${e.javaClass}: message: [${e.message}]")
        }

        try {
            logger.info(logMarker, "Will shutdown iproxy and socat")
            remote.pkill("/usl/local/bin/iproxy", true)
            remote.pkill("/opt/homebrew/bin/iproxy", true)
            remote.pkill("/usr/local/bin/socat", true)
            remote.pkill("/opt/homebrew/bin/socat", true)
        } catch (e: Exception) {
            logger.warn(logMarker, "Failed to shutdown simulator because: ${e.javaClass}: message: [${e.message}]")
        }

        if (shutdownSimulators) {
            cleanupSimulators()
            cleanupSimulatorServices()
        }

        val deviceSetsPath = remote.fbsimctl.defaultDeviceSet()
        check(!deviceSetsPath.isBlank()) { "Device sets must not be blank" } // fbsimctl.defaultDeviceSet will throw if empty. but paranoid mode on.

        removeOldFiles("/private/var/folders/*/*/*/app_bundle_cache.*", 0) // remove local caches
        removeOldFiles(ApplicationConfiguration().appBundleCacheRemotePath.absolutePath, 0) // remove local caches
        remote.shell("mkdir -p ${ApplicationConfiguration().appBundleCacheRemotePath.absolutePath}")

        // TODO: Use $TMPDIR instead of /private/var/folders/*/*/*
        val caches = listOf(
                "/var/folders/*/*/*/*-*-*/*.app",
                "/var/folders/*/*/*/fbsimctl-*",
                "/var/folders/*/*/*/videoRecording_*",
                "/var/folders/*/*/*/videoRecording_*",
                "/var/folders/*/*/*/derivedDataDir_*",
                "/var/folders/*/*/*/xctestRunDir_*",
                "/var/folders/*/*/*/device_agent_log_*",
                File(ApplicationConfiguration().appBundleCacheRemotePath.absolutePath, "*").absolutePath,
                "$deviceSetsPath/*/data/Library/Caches/com.apple.mobile.installd.staging/*/*.app"
        )

        caches.forEach { path ->
            removeOldFiles(path, 1)
        }

        val cleanUpRunnable = Runnable {
            caches.forEach { path ->
                removeOldFiles(path, 120)
            }
        }

        cleanUpTask = periodicTasksPool.scheduleWithFixedDelay(
                cleanUpRunnable,
                0,
                diskCleanupInterval.toMinutes(),
                TimeUnit.MINUTES)
    }

    private fun removeOldFiles(path: String, minutes: Int) {
        try {
            val r = remote.shell(
                "find $path -maxdepth 0 -mmin +$minutes -exec rm -rf {} \\;",
                returnOnFailure = true
            ) // find returns non zero if nothing found
            if (!r.isSuccess && r.exitCode != 1 && (r.stdErr.trim().isNotEmpty() || r.stdOut.trim().isNotEmpty())) {
                logger.debug(logMarker, "[disc cleaner] @ ${remote.publicHostName} returned non-empty. Result: ${r}")
            }
        } catch (e: RuntimeException) {
            logger.debug(logMarker, "[disc cleaner] $this got exception while cleaning caches: ${e.message}", e)
        }
    }

    private fun cleanupSimulators() {
        remote.pkill("Simulator.app", false) // Simulator UI application
        remote.pkill("launchd_sim", false) // main process for running simulator
    }

    private fun cleanupSimulatorServices() {
        val simulatorServices = listOf("CoreSimulatorService", "SimAudioProcessorService", "SimStreamProcessorService")
        simulatorServices.forEach {
            remote.pkill(it, true)
        }
    }

    override fun setupHost() {
        // disable node hardware keyboard, i.e. use on-screen one
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator PasteboardAutomaticSync -bool false".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator StartLastDeviceOnLaunch -bool false".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator DetachOnWindowClose -bool true".split(" "))

        // disable simulator location
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator LocationMode \"3101\"".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator ShowChrome -bool false".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator ShowSingleTouches -bool true".split(" "))
        //  other options that might be useful are:
        //  EnableKeyboardSync = 0;
        //  GraphicsQualityOverride = 10;
        //  OptimizeRenderingForWindowScale = 0;
        //  ShowChrome = 1;
        //  SlowMotionAnimation = 0;
    }
}
