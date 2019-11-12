package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface ISimulatorHostChecker {
    fun checkPrerequisites()
    fun cleanup()
    fun setupHost()
    fun killDiskCleanupThread()
    fun copyWdaBundleToHost()
    val isWdaBundleDeployed: Boolean
}

class SimulatorHostChecker(
        val remote: IRemote,
        private val diskCleanupInterval: Duration = Duration.ofMinutes(15),
        private val wdaBundle: File,
        private val remoteWdaBundleRoot: File,
        private val fbsimctlVersion: String,
        private val shutdownSimulators: Boolean
) : ISimulatorHostChecker {
    override val isWdaBundleDeployed: Boolean
        get() = remote.execIgnoringErrors(listOf("test", "-d", remoteWdaBundleRoot.absolutePath)).isSuccess

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remote.hostName
    ))

    private lateinit var cleanUpTask: ScheduledFuture<*>

    override fun copyWdaBundleToHost() {
        logger.debug(logMarker, "Setting up remote node: copying WebDriverAgent to node ${remote.hostName}")
        remote.rm(remoteWdaBundleRoot.absolutePath)
        remote.execIgnoringErrors(listOf("/bin/mkdir", "-p", remoteWdaBundleRoot.absolutePath))
        remote.scp(wdaBundle.absolutePath, remoteWdaBundleRoot.absolutePath)
    }

    override fun killDiskCleanupThread() {
        if (::cleanUpTask.isInitialized) {
            cleanUpTask.cancel(true)
        }
    }

    override fun checkPrerequisites() {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

        if (xcodeVersion < XcodeVersion(9, 0)) {
            throw RuntimeException("Expecting Xcode 9 or higher, but received $xcodeVersion. $xcodeOutput")
        }

        // temp solution, prereq should be satisfied without having to switch anything
        val rv = remote.execIgnoringErrors(listOf("/usr/local/bin/brew", "switch", "fbsimctl", fbsimctlVersion), env = mapOf("RUBYOPT" to ""))
        if (!rv.isSuccess) {
            logger.warn(logMarker, "fbsimctl switch failed, see: $rv")
        }

        val fbsimctlPath = remote.execIgnoringErrors(listOf("readlink", FBSimctl.FBSIMCTL_BIN )).stdOut
        val match = Regex("/fbsimctl/([-.\\w]+)/bin/fbsimctl").find(fbsimctlPath)
                ?: throw RuntimeException("Could not read fbsimctl version from $fbsimctlPath")
        val actualFbsimctlVersion = match.groupValues[1]
        if (actualFbsimctlVersion != fbsimctlVersion) {
            throw RuntimeException("Expecting fbsimctl $fbsimctlVersion, but it was $actualFbsimctlVersion ${match.groupValues}")
        }
    }

    override fun cleanup() {
        if (shutdownSimulators) {
            cleanupSimulators()
            cleanupSimulatorServices()
        }

        try {
            logger.info(logMarker, "Will kill abandoned long living fbsimctl processes")
            remote.pkill("/usr/local/bin/fbsimctl", true)
            logger.info(logMarker, "Will shutdown booted simulators")
            remote.fbsimctl.shutdownAllBooted()
            logger.info(logMarker, "Done shutting down booted simulators")
        } catch (e: Exception) {
            logger.warn(logMarker, "Failed to shutdown simulator because: ${e.javaClass}: message: [${e.message}]")
        }

        val deviceSetsPath = remote.fbsimctl.defaultDeviceSet()
        check(!deviceSetsPath.isBlank()) { "Device sets must not be blank" } // fbsimctl.defaultDeviceSet will throw if empty. but paranoid mode on.

        removeOldFiles("/private/var/folders/*/*/*/app_bundle_cache.*", 0) // remove local caches
        removeOldFiles(ApplicationConfiguration().appBundleCacheRemotePath, 0) // remove local caches

        // TODO: Use $TMPDIR instead of /private/var/folders/*/*/*
        val caches = listOf(
                "/private/var/folders/*/*/*/*-*-*/*.app",
                "/private/var/folders/*/*/*/fbsimctl-*",
                "/var/folders/*/*/*/videoRecording_*",
                "/private/var/folders/*/*/*/videoRecording_*",
                "$deviceSetsPath/*/data/Library/Caches/com.apple.mobile.installd.staging/*/*.app"
        )

        val cleanUpRunnable: Runnable = Runnable {
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
            if (!r.isSuccess || r.stdErr.isNotEmpty() || r.stdOut.isNotEmpty()) {
                logger.debug(logMarker, "[disc cleaner] $this returned non-empty. Result stdErr: ${r.stdErr}")
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
        remote.execIgnoringErrors("defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false".split(" "))

        // disable simulator location
        remote.execIgnoringErrors("defaults write com.apple.iphonesimulator LocationMode \"3101\"".split(" "))
        remote.execIgnoringErrors("defaults write com.apple.iphonesimulator ShowChrome -bool false".split(" "))
        //  other options that might be useful are:
        //  EnableKeyboardSync = 0;
        //  GraphicsQualityOverride = 10;
        //  OptimizeRenderingForWindowScale = 0;
        //  ShowChrome = 1;
        //  SlowMotionAnimation = 0;
    }
}