package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.WaitTimeoutError
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceState
import com.badoo.automation.deviceserver.ios.proc.FbsimctlProc
import com.badoo.automation.deviceserver.ios.proc.SimulatorWebDriverAgent
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.FileSystem
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
        override val fbsimctlSubject: String,
        debugXCTest: Boolean
) : ISimulator
{
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(4)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(3)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
    }

    //region public properties copied from Ruby
    override val ref = deviceRef
    override val udid = deviceInfo.udid
    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint= URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val userPorts = allocatedPorts
    override val info = deviceInfo
    override val calabashPort: Int = allocatedPorts.calabashPort
    override val videoRecorder: SimulatorVideoRecorder = SimulatorVideoRecorder(udid, remote)
    //endregion

    //region instance state variables
    private val deviceLock = ReentrantLock()
    @Volatile private var deviceState: DeviceState = DeviceState.NONE // writing from separate thread
    @Volatile private var lastException: Exception? = null // writing from separate thread

    private lateinit var criticalAsyncPromise: Job // 1-1 from ruby
    private val fbsimctlProc: FbsimctlProc = FbsimctlProc(remote, deviceInfo.udid, fbsimctlEndpoint, headless)
    private val wdaProc = SimulatorWebDriverAgent(remote, wdaRunnerXctest, deviceInfo.udid, wdaEndpoint, debugXCTest)
    private val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath)
    private val simulatorStatus = SimulatorStatus()
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val commonLogMarkerDetails = mapOf(
            LogMarkers.DEVICE_REF to deviceRef,
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    )
    private val logMarker: Marker = MapEntriesAppendingMarker(commonLogMarkerDetails)
    private val fileSystem = FileSystem(remote, udid)
    private val simulatorProcess = SimulatorProcess(remote, udid)
    //endregion

    //region properties from ruby with backing mutable field
    override val state get() = deviceState
    override val lastError get() = lastException
    //endregion

    override fun toString() = "<Simulator: $deviceRef>"

    //region prepareAsync
    override fun prepareAsync() {
        executeCriticalAsync {
            val elapsed = measureTimeMillis {
                prepare(clean = true)
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

            logTiming("starting WebDriverAgent") { startWdaWithRetry() }

            logger.info(logMarker, "Finished preparing $this")
            deviceState = DeviceState.CREATED
        }
    }

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(60), retryInterval: Duration = Duration.ofSeconds(3)) {
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
            catch (e: WaitTimeoutError) {
                logger.warn(logMarker, "Attempt $attempt to start WebDriverAgent for ${this@Simulator} timed out: $e")
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

        logger.info(logMarker, "Booting ${this@Simulator} before creating a backup")
        logTiming("initial boot") { boot() }

        logger.info(logMarker, "Shutting down ${this@Simulator} before creating a backup")
        shutdown()

        backup.create()
    }

    private fun shutdown() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")
        ignoringErrors({ fbsimctlProc.kill() })

        if (remote.fbsimctl.listDevice(udid)?.state != FBSimctlDeviceState.SHUTDOWN.value) {
            remote.fbsimctl.shutdown(udid)
            pollFor(Duration.ofSeconds(50), "${this@Simulator} to shutdown", logger = logger, marker = logMarker) {
                val fbSimctlDevice = remote.fbsimctl.listDevice(udid)
                FBSimctlDeviceState.SHUTDOWN.value == fbSimctlDevice?.state
            }
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
        executeCritical {
            deviceState = DeviceState.RESETTING
        }

        executeCriticalAsync {
            // FIXME: check for it.isActive to help to cancel long running tasks
            val elapsed = measureTimeMillis {
                resetFromBackup()
                prepare(clean = false) // simulator is already clean as it was restored from backup in resetFromBackup
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
        refreshStatus()
        return SimulatorStatusDTO(
                ready = deviceState == DeviceState.CREATED && simulatorStatus.isReady,
                wda_status = simulatorStatus.wdaStatus,
                fbsimctl_status = simulatorStatus.fbsimctlStatus,
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

    private fun refreshStatus() {
        simulatorStatus.isReady = false
        simulatorStatus.fbsimctlStatus = false
        simulatorStatus.wdaStatus = false

        if (deviceState != DeviceState.CREATED) {
            return
        }

        val device = remote.fbsimctl.listDevice(udid) ?: return

        if (device.state != FBSimctlDeviceState.BOOTED.value) {
            return
        }

        runBlocking {
            val isFbsimctlHealthyTask = async { fbsimctlProc.isHealthy() }
            val isWdaHealthyTask = async { wdaProc.isHealthy() }

            val isFbsimctlHealthy: Boolean = isFbsimctlHealthyTask.await()
            val isWdaHealthy: Boolean = isWdaHealthyTask.await()

            if (isFbsimctlHealthy) {
                simulatorStatus.fbsimctlStatusRetries = 0
            } else {
                simulatorStatus.fbsimctlStatusRetries += 1

                if (simulatorStatus.fbsimctlStatusRetries > 3) {
                    executeCritical {
                        deviceState = DeviceState.FAILED
                    }

                    val message = "${this@Simulator} Fbsimctl: simulator is not healthy, probably crashed."
                    logger.error(logMarker, message)
                    lastException = RuntimeException(message)
                    return@runBlocking
                }
            }

            if (isWdaHealthy) {
                simulatorStatus.wdaStatusRetries = 0
            } else {
                simulatorStatus.wdaStatusRetries += 1

                if (simulatorStatus.wdaStatusRetries > 3) {
                    executeCritical {
                        deviceState = DeviceState.FAILED
                    }

                    val message = "${this@Simulator} WebDriverAgent is not healthy, probably crashed."
                    logger.error(logMarker, message)
                    lastException = RuntimeException(message)
                    return@runBlocking
                }
            }

            simulatorStatus.isReady = isWdaHealthy && isFbsimctlHealthy
            simulatorStatus.fbsimctlStatus = isFbsimctlHealthy
            simulatorStatus.wdaStatus = isWdaHealthy
        }
    }
    //endregion

    override fun endpointFor(port: Int): URL {
        val ports = allocatedPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    //region approveAccess
    override fun approveAccess(bundleId: String) {
        updatePermission(bundleId, "kTCCServiceCamera")
        updatePermission(bundleId, "kTCCServiceMicrophone")
        updatePermission(bundleId, "kTCCServicePhotos")
        updatePermission(bundleId, "kTCCServiceAddressBook")
    }

    private fun updatePermission(bundleId: String, key: String) {
        val path = File(deviceSetPath, udid)
        val sqlCmd = "sqlite3 ${path.absolutePath}/data/Library/TCC/TCC.db"
        val insert =
            "$sqlCmd \"INSERT INTO access (service, client, client_type, allowed, prompt_count) VALUES ('$key','$bundleId',0,1,1);\""
        val update = "$sqlCmd \"UPDATE access SET allowed=1 where client='$bundleId' AND service='$key'\""

        // FIXME: should we fail if sqlite3 fails (insert or update) or shall we do a separate check for access to be granted?
        remote.shell(insert)
        val result = remote.shell(update)

        if (!result.isSuccess) {
            logger.error(logMarker, "Device $this permission update failed ${result.stdErr}")
        }
    }
    //endregion

    //region release
    override fun release(reason: String) {
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
    override fun lastCrashLog(): CrashLog {
        val crashLogs = listCrashLogs()

        if (crashLogs.isEmpty()) {
            //FIXME: in Ruby there is a JSON parse exception for not found case
            return CrashLog("no crash logs found", "")
        }

        val logFileName = crashLogs.first()

        if (logFileName.isBlank()) {
            return CrashLog("no crash logs found", "")
        }

        val result = remote.execIgnoringErrors(listOf("cat", logFileName))

        if (!result.isSuccess) {
            throw SimulatorError("Failed to read crash file $logFileName on $remote for $this: $result")
        } else {
            cleanupOldCrashLogs(crashLogs) // FIXME: this will delete current crash log which makes method non-idempotent
        }

        return CrashLog(filename = File(logFileName).name, content = result.stdOut)
    }

    private fun cleanupOldCrashLogs(crashLogs: List<String>) {
        crashLogs.forEach { logFileName ->
            try {
                remote.execIgnoringErrors(listOf("rm", "-f", "'$logFileName'"), timeOutSeconds = 5)
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Failed to delete crash log $logFileName: $e")
            }
        }
    }

    private fun listCrashLogs(): List<String> {
        val cmd = "ls -t \$HOME/Library/Logs/DiagnosticReports/*.crash | xargs grep -l $udid || true"

        val result = remote.shell(cmd, returnOnFailure = true)
        if (!result.isSuccess) {
            SimulatorError("Failed to list crash logs for $this: $result")
        }
        return result.stdOut
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
    }

    override fun dataContainer(bundleId: String): DataContainer {
        return fileSystem.dataContainer(bundleId)
    }

    //endregion
    override fun uninstallApplication(bundleId: String) {
        logger.debug(logMarker, "Uninstalling aplication $bundleId from Simulator $this")
        remote.execIgnoringErrors(listOf("xcrun", "simctl", "uninstall", udid, bundleId))
    }
}
