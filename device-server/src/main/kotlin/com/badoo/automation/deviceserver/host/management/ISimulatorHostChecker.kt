package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.XcodeVersion.Companion.REQUIRED_XCODE_VERSION
import com.badoo.automation.deviceserver.ios.simulator.periodicTasksPool
import com.badoo.automation.deviceserver.util.WdaSimulatorBundles
import com.badoo.automation.deviceserver.util.deleteRecursivelyIfExist
import com.badoo.automation.deviceserver.util.ensureDirectoryExists
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
}

class SimulatorHostChecker(
        val remote: IRemote,
        private val diskCleanupInterval: Duration = Duration.ofMinutes(15),
        private val wdaSimulatorBundles: WdaSimulatorBundles,
        private val remoteTestHelperAppRoot: File,
        private val shutdownSimulators: Boolean
) : ISimulatorHostChecker {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remote.hostName
    ))

    private lateinit var cleanUpTask: ScheduledFuture<*>
    private val applicationConfiguration = ApplicationConfiguration()

    override fun createDirectories() {
        with(applicationConfiguration.appBundleCachePath) {
            deleteRecursivelyIfExist(logger, logMarker)
            ensureDirectoryExists(logger, logMarker)
        }
    }

    override fun killDiskCleanupThread() {
        if (::cleanUpTask.isInitialized) {
            cleanUpTask.cancel(true)
        }
    }

    override fun checkPrerequisites() {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        logger.info(logMarker, "Using default Xcode version: ${xcodeOutput.stdOut.trim().replace("\n", " ")}")
        val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

        if (xcodeVersion < REQUIRED_XCODE_VERSION) {
            logger.error(logMarker, "Expecting Xcode $REQUIRED_XCODE_VERSION or higher, but it is $xcodeVersion")
        }
    }

    override fun cleanup() {
        try {
            logger.info(logMarker, "Will shutdown booted simulators")
            remote.fbsimctl.shutdownAllBooted()
            logger.info(logMarker, "Done shutting down booted simulators")
            logger.info(logMarker, "Will kill abandoned long living fbsimctl processes")
            remote.pkill(remote.fbsimctl.fbsimctlBinary, true)
            remote.pkill("fbsimctl", true)
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
        val runtimesResult: CommandResult = remote.exec("/usr/bin/xcrun simctl runtime list".split(" "), mapOf(), true, 600)

        if (runtimesResult.isSuccess) {
            logger.info(logMarker, "iOS Simulator Runtimes available: ${runtimesResult.stdErr.trim() + runtimesResult.stdOut.trim()}")
        } else {
            logger.error(logMarker, "Failed to get iOS Simulator Runtimes runtimes: ${runtimesResult.stdErr.trim() + runtimesResult.stdOut.trim()}")
        }

        // disable node hardware keyboard, i.e. use on-screen one
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false".split(" "))
        remote.execIgnoringErrors("/usr/bin/defaults write com.apple.iphonesimulator EnableKeyboardSync -bool false".split(" "))
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
