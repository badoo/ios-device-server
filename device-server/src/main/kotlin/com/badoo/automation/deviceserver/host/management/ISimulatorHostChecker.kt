package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.XcodeVersion.Companion.REQUIRED_XCODE_VERSION
import com.badoo.automation.deviceserver.simctl.SimCtlUtility.Companion.SIMCTL_LIST_DEVICES_JSON
import com.badoo.automation.deviceserver.simctl.SimCtlUtility.Companion.SIMCTL_LIST_DEVICE_TYPES_JSON
import com.badoo.automation.deviceserver.simctl.SimCtlUtility.Companion.SIMCTL_LIST_RUNTIMES_JSON
import com.badoo.automation.deviceserver.util.deleteRecursivelyIfExist
import com.badoo.automation.deviceserver.util.ensureDirectoryExists
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
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
    private val shutdownSimulators: Boolean
) : ISimulatorHostChecker {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

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
        logger.info(logMarker, "Checking default Xcode version:")
        val xcodeOutput = remote.exec(listOf("/usr/bin/xcodebuild", "-version"), mapOf(), true, 180)

        if (xcodeOutput.isSuccess) {
            logger.info(logMarker, "Using default Xcode version: ${xcodeOutput.stdOut.trim().replace("\n", " ")}")

            val xcodeVersion = XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)

            if (xcodeVersion < REQUIRED_XCODE_VERSION) {
                logger.error(logMarker, "Expecting Xcode $REQUIRED_XCODE_VERSION or higher, but it is $xcodeVersion")
            }
        } else {
            logger.error(logMarker, "Failed to get Xcode version: ${xcodeOutput.stdErr.trim() + xcodeOutput.stdOut.trim()}")
            throw IllegalStateException("Failed to get Xcode version: ${xcodeOutput.stdErr.trim() + xcodeOutput.stdOut.trim()}")
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
        remote.exec(listOf("/usr/bin/xcrun", "simctl", "shutdown", "all"), mapOf(), true, 60)
        remote.pkill("Simulator.app", false) // Simulator UI application
    }

    private fun cleanupSimulatorServices() {
        val simulatorServices = listOf("CoreSimulatorService", "SimAudioProcessorService", "SimStreamProcessorService")
        simulatorServices.forEach {
            remote.pkill(it, true)
        }
    }

    private fun ensureXodeSimulatorRuntimesWork() {
        listOf(
            SIMCTL_LIST_RUNTIMES_JSON,
            SIMCTL_LIST_DEVICE_TYPES_JSON,
            SIMCTL_LIST_DEVICES_JSON
        ).forEach { commandString ->
            val simplifiedCommand = commandString.replace("--json", "").trim()
            logger.info(logMarker, "Executing command \"$simplifiedCommand\" to ensure Xcode simulator runtimes work. This may take significant time.")
            val startTime = System.nanoTime()
            val command = simplifiedCommand.split(" ").map { it.trim() }
            val result: CommandResult = remote.exec(command, mapOf(), true, 600) // huge timeout to allow for Xcode to start up and list runtimes
            val elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

            if (result.isSuccess) {
                logger.info(logMarker, "Xcode command \"${simplifiedCommand}\" executed successfully:\n${result.stdErr.trim() + result.stdOut.trim()}")
            } else {
                logger.error(
                    logMarker,
                    "Xcode command \"${simplifiedCommand}\" failed. Exit code: ${result.exitCode}:\nSTDERR:\n${result.stdErr.trim()}\nSTDOUT:\n${result.stdOut.trim()}"
                )
            }
        }
    }

    override fun setupHost() {
        ensureXodeSimulatorRuntimesWork()

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
