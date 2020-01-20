package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.errors.DeviceCreationException
import com.badoo.automation.deviceserver.ios.proc.FbsimctlProc
import com.badoo.automation.deviceserver.ios.proc.IWebDriverAgent
import com.badoo.automation.deviceserver.ios.proc.SimulatorWebDriverAgent
import com.badoo.automation.deviceserver.ios.proc.SimulatorXcrunWebDriverAgent
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackupError
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.FileSystem
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.executeWithTimeout
import com.badoo.automation.deviceserver.util.pollFor
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Runnable
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.*
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureNanoTime

class Simulator(
        private val deviceRef: DeviceRef,
        private val remote: IRemote,
        override val deviceInfo: DeviceInfo,
        private val allocatedPorts: DeviceAllocatedPorts,
        private val deviceSetPath: String,
        wdaRunnerXctest: File,
        private val concurrentBootsPool: ExecutorService,
        headless: Boolean,
        private val useWda: Boolean,
        private val trustStoreFile: String = ApplicationConfiguration().trustStorePath,
        private val assetsPath: String = ApplicationConfiguration().assetsPath
) : ISimulator
{
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
    }

    override val ref = deviceRef
    override val udid: UDID = deviceInfo.udid
    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val userPorts = allocatedPorts
    override val calabashPort: Int = allocatedPorts.calabashPort
    override val mjpegServerPort: Int = allocatedPorts.mjpegServerPort

    private fun createVideoRecorder(): VideoRecorder {
        val recorderClassName = ApplicationConfiguration().videoRecorderClassName

        return when (recorderClassName) {
            SimulatorVideoRecorder::class.qualifiedName -> SimulatorVideoRecorder(
                deviceInfo,
                remote,
                location = Paths.get(deviceSetPath, udid, "video.mp4").toFile()
            )
            MJPEGVideoRecorder::class.qualifiedName -> MJPEGVideoRecorder(
                deviceInfo,
                remote,
                wdaEndpoint,
                mjpegServerPort,
                ref,
                udid
            )
            else -> throw IllegalArgumentException(
                "Wrong class specified as video recorder: $recorderClassName. " +
                        "Available are: [${SimulatorVideoRecorder::class.qualifiedName}, ${MJPEGVideoRecorder::class.qualifiedName}]"
            )
        }
    }
    override val videoRecorder: VideoRecorder = createVideoRecorder()

    override val systemLog = SystemLog(remote, udid)
    override val osLog = OsLog(remote, udid)

    //region instance state variables
    private val deviceLock = ReentrantLock()
    @Volatile override var deviceState: DeviceState = DeviceState.NONE // writing from separate thread
        private set

    @Volatile override var lastException: Exception? = null // writing from separate thread
        private set

    private val fbsimctlProc: FbsimctlProc = FbsimctlProc(remote, deviceInfo.udid, fbsimctlEndpoint, headless)
    private val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)

    private val webDriverAgent: IWebDriverAgent

    init {
        val wdaClassName = ApplicationConfiguration().simulatorWdaClassName

        webDriverAgent = when (wdaClassName) {
            SimulatorWebDriverAgent::class.qualifiedName -> SimulatorWebDriverAgent(
                remote,
                wdaRunnerXctest,
                deviceInfo.udid,
                wdaEndpoint,
                mjpegServerPort,
                deviceRef
            )
            SimulatorXcrunWebDriverAgent::class.qualifiedName -> SimulatorXcrunWebDriverAgent(
                remote,
                wdaRunnerXctest,
                deviceInfo.udid,
                wdaEndpoint,
                mjpegServerPort,
                deviceRef
            )
            else -> throw IllegalArgumentException(
                "Wrong class specified as WDA for Simulator: $wdaClassName. " +
                        "Available are: [${SimulatorWebDriverAgent::class.qualifiedName}, ${SimulatorXcrunWebDriverAgent::class.qualifiedName}]"
            )
        }
    }

    private val simulatorDirectory = File(deviceSetPath, udid)
    private val simulatorDataDirectory = File(simulatorDirectory, "data")

    private val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory)
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val commonLogMarkerDetails = mapOf(
            LogMarkers.DEVICE_REF to deviceRef,
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    )
    private val logMarker: Marker = MapEntriesAppendingMarker(commonLogMarkerDetails)
    private val fileSystem = FileSystem(remote, udid)
    @Volatile private var healthChecker: Job? = null
    //endregion

    override val media: Media = Media(remote, udid, deviceSetPath)

    override fun toString() = "<Simulator: $deviceRef>"

    @Volatile
    private var bootTask: Future<*>? = null
    @Volatile
    private var installTask: Future<Boolean>? = null

    override fun installApplication(
        appInstaller: AppInstaller,
        appBundleId: String,
        appBinaryPath: File
    ) {
        deviceLock.withLock {
            installTask?.let { oldInstallTask ->
                if (!oldInstallTask.isDone) {
                    val message = "Failed to install app $appBundleId to simulator $udid due to previous task is not finished"
                    logger.error(logMarker, message)
                    throw RuntimeException(message)
                }
            }

            installTask = appInstaller.installApplication(udid, appBundleId, appBinaryPath)
        }
    }

    override fun appInstallationStatus(): Map<String, Boolean> {
        val task = installTask
        val status = mapOf<String, Boolean>(
            "task_exists" to (task != null),
            "task_complete" to (task != null && task.isDone),
            "success" to (task != null && task.isDone && task.get())
        )
        return status
    }

    //region prepareAsync
    override fun prepareAsync() {
        executeCritical {
            if (deviceState == DeviceState.CREATING || deviceState == DeviceState.RESETTING) {
                throw java.lang.IllegalStateException("Simulator $udid is already in state $deviceState")
            }
            deviceState = DeviceState.CREATING

            val nanos = measureNanoTime {
                try {
                    shutdown()
                    prepare(clean = true)
                } catch (e: Exception) { // catching most wide exception
                    deviceState = DeviceState.FAILED
                    logger.error(logMarker, "Failed to prepare device ${this@Simulator}", e)
                    shutdown()
                    throw e
                }
            }

            val seconds = NANOSECONDS.toSeconds(nanos)
            val measurement = mutableMapOf(
                "action_name" to "prepareAsync",
                "duration" to seconds
            )
            measurement.putAll(commonLogMarkerDetails)

            logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} ready in $seconds seconds")
        }
    }

    private fun prepare(timeout: Duration = PREPARE_TIMEOUT, clean: Boolean) {
        logger.info(logMarker, "Starting to prepare ${this@Simulator}. Will wait for ${timeout.seconds} seconds")
        lastException = null

        //FIXME: add checks for cancellation of criticalAsyncPromise
        executeWithTimeout(timeout, "Preparing simulator") {
            // erase simulator if there is no existing backup, this is to ensure backup is created from a clean state
            logger.info(logMarker, "Launch prepare sequence for ${this@Simulator} asynchronously")

            if (backup.isExist()) {
                if (clean) {
                    try {
                        backup.restore()
                    } catch (e: SimulatorBackupError) {
                        logger.warn(logMarker, "Will erase simulator and re-create backup for ${this@Simulator}")
                        shutdown()
                        backup.delete()
                        eraseSimulatorAndCreateBackup()
                    }
                }
            } else {
                eraseSimulatorAndCreateBackup()
            }

            logTiming("simulator boot") { boot() }

            fbsimctlProc.start()

            if (useWda) {
                logTiming("starting WebDriverAgent") { startWdaWithRetry() }
            }

            logger.info(logMarker, "Finished preparing $this")
            startPeriodicHealthCheck()
            deviceState = DeviceState.CREATED
        }
    }

    private fun startPeriodicHealthCheck() {
        stopPeriodicHealthCheck()

        var fbsimctlFailCount = 0
        var wdaFailCount = 0
        val maxFailCount = 3
        val healthCheckInterval = Duration.ofSeconds(15).toMillis()

        healthChecker = launch {
            while (isActive) {
                performFBSimctlHealthCheck(fbsimctlFailCount, maxFailCount)
                performWebDriverAgentHealthCheck(wdaFailCount, maxFailCount)
                delay(healthCheckInterval)
            }
        }
    }

    private suspend fun performWebDriverAgentHealthCheck(wdaFailCount: Int, maxFailCount: Int) {
        var wdaFailCount1 = wdaFailCount
        if (webDriverAgent.isHealthy()) {
            wdaFailCount1 = 0
        } else {
            (1..5).forEach {
                if (webDriverAgent.isHealthy()) {
                    wdaFailCount1 = 0
                    return@forEach
                } else {
                    val message = "WebDriverAgent health check failed $wdaFailCount1 times."
                    logger.error(logMarker, message)
                    wdaFailCount1 += 1
                    delay(Duration.ofSeconds(2).toMillis())
                }
            }

            if (wdaFailCount1 >= maxFailCount) {
                logger.error(logMarker, "WebDriverAgent health check failed $wdaFailCount1 times. Restarting WebDriverAgent")

                try {
                    webDriverAgent.kill()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to kill WebDriverAgent. ${e.message}", e)
                }

                try {
                    webDriverAgent.start()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to restart WebDriverAgent. ${e.message}", e)
                    deviceState = DeviceState.FAILED
                    throw RuntimeException("${this@Simulator} Failed to restart WebDriverAgent. Stopping health check")
                }
            }
        }
    }

    private suspend fun performFBSimctlHealthCheck(fbsimctlFailCount: Int, maxFailCount: Int) {
        var fbsimctlFailCount1 = fbsimctlFailCount
        if (fbsimctlProc.isHealthy()) {
            fbsimctlFailCount1 = 0
        } else {
            (1..5).forEach {
                if (fbsimctlProc.isHealthy()) {
                    fbsimctlFailCount1 = 0
                    return@forEach
                } else {
                    val message = "Fbsimctl health check failed $fbsimctlFailCount1 times."
                    logger.error(logMarker, message)
                    fbsimctlFailCount1 += 1
                    delay(Duration.ofSeconds(2).toMillis())
                }
            }

            if (fbsimctlFailCount1 >= maxFailCount) {
                logger.error(logMarker, "Fbsimctl health check failed $fbsimctlFailCount1 times. Restarting fbsimctl")

                try {
                    fbsimctlProc.kill()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to kill Fbsimctl. ${e.message}", e)
                }

                try {
                    fbsimctlProc.start()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to restart Fbsimctl. ${e.message}", e)
                    deviceState = DeviceState.FAILED
                    throw RuntimeException("${this@Simulator} Failed to restart WebDriverAgent. Stopping health check")
                }
            }
        }
    }

    private fun stopPeriodicHealthCheck() {
        healthChecker?.let { checker ->
            checker.cancel()
            while (checker.isActive) {
                Thread.sleep(100)
            }
        }
    }

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(10), retryInterval: Duration = Duration.ofSeconds(1)) {
        val maxRetries = 7

        for (attempt in 1..maxRetries) {
            try {
                logger.info(logMarker, "Starting WebDriverAgent on ${this@Simulator}")

                webDriverAgent.kill()
                webDriverAgent.start()

                Thread.sleep(2000)

                pollFor(
                    pollTimeout,
                    reasonName = "${this@Simulator} WebDriverAgent health check",
                    retryInterval = retryInterval,
                    logger = logger,
                    marker = logMarker
                ) {
                    webDriverAgent.isHealthy()
                }

                logger.info(logMarker, "Started WebDriverAgent on ${this@Simulator}")

                return
            }
            catch (e: RuntimeException) {
                logger.warn(logMarker, "Attempt $attempt to start WebDriverAgent for ${this@Simulator} failed: $e")
                if (attempt == maxRetries) {
                    throw e
                }
            }
        }
    }

    private fun eraseSimulatorAndCreateBackup() {
        logger.info(logMarker, "Erasing simulator ${this@Simulator} before creating a backup")
        remote.fbsimctl.eraseSimulator(udid)

        if (trustStoreFile.isNotEmpty()) {
            copyTrustStore()
        }

        logger.info(logMarker, "Booting ${this@Simulator} before creating a backup")
        logTiming("initial boot") { boot() }

        val osVersion = Regex("[0-9.]+").find(deviceInfo.os)?.value?.toFloat()
        if (osVersion != null && osVersion >= 13) {
            logger.info(logMarker, "Saving Preference that Continuous Path Introduction was shown")
            writeSimulatorDefaults("com.apple.Preferences DidShowContinuousPathIntroduction -bool true")
        }

        if (assetsPath.isNotEmpty()) {
            copyMediaAssets()
        }

        if (useWda) {
            webDriverAgent.installHostApp()
        }

        logger.info(logMarker, "Shutting down ${this@Simulator} before creating a backup")
        shutdown()

        backup.create()
    }

    private fun copyTrustStore() {
        logger.debug(logMarker, "Copying trust store to ${this@Simulator}")
        val keyChainLocation = Paths.get(deviceSetPath, udid, "data", "Library", "Keychains").toFile().absolutePath
        remote.shell("mkdir -p $keyChainLocation", returnOnFailure = false)

        if (remote.isLocalhost()) {
            remote.shell("cp $trustStoreFile $keyChainLocation", returnOnFailure = false)
        } else {
            remote.scpToRemoteHost(trustStoreFile, keyChainLocation)
        }

        logger.info(logMarker, "Copied trust store to ${this@Simulator}")
    }

    private fun copyMediaAssets() {
        logger.debug(logMarker, "Copying assets to ${this@Simulator}")
        media.reset()
        File(assetsPath).walk().filter { it.isFile }.forEach {
           media.addMedia(it, it.readBytes())
        }

        logger.info(logMarker, "Copied assets to ${this@Simulator}")
    }

    private fun listDevices(): String {
        return remote.shell("/usr/bin/xcrun simctl list devices", returnOnFailure = true).stdOut
    }

    private fun isSimulatorShutdown(): Boolean {
        return listDevices().lines().find { it.contains(udid) && it.contains("(Shutdown)") } != null
    }

    private fun shutdown() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")
        stopPeriodicHealthCheck()
        bootTask?.cancel(true)
        installTask?.cancel(true)
        ignoringErrors({ videoRecorder.stop() })
        ignoringErrors({ webDriverAgent.kill() })
        ignoringErrors({ fbsimctlProc.kill() })

        val result = remote.fbsimctl.shutdown(udid)

        if (!result.isSuccess && !result.stdErr.contains("current state: Shutdown") && !result.stdOut.contains("current state: Shutdown")) {
            logger.debug(logMarker, "Error occured while shutting down simulator $udid. Command exit code: ${result.exitCode}. Result stdErr: ${result.stdErr}")
        }

        pollFor(
            timeOut = Duration.ofSeconds(90),
            retryInterval = Duration.ofSeconds(5),
            reasonName = "${this@Simulator} to shutdown",
            logger = logger,
            marker = logMarker
        ) {
            isSimulatorShutdown()
        }

        logger.info(logMarker, "Successfully shut down ${this@Simulator}")
    }
    
    private fun disabledServices(): List<String> {
        val cmdLine = listOf(
            "com.apple.bird",
            "com.apple.homed",
            "com.apple.SafariBookmarksSyncAgent",
//            "com.apple.itunesstored",
            "com.apple.assistant_service",
            "com.apple.cloudd",
            "com.apple.carkitd",
            "com.apple.ap.adprivacyd",
            "com.apple.siri.ClientFlow.ClientScripter",
            "com.apple.healthd",
            "com.apple.mobileassetd",
             "com.apple.accessibility.AccessibilityUIServer",
            "com.apple.siri.context.service",
            "com.apple.remindd",
            "com.apple.searchd",
            "com.apple.voiced",
            "com.apple.telephonyutilities.callservicesd",
            "com.apple.WebBookmarks.webbookmarksd",
            "com.apple.siriactionsd",
            "com.apple.healthappd",
            "com.apple.familynotification",
            "com.apple.navd",
            "com.apple.assistantd",
            "com.apple.companionappd",
            "com.apple.email.maild",
            "com.apple.Maps.mapspushd",
            "com.apple.addressbooksyncd",
            "com.apple.dataaccess.dataaccessd",
            "spotlight",
            "Spotlight",
            "Spotlight.app",
            "UIKitApplication:com.apple.Spotlight",
            "com.apple.Spotlight",
            "com.apple.corespotlightservice",
            "com.apple.NPKCompanionAgent",
            "com.apple.avatarsd",
            "com.apple.coreservices.useractivityd",
            "com.apple.pairedsyncd",
            "com.apple.mobiletimerd",
            "com.apple.mobilecal",
            "com.apple.photoanalysisd",
            "com.apple.suggestd",
            "com.apple.purplebuddy.budd",
//            "com.apple.passd",
            "com.apple.corespeechd",
//            "com.apple.appstored",
            "com.apple.calaccessd"
        ).map {
            "--disabledJob=$it"
        }

        return cmdLine
    }

    private fun bootSimulator() {
        val cmd = listOf("/usr/bin/xcrun", "simctl", "boot", udid) + disabledServices()
        remote.exec(cmd, mapOf(), false, 60L)
    }

    private fun boot() {
        logger.info(logMarker, "Booting ${this@Simulator} asynchronously")
        val nanos = measureNanoTime {
            val task = concurrentBootsPool.submit { // using limited amount of workers to boot simulator
                bootSimulator()
                waitUntilSimulatorBooted()
            }
            bootTask = task
            task.get()
        }
        val timingMarker = MapEntriesAppendingMarker(commonLogMarkerDetails + mapOf("simulatoBootTime" to NANOSECONDS.toSeconds(nanos)))
        logger.info(timingMarker, "Device ${this@Simulator} is sufficiently booted")
    }

    sealed class RequiredService(val identifier: String, @Volatile var booted: Boolean = false) {
        class SpringBoard() : RequiredService("com.apple.SpringBoard")
        class TextInput() : RequiredService("com.apple.TextInput.kbd")
        class AccessibilityUIServer() : RequiredService("com.apple.accessibility.AccessibilityUIServer")
        class Spotlight() : RequiredService("com.apple.Spotlight")
        class SpotlightIos12() : RequiredService("SpotlightIndex")
        class Locationd() : RequiredService("com.apple.locationd")

        override fun toString(): String {
            return identifier
        }
    }

    val failedExitCodes = listOf(
        // 143 - terminated
        164, // Invalid device (not existing)
        165 // Invalid device state (not Booted)
    )

    private fun waitUntilSimulatorBooted() {
        val predicate = if (remote.isLocalhost()) {
            "eventMessage contains 'Bootstrap success' OR (eventMessage contains 'LaunchServices' AND eventMessage contains 'registering extension')"
        } else {
            "\"eventMessage contains 'Bootstrap success' OR (eventMessage contains 'LaunchServices' AND eventMessage contains 'registering extension')\""
        }

        val simulatorBootTimeOutMinutes = 2
        val cmd = mutableListOf(
            "/usr/bin/xcrun", "simctl", "spawn", udid, "log", "stream",
            "--timeout", "${simulatorBootTimeOutMinutes}m",
            "--color", "none",
            "--level", "info")

        if (deviceInfo.os.contains("iOS 13")) {
            cmd.add("--process")
            cmd.add("SpringBoard")
        }

        cmd.add("--predicate")
        cmd.add(predicate)

        val requiredServices = mutableListOf<RequiredService>()

        if (deviceInfo.os.contains("iOS 12")) {
            requiredServices.add(RequiredService.SpotlightIos12())
        } else {
            requiredServices.add(RequiredService.Spotlight())
        }

        val process = remote.remoteExecutor.startProcess(cmd, mapOf(), logMarker)

        val stdOut = StringBuilder()
        val stdErr = StringBuilder()

        val outReader: ((line: String) -> Unit) = { line: String ->
            stdOut.append(line)
            stdOut.append("\n")

            requiredServices.forEach { service ->
                if (line.contains(service.identifier)) {
                    service.booted = true
                }
            }

            if (requiredServices.all { it.booted }) {
                process.destroy()
            }
        }

        val errReader: ((line: String) -> Unit) = { line: String ->
            stdErr.append(line)
            stdErr.append("\n")
        }

        val executor = Executors.newFixedThreadPool(2)
        executor.submit(lineReader(process.inputStream, outReader))
        executor.submit(lineReader(process.errorStream, errReader))
        executor.shutdown()

        val finishedInTime = process.waitFor(simulatorBootTimeOutMinutes*60L + 15L, TimeUnit.SECONDS)
        val exitCode = if (finishedInTime) process.exitValue() else -1

        if (failedExitCodes.any { exitCode == it }) {
            val errorMessage = "Simulator $udid failed to boot. Exit code: ${process.exitValue()}. StdErr: $stdErr. StdOut: $stdOut"
            logger.error(logMarker, errorMessage)
            throw DeviceCreationException(errorMessage)
        }

        if (requiredServices.any { !it.booted }) {
            val failedServices = requiredServices.filter { !it.booted }
            val failedServicesMessage = if (failedServices.isNotEmpty()) "Failed services [${failedServices.joinToString(", ")}]" else ""
            val errorMessage = "Simulator $udid failed to successfully boot to sufficient state. $failedServicesMessage. Exit code: $exitCode. StdErr: $stdErr. StdOut: $stdOut"
            logger.error(logMarker, errorMessage)
            throw DeviceCreationException(errorMessage)
        }

        if (!finishedInTime) {
            logger.error(logMarker, "Simulator $udid log has not exited in time. Possible errors. Exit code: $exitCode. StdErr: $stdErr. StdOut: $stdOut")
        }
    }

    private fun lineReader(inputStream: InputStream, readerProc: ((line: String) -> Unit)): java.lang.Runnable {
        return Runnable {
            inputStream.use { stream ->
                val inputStreamReader = InputStreamReader(stream, StandardCharsets.UTF_8)
                val reader = BufferedReader(inputStreamReader, 65356)
                var line: String? = reader.readLine()

                while (line != null) {
                    readerProc.invoke(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun writeSimulatorDefaults(setting: String) {
        remote.shell("/usr/bin/xcrun simctl spawn $udid defaults write $setting", true)
    }

    private fun readSimulatorDefaults(): String {
        return remote.execIgnoringErrors("/usr/bin/xcrun simctl spawn $udid defaults read".split(" ")).stdOut
    }

    private fun logTiming(actionName: String, action: () -> Unit) {
        logger.info(logMarker, "Device ${this@Simulator} starting action <$actionName>")
        val nanos = measureNanoTime(action)
        val seconds = NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
                "action_name" to actionName,
                "duration" to seconds
        )
        measurement.putAll(commonLogMarkerDetails)
        logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} action <$actionName> took $seconds seconds")
    }
    //endregion

    //region reset async
    override fun resetAsync(): Runnable {
        val state = deviceState
        if (state != DeviceState.CREATED && state != DeviceState.FAILED) {
            val message = "Unable to perform reset. Simulator $udid is in state $state"
            logger.error(logMarker, message)
            throw IllegalStateException(message)
        }

        return Runnable {
            executeCritical {
                deviceState = DeviceState.RESETTING

                val nanos = measureNanoTime {
                    shutdown()
                    resetFromBackup()
                    try {
                        prepare(clean = false) // simulator is already clean as it was restored from backup in resetFromBackup
                    } catch (e: Exception) { // catching most wide exception
                        deviceState = DeviceState.FAILED
                        logger.error(logMarker, "Failed to reset and prepare device ${this@Simulator}", e)
                        throw e
                    }
                }

                val seconds = NANOSECONDS.toSeconds(nanos)

                val measurement = mutableMapOf(
                    "action_name" to "resetAsync",
                    "duration" to seconds
                )
                measurement.putAll(commonLogMarkerDetails)

                logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} reset and ready in $seconds seconds")
            }
        }
    }

    private fun resetFromBackup(timeout: Duration = RESET_TIMEOUT) {
        logger.info(logMarker, "Starting to reset $this")

        executeWithTimeout(timeout, "Resetting simulator") {
            if (!backup.isExist()) {
                logger.error(logMarker, "Could not find backup for $this")
                throw SimulatorError("Could not find backup for $this")
            }

            logTiming("replacing with backup") {
                backup.restore()
            }
        }

        logger.info(logMarker, "Finished to reset $this")
    }
    //endregion

    //region helper functions — execute critical and async
    private fun executeCritical(action: () -> Unit) {
        deviceLock.withLock {
            try {
                action()
            } catch (e: RuntimeException) {
                deviceState = DeviceState.FAILED
                lastException = e
                // FIXME: force shutdown failed sim
                logger.error(logMarker, "Execute critical block finished with exception. Message: [${e.message}]", e)
            }
        }
    }
    //endregion

    //region simulator status
    override fun status(): SimulatorStatusDTO {
        var isFbsimctlReady = false
        var isWdaReady = false

        if (deviceState == DeviceState.CREATED) {
            isFbsimctlReady = fbsimctlProc.isHealthy()
            isWdaReady = (if (useWda) { webDriverAgent.isHealthy() } else true)
        }

        val isSimulatorReady = deviceState == DeviceState.CREATED && isFbsimctlReady && isWdaReady

        return SimulatorStatusDTO(
                ready = isSimulatorReady,
                wda_status = isWdaReady,
                fbsimctl_status = isFbsimctlReady,
                state = deviceState.value,
                last_error = lastException?.toDTO()
        )
    }
    //endregion

    override fun endpointFor(port: Int): URL {
        val ports = allocatedPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    //region approveAccess

    override fun approveAccess(bundleId: String, locationPermissionsLock: ReentrantLock) {
        val permissions = SimulatorPermissions(remote, deviceSetPath, this)

        val set = PermissionSet()

        set.putAll(mapOf(
            PermissionType.Camera to PermissionAllowed.Yes,
            PermissionType.Microphone to PermissionAllowed.Yes,
            PermissionType.Photos to PermissionAllowed.Yes,
            PermissionType.Contacts to PermissionAllowed.Yes
        ))

        permissions.setPermissions(bundleId, set, locationPermissionsLock)
    }

    override fun setPermissions(bundleId: String, permissions: PermissionSet, locationPermissionsLock: ReentrantLock) {
        val manager = SimulatorPermissions(remote, deviceSetPath, this)

        manager.setPermissions(bundleId, permissions, locationPermissionsLock)
    }

    //endregion

    //region release
    override fun release(reason: String) {
        logger.info(logMarker, "Releasing device $this because $reason")
        shutdown()
        disposeResources()
        logger.info(logMarker, "Released device $this")
    }

    private fun deleteSimulatorKeepingMetadata() {
        val simulatorDataDirectoryPath = simulatorDataDirectory.absolutePath
        val deleteResult = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", simulatorDataDirectoryPath), timeOutSeconds = 120L)

        if (!deleteResult.isSuccess) {
            logger.error(logMarker, "Failed to delete at path: [$simulatorDataDirectoryPath]. Result: $deleteResult")

            val r = remote.execIgnoringErrors(listOf("/usr/bin/sudo", "/bin/rm", "-rf", simulatorDataDirectoryPath), timeOutSeconds = 120L);

            if (!r.isSuccess) {
                val undeletedFiles = remote.execIgnoringErrors(listOf("/usr/bin/find", simulatorDataDirectoryPath), timeOutSeconds = 90L);
                logger.error(logMarker, "Failed to delete at path: [$simulatorDataDirectoryPath]. Not deleted files: ${undeletedFiles.stdOut}")
            }
        }
    }

    private fun disposeResources() {
        ignoringErrors({ videoRecorder.dispose() })
        deleteSimulatorKeepingMetadata()
    }

    private fun ignoringErrors(action: () -> Unit?) {
        try {
            action()
        } catch (e: Throwable) { // FIXME: RuntimeError, SystemCallError in Ruby
            logger.warn(logMarker, "Ignoring $this release error: $e")
        }
    }
    //endregion

    /**
     * [see Deleting-Safari-Cookies-in-iOS-Simulator.html](http://www.ryanchapin.com/fv-b-4-744/Deleting-Safari-Cookies-in-iOS-Simulator.html)
     */
    private val cookieJars = listOf(
        "Cookies.binarycookies", // pre iOS 12
        "com.apple.SafariViewService.binarycookies" // iOS 12
    )

    override fun clearSafariCookies(): Map<String, String> {
        val apps = remote.fbsimctl.listApps(udid)
        check(!apps.isEmpty()) { "Could not list apps for $this" }

        val safari = apps.find { SAFARI_BUNDLE_ID == it.bundle.bundle_id }

        if (safari == null) {
            throw IllegalStateException("$SAFARI_BUNDLE_ID not found in $apps for $this")
        }

        // Have to kill Simulator's SafariViewService process as it holds cookies loaded
        simulatorProcess.terminateChildProcess("SafariViewService")

        val cookieJarPaths = cookieJars.map { cookieJar ->
            File(safari.data_container, listOf("Library", "Cookies", cookieJar).joinToString(File.separator)).absolutePath
        }

        val cmd = mutableListOf("rm", "-f")
        cmd.addAll(cookieJarPaths)
        val result = remote.execIgnoringErrors(cmd)
        check(result.isSuccess) { "Failed to remove safari cookies ($cookieJarPaths on $remote for $this: $result" }

        return mapOf("status" to "true")
    }

    override fun shake() : Boolean {
        val command = listOf("xcrun", "simctl", "notify_post", udid, "com.apple.UIKit.SimulatorShake")
        val result = remote.execIgnoringErrors(command)
        return result.isSuccess
    }

    override fun openUrl(url: String) : Boolean {
        val urlString = if (remote.isLocalhost()) { url } else { "\"$url\"" }

        val command = listOf("xcrun", "simctl", "openurl", udid, urlString)
        val result = remote.execIgnoringErrors(command)
        return result.isSuccess
    }

    //region last crash log

    @Deprecated("Will be removed in favor of crashLogs. Note that crashLogs does not delete old crashes")
    override fun lastCrashLog(): CrashLog {
        val crashLog = crashLogs(pastMinutes = null).firstOrNull() ?: CrashLog("no crash logs found", "")

        deleteCrashLogs() // FIXME: this will delete current crash log which makes method non-idempotent

        return crashLog
    }

    override fun crashLogs(pastMinutes: Long?): List<CrashLog> {
        val crashLogFiles = listCrashLogs(pastMinutes)

        val crashLogs = crashLogFiles.map {
            val rv = remote.execIgnoringErrors(listOf("cat", it))

            if (rv.isSuccess) {
                CrashLog(filename = File(it).name, content = rv.stdOut)
            } else {
                logger.warn(logMarker, "Cannot read  crash log file $it")
                null
            }
        }

        return crashLogs.filterNotNull()
    }

    /**
     * Returns list of crashes (file names) since {@code pastMinutes} sorted by most recent first
     * @param pastMinutes optional duration to search from, for example Duration.ofMinutes(5)
     */
    private fun listCrashLogs(pastMinutes: Long? = null):List<String> {
        if (pastMinutes != null && pastMinutes < 0) {
            throw IllegalArgumentException("pastMinutes should be positive ")
        }

        val predicate = when (pastMinutes) {
            null -> ""
            else -> "-mmin -$pastMinutes"
        }

        // `ls -1t` is for backwards compatibility with lastCrashLog as it expects this method to return most recent crashes first
        val cmd = "find \$HOME/Library/Logs/DiagnosticReports $predicate -type f -name \\*.crash -print0 | " +
                "xargs -0 grep --files-with-matches --null $udid | " +
                "xargs -0 ls -1t"

        val result = remote.shell(cmd, returnOnFailure = true)
        if (!result.isSuccess) {
            throw SimulatorError("Failed to list crash logs for $this: $result")
        }
        return result.stdOut
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun deleteCrashLogs(): Boolean {
        val cmd = "find \$HOME/Library/Logs/DiagnosticReports -type f -name \\*.crash -print0 | " +
                "xargs -0 grep --files-with-matches --null $udid | " +
                "xargs -0 rm"

        val result = remote.shell(cmd, returnOnFailure = true)

        return result.isSuccess
    }

    override fun dataContainer(bundleId: String): DataContainer {
        return fileSystem.dataContainer(bundleId)
    }

    override fun applicationContainer(bundleId: String): DataContainer {
        return fileSystem.applicationContainer(bundleId)
    }

    //endregion
    override fun uninstallApplication(bundleId: String, appInstaller: AppInstaller) {
        appInstaller.uninstallApplication(udid, bundleId)
    }

    override fun setEnvironmentVariables(envs: Map<String, String>) {
        if (envs.isEmpty()) {
            logger.debug(logMarker, "Passed empty list of environment variables for Simulator $this")
            return
        }

        logger.debug(logMarker, "Setting environment variables $envs for Simulator $this")
        val envsArguments = mutableListOf<String>()
        envs.keys.forEach {
            envsArguments.addAll(listOf(it, ShellUtils.escape(envs.getValue(it))))
        }
        remote.shell("xcrun simctl spawn $udid launchctl setenv ${envsArguments.joinToString(" ")}")
    }
}
