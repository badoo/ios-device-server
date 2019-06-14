package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.WaitTimeoutError
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceState
import com.badoo.automation.deviceserver.ios.proc.FbsimctlProc
import com.badoo.automation.deviceserver.ios.proc.SimulatorWebDriverAgent
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.FileSystem
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import com.badoo.automation.deviceserver.util.executeWithTimeout
import com.badoo.automation.deviceserver.util.pollFor
import kotlinx.coroutines.experimental.*
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureTimeMillis

class Simulator (
        private val deviceRef: DeviceRef,
        private val remote: IRemote,
        deviceInfo: DeviceInfo,
        private val allocatedPorts: DeviceAllocatedPorts,
        private val deviceSetPath: String,
        wdaRunnerXctest: File,
        private val concurrentBootsPool: ThreadPoolDispatcher,
        headless: Boolean,
        private val useWda: Boolean,
        override val fbsimctlSubject: String,
        private val trustStoreFile: String = ApplicationConfiguration().trustStorePath
) : ISimulator
{
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(4)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(3)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
    }

    override val ref = deviceRef
    override val udid = deviceInfo.udid
    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint= URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val userPorts = allocatedPorts
    override val info = deviceInfo
    override val calabashPort: Int = allocatedPorts.calabashPort

    private val recordingLocation = Paths.get(deviceSetPath, udid, "video.mp4").toFile()

    override val videoRecorder: SimulatorVideoRecorder = SimulatorVideoRecorder(deviceInfo, remote, location = recordingLocation)

    override val systemLog = SystemLog(remote, udid)
    override val osLog = OsLog(remote, udid)

    //region instance state variables
    private val deviceLock = ReentrantLock()
    @Volatile private var deviceState: DeviceState = DeviceState.NONE // writing from separate thread
    @Volatile private var lastException: Exception? = null // writing from separate thread

    private lateinit var criticalAsyncPromise: Job // 1-1 from ruby
    private val fbsimctlProc: FbsimctlProc = FbsimctlProc(remote, deviceInfo.udid, fbsimctlEndpoint, headless)
    private val wdaProc = SimulatorWebDriverAgent(remote, wdaRunnerXctest, deviceInfo.udid, wdaEndpoint)
    private val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath)
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val commonLogMarkerDetails = mapOf(
            LogMarkers.DEVICE_REF to deviceRef,
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    )
    private val logMarker: Marker = MapEntriesAppendingMarker(commonLogMarkerDetails)
    private val fileSystem = FileSystem(remote, udid)
    private val simulatorProcess = SimulatorProcess(remote, udid)
    @Volatile private var healthChecker: Job? = null
    //endregion

    override val media: Media = Media(remote, udid, deviceSetPath)

    //region properties from ruby with backing mutable field
    override val state get() = deviceState
    override val lastError get() = lastException
    //endregion

    override fun toString() = "<Simulator: $deviceRef>"

    //region prepareAsync
    override fun prepareAsync() {
        stopPeriodicHealthCheck()
        executeCritical {
            deviceState = DeviceState.CREATING
        }

        executeCriticalAsync {
            val elapsed = measureTimeMillis {
                try {
                    prepare(clean = true)
                } catch (e: Exception) { // catching most wide exception
                    executeCritical {
                        deviceState = DeviceState.FAILED
                    }
                    logger.error(logMarker, "Failed to prepare device ${this@Simulator}", e)
                    throw e
                }
            }
            logger.info(logMarker, "Device ${this@Simulator} ready in ${elapsed / 1000} seconds")
        }
    }

    private fun prepare(timeout: Duration = PREPARE_TIMEOUT, clean: Boolean) {
        logger.info(logMarker, "Starting to prepare ${this@Simulator}. Will wait for ${timeout.seconds} seconds")
        lastException = null
        wdaProc.kill()
        shutdown()

        //FIXME: add checks for cancellation of criticalAsyncPromise
        executeWithTimeout(timeout, "Preparing simulator") {
            // erase simulator if there is no existing backup, this is to ensure backup is created from a clean state
            logger.info(logMarker, "Launch prepare sequence for ${this@Simulator} asynchronously")

            if (backup.isExist()) {
                if (clean) {
                    backup.restore()
                }
            } else {
                eraseSimulatorAndCreateBackup()
            }

            logTiming("simulator boot") { boot() }

            if (useWda) {
                logTiming("starting WebDriverAgent") { startWdaWithRetry() }
            }

            logger.info(logMarker, "Finished preparing $this")
            deviceState = DeviceState.CREATED
            startPeriodicHealthCheck()
        }
    }

    private fun startPeriodicHealthCheck() {
        stopPeriodicHealthCheck()

        var fbsimctlFailCount = 0
        var wdaFailCount = 0
        val maxFailCount = 3
        val healthCheckInterval = Duration.ofSeconds(10).toMillis()

        healthChecker = launch {
            while (isActive) {
                if (fbsimctlProc.isHealthy()) {
                    fbsimctlFailCount = 0
                } else {
                    fbsimctlFailCount += 1

                    if (fbsimctlFailCount >= maxFailCount) {
                        deviceState = DeviceState.FAILED
                        val message = "Fbsimctl health check failed $fbsimctlFailCount times. Setting device state to $deviceState"
                        logger.error(logMarker, message)
                        throw RuntimeException("${this@Simulator} $message. Stopping health check")
                    }
                }

                if (wdaProc.isHealthy()) {
                    wdaFailCount = 0
                } else {
                    wdaFailCount += 1

                    if (wdaFailCount >= maxFailCount) {
                        deviceState = DeviceState.FAILED
                        val message = "WebDriverAgent health check failed $wdaFailCount times. Setting device state to $deviceState"
                        logger.error(logMarker, message)
                        throw RuntimeException("${this@Simulator} $message. Stopping health check")
                    }
                }

                delay(healthCheckInterval)
            }
        }
    }

    private fun stopPeriodicHealthCheck() {
        healthChecker?.cancel()
    }

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(30), retryInterval: Duration = Duration.ofSeconds(3)) {
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            try {
                logger.info(logMarker, "Starting WebDriverAgent on ${this@Simulator}")

                wdaProc.kill()
                wdaProc.start()

                pollFor(
                    pollTimeout,
                    reasonName = "${this@Simulator} WebDriverAgent health check",
                    retryInterval = retryInterval,
                    logger = logger,
                    marker = logMarker
                ) {
                    //FIXME: add short_circuit: and throw if wdaProc.childProcess is dead
                    if (wdaProc.isProcessAlive) {
                        wdaProc.isHealthy()
                    } else {
                        throw WaitTimeoutError("WebDriverAgent process is not alive")
                    }
                }

                break
            }
            catch (e: RuntimeException) {
                logger.warn(logMarker, "Attempt $attempt to start WebDriverAgent for ${this@Simulator} failed: $e")
                if (attempt == maxRetries) {
                    throw e
                }
            }
        }

        logger.info(logMarker, "Started WebDriverAgent on ${this@Simulator}")
    }

    private fun eraseSimulatorAndCreateBackup() {
        logger.info(logMarker, "Erasing simulator ${this@Simulator} before creating a backup")
        remote.fbsimctl.eraseSimulator(udid)

        if (trustStoreFile.isNotEmpty()) {
            copyTrustStore()
        }

        logger.info(logMarker, "Booting ${this@Simulator} before creating a backup")
        logTiming("initial boot") { boot() }

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
            remote.rsync(trustStoreFile, keyChainLocation, setOf("--delete"))
        }

        logger.info(logMarker, "Copied trust store to ${this@Simulator}")
    }

    private fun shutdown() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")
        remote.fbsimctl.shutdown(udid)
        ignoringErrors { fbsimctlProc.kill() }

        pollFor(
            timeOut = Duration.ofSeconds(60),
            reasonName = "${this@Simulator} to shutdown",
            retryInterval = Duration.ofSeconds(10),
            logger = logger,
            marker = logMarker
        ) {
            val fbSimctlDevice = remote.fbsimctl.listDevice(udid)
            FBSimctlDeviceState.SHUTDOWN.value == fbSimctlDevice?.state
        }

        logger.info(logMarker, "Successfully shut down ${this@Simulator}")
    }

    private fun boot() {
        logger.info(logMarker, "Booting ${this@Simulator} asynchronously")
        val bootJob = async(context = concurrentBootsPool) {
            truncateSystemLogIfExists()

            logger.info(logMarker, "Starting fbsimctl on ${this@Simulator}")
            fbsimctlProc.start() // boots simulator

            var lastState: String? = null

            try {
                pollFor(
                    Duration.ofSeconds(60),
                    reasonName = "${this@Simulator} initial boot",
                    shouldReturnOnTimeout = false,
                    logger = logger,
                    marker = logMarker
                ) {
                    val simulatorInfo = remote.fbsimctl.listDevice(udid)
                    lastState = simulatorInfo?.state
                    lastState == FBSimctlDeviceState.BOOTED.value
                }
            } catch (e: WaitTimeoutError) {
                throw WaitTimeoutError("${e.message}. Simulator is in wrong state of $lastState", e)
            }

            var systemLogPath = ""

            pollFor(Duration.ofSeconds(20), "${this@Simulator} system log appeared",
                    shouldReturnOnTimeout = true, logger = logger, marker = logMarker) {
                val diagnosticInfo = remote.fbsimctl.diagnose(udid)
                val location = diagnosticInfo.sysLogLocation
                if (location != null) {
                    systemLogPath = location
                    logger.info(logMarker, "Device ${this@Simulator} system log appeared")
                    true
                } else {
                    logger.warn(logMarker, "Device ${this@Simulator} system log NOT appeared")
                    false
                }
            }

            pollFor(Duration.ofSeconds(30), "${this@Simulator} to be sufficiently booted",
                    shouldReturnOnTimeout = true, logger = logger, marker = logMarker) {
                if (!systemLogPath.isBlank()) {
                    remote.execIgnoringErrors(listOf("grep", "-m1", "SpringBoard", systemLogPath)).isSuccess
                } else {
                    false
                }
            }

            pollFor(
                Duration.ofSeconds(60),
                reasonName = "${this@Simulator} FbSimCtl health check",
                retryInterval = Duration.ofSeconds(3),
                logger = logger,
                marker = logMarker
            ) {
                fbsimctlProc.isHealthy()
            }

            logger.info(logMarker, "Device ${this@Simulator} is sufficiently booted")
        }

        runBlocking {
            bootJob.await()
        }
    }

    private fun truncateSystemLogIfExists() {
        val sysLog = remote.fbsimctl.diagnose(udid).sysLogLocation ?: return

        if (remote.isLocalhost()) {
            try {
                FileOutputStream(sysLog).channel.use {
                    it.truncate(0)
                }
            } catch(e: IOException) {
                logger.error(logMarker, "Error truncating sysLog $this", e)
            }
        } else {
            try {
                remote.shell("echo -n > $sysLog", returnOnFailure = true)
                logger.debug(logMarker, "Truncated syslog of simulator ${this@Simulator}")
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error while truncating syslog of simulator ${this@Simulator}", e)
            }
        }
    }

    private fun logTiming(actionName: String, action: () -> Unit) {
        logger.info(logMarker, "Device ${this@Simulator} starting action <$actionName>")
        val millis = measureTimeMillis(action)
        val seconds = millis / 1000
        val measurement = mutableMapOf(
                "action_name" to actionName,
                "duration" to seconds
        )
        measurement.putAll(commonLogMarkerDetails)
        logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} action <$actionName> took $seconds seconds")
    }
    //endregion

    //region reset async
    override fun resetAsync() {
        stopPeriodicHealthCheck()
        executeCritical {
            deviceState = DeviceState.RESETTING
        }

        executeCriticalAsync {
            // FIXME: check for it.isActive to help to cancel long running tasks
            val elapsed = measureTimeMillis {
                resetFromBackup()
                try {
                    prepare(clean = false) // simulator is already clean as it was restored from backup in resetFromBackup
                } catch (e: Exception) { // catching most wide exception
                    executeCritical {
                        deviceState = DeviceState.FAILED
                    }
                    logger.error(logMarker, "Failed to reset and prepare device ${this@Simulator}", e)
                    throw e
                }
            }
            logger.info(logMarker, "Device ${this@Simulator} reset and ready in ${elapsed / 1000} seconds")
        }
    }

    private fun resetFromBackup(timeout: Duration = RESET_TIMEOUT) {
        logger.info(logMarker, "Starting to reset $this")

        executeWithTimeout(timeout, "Resetting simulator") {
            disposeResources()
            shutdown()

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
    private fun executeCriticalAsync(function: (context: CoroutineScope) -> Unit) {
        criticalAsyncPromise = launch(context = simulatorsThreadPool) {
            executeCritical {
                function(this)
            }
        }
    }

    private fun executeCritical(action: () -> Unit) {
        if (deviceLock.isLocked) {
            logger.info(logMarker, "Awaiting for previous action. Likely a criticalAsyncPromise $criticalAsyncPromise on ${this@Simulator}")
        }

        deviceLock.withLock {
            try {
                action()
            } catch (e: RuntimeException) {
                deviceState = DeviceState.FAILED
                lastException = e
                // FIXME: force shutdown failed sim
                logger.error(logMarker, "Execute critical block finished with exception. Message: [${e.message}]", e)
                logger.warn(logMarker, "Host stats on ${this@Simulator} are:\n${getSystemStats()}")
            }
        }
    }

    private fun getSystemStats(): String {
        val uptime = remote.execIgnoringErrors(listOf("/usr/bin/uptime"))
        val message = mutableListOf("uptime", uptime.stdOut)

        val istats = remote.execIgnoringErrors(listOf("istats", "--no-graphs"), env = mapOf("RUBYOPT" to ""))

        if (istats.isSuccess) {
            message.add("istats")
            message.add(istats.stdOut)
        }

        return message.joinToString("\n")
    }
    //endregion

    //region simulator status
    override fun status(): SimulatorStatusDTO {
        var isFbsimctlReady = false
        var isWdaReady = false

        if (deviceState == DeviceState.CREATED) {
            isFbsimctlReady = fbsimctlProc.isHealthy()
            isWdaReady = (if (useWda) { wdaProc.isHealthy() } else true)
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

    private fun Exception.toDTO(): ExceptionDTO {

        return ExceptionDTO(
            type = this.javaClass.name,
            message = this.message ?: "",
            stackTrace = stackTrace.map { it.toString() }
        )
    }
    //endregion

    override fun endpointFor(port: Int): URL {
        val ports = allocatedPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    //region approveAccess

    override fun approveAccess(bundleId: String) {
        val permissions = SimulatorPermissions(remote, deviceSetPath, this)

        val set = PermissionSet()

        set.putAll(mapOf(
            PermissionType.Camera to PermissionAllowed.Yes,
            PermissionType.Microphone to PermissionAllowed.Yes,
            PermissionType.Photos to PermissionAllowed.Yes,
            PermissionType.Contacts to PermissionAllowed.Yes
        ))

        permissions.setPermissions(bundleId, set)
    }

    override fun setPermissions(bundleId: String, permissions: PermissionSet) {
        val manager = SimulatorPermissions(remote, deviceSetPath, this)

        manager.setPermissions(bundleId, permissions)
    }

    //endregion

    //region release
    override fun release(reason: String) {
        stopPeriodicHealthCheck()
        logger.info(logMarker, "Releasing device $this because $reason")

        // FIXME: add background thread to clear up junk we failed to delete
        if (deviceLock.isLocked) {
            logger.warn(logMarker, "Going to kill previous promise $criticalAsyncPromise running on $this")
        }

        if (criticalAsyncPromise.isActive) {
            // FIXME: unlike in Ruby canceling is not immediate, consider using thread instead of async
            criticalAsyncPromise.cancel(CancellationException("Killing previous $criticalAsyncPromise running on $this due to release of the device"))
        }

        executeCritical {
            disposeResources()
            shutdown()
        }
        logger.info(logMarker, "Released device $this")
    }

    private fun disposeResources() {
        ignoringErrors({ videoRecorder.dispose() })
        ignoringErrors({ wdaProc.kill() })
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

    //region last crash log

    @Deprecated("Will be removed in favor of crashLogs. Note that crashLogs does not delete old crashes")
    override fun lastCrashLog(): CrashLog {
        val crashLog = crashLogs(pastMinutes = null).firstOrNull() ?: CrashLog("no crash logs found", "")

        deleteCrashLogs() // FIXME: this will delete current crash log which makes method non-idempotent

        return crashLog
    }

    override fun crashLogs(pastMinutes: Long?): List<CrashLog> {
        var crashLogFiles = listCrashLogs(pastMinutes)

        var crashLogs = crashLogFiles.map {
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
            SimulatorError("Failed to list crash logs for $this: $result")
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

    //endregion
    override fun uninstallApplication(bundleId: String) {
        logger.debug(logMarker, "Uninstalling application $bundleId from Simulator $this")

        terminateApplication(bundleId)

        val uninstallResult = remote.execIgnoringErrors(listOf("xcrun", "simctl", "uninstall", udid, bundleId))

        if (!uninstallResult.isSuccess) {
            logger.error(logMarker, "Uninstall application $bundleId was unsuccessful. Result $uninstallResult")
        }
    }

    private fun terminateApplication(bundleId: String) {
        val terminateResult = remote.execIgnoringErrors(listOf("xcrun", "simctl", "terminate", udid, bundleId))

        if (!terminateResult.isSuccess) {
            logger.error(logMarker, "Terminating application $bundleId was unsuccessful. Result $terminateResult")
        }
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
