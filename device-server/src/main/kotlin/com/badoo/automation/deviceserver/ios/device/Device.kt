package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.WaitTimeoutError
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.IDevice
import com.badoo.automation.deviceserver.ios.device.diagnostic.RealDeviceSysLog
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceState
import com.badoo.automation.deviceserver.ios.proc.WebDriverAgentError
import com.badoo.automation.deviceserver.ios.proc.XcodeTestRunnerDeviceAgent
import com.badoo.automation.deviceserver.ios.simulator.video.FFMPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Device(
    private val remote: IRemote,
    override val deviceInfo: DeviceInfo,
    override val userPorts: DeviceAllocatedPorts,
    private val wdaDeviceBundle: WdaDeviceBundle,
    usbProxy: UsbProxyFactory = UsbProxyFactory(remote)
) : IDevice {
    override val udid: String = deviceInfo.udid
    override val ref: DeviceRef by lazy {
        val unsafe = Regex("[^\\-_a-zA-Z\\d]")
        "${udid}-${remote.publicHostName}".replace(unsafe, "-")
    }

    override val osLog = RealDeviceSysLog(remote, udid)

    private val useFbsimctlProc = ApplicationConfiguration().useFbsimctlProc

    private val calabashProxy = usbProxy.create(
        udid = deviceInfo.udid,
        localPort = userPorts.calabashPort,
        devicePort = CALABASH_PORT
    )

    private val wdaProxy = usbProxy.create(
        udid = deviceInfo.udid,
        localPort = userPorts.wdaPort,
        devicePort = if (wdaDeviceBundle.bundleId.contains("DeviceAgent")) DA_PORT else WDA_PORT
    )

    override val mjpegServerPort = userPorts.mjpegServerPort
    private val mjpegProxy = usbProxy.create(
        udid = deviceInfo.udid,
        localPort = mjpegServerPort,
        devicePort = mjpegServerPort
    )

    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${userPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint = URI("http://${remote.publicHostName}:${wdaProxy.localPort}")
    override val calabashPort = calabashProxy.localPort
//    override val videoRecorder: VideoRecorder = MJPEGVideoRecorder(
//        deviceInfo,
//        remote,
//        mjpegServerPort,
//        deviceRefFromUDID(deviceInfo.udid, remote.publicHostName),
//        deviceInfo.udid
//    )

    override val videoRecorder: VideoRecorder = FFMPEGVideoRecorder(
        remote,
        mjpegServerPort,
        deviceRefFromUDID(deviceInfo.udid, remote.publicHostName),
        deviceInfo.udid
    )

    @Volatile
    override var lastException: Exception? = null
        private set

    @Volatile
    override var deviceState = DeviceState.NONE
        private set(value) {
            val oldState = field
            field = value
            logger.debug(logMarker, "$this $oldState -> $value")
        }

    private val fbsimctlProc: DeviceFbsimctlProc = DeviceFbsimctlProc(remote, deviceInfo.udid, fbsimctlEndpoint, false)
    private val wdaProc = XcodeTestRunnerDeviceAgent(
        remote = remote,
        wdaBundle = wdaDeviceBundle,
        udid = deviceInfo.udid,
        wdaEndpoint = wdaEndpoint,
        mjpegServerPort = mjpegServerPort,
        deviceRef = ref,
        isRealDevice = true,
        port = wdaProxy.devicePort
    )
    override val deviceAgentLog get() = wdaProc.deviceAgentLog

    private val status = SimulatorStatus()

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private var preparePromise: Future<*>? = null
    private var renewPromise: Future<*>? = null

    init {
        if (udid.isEmpty()) {
            throw IllegalArgumentException("udid cannot be empty")
        }
    }

    override fun toString(): String = "<Device: $udid>"

    override fun status(): SimulatorStatusDTO {
        refreshStatus()

        return SimulatorStatusDTO(
            ready = status.isReady,
            state = deviceState.value, // FIXME: why get rid of type here
            wda_status = status.wdaStatus,
            fbsimctl_status = status.fbsimctlStatus,
            last_error = lastException?.toDTO()
        )
    }

    private fun refreshStatus() {
        val previousFbSimctlStatus = status.fbsimctlStatus

        status.fbsimctlStatus = false
        status.wdaStatus = false

        if (deviceState != DeviceState.CREATED) {
            return
        }

        val currentState = remote.fbsimctl.listDevice(udid)?.state
        val isBooted = currentState == FBSimctlDeviceState.BOOTED.value

        if (!isBooted) {
            return
        }

        val isWdaHealty = wdaProc.isHealthy()
        val fbsimctlStatus = if (useFbsimctlProc) fbsimctlProc.isHealthy() else true

        // check if WDA or fbsimctl crashed after being ok for some time

        if (isWdaHealty) {
            status.wdaStatusRetries = 0
        } else {
            status.wdaStatusRetries += 1
        }

        if (status.wdaStatusRetries >= MAX_WDA_STATUS_CHECKS) {
            deviceState = DeviceState.FAILED
            val message = "${this} WebDriverAgent crashed. Last $MAX_WDA_STATUS_CHECKS health checks failed"
            logger.error(logMarker, message)
            lastException = RuntimeException(message)
        }

        if (previousFbSimctlStatus && !fbsimctlStatus) {
            deviceState = DeviceState.FAILED
            val message = "${this} fbsimctl crashed"
            logger.error(logMarker, message)
            lastException = RuntimeException(message)
        }

        status.fbsimctlStatus = fbsimctlStatus
        status.wdaStatus = isWdaHealty
    }

    override fun endpointFor(port: Int): URL {
        val ports = userPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    override fun release(reason: String) {
        installTask?.cancel(true)
        renewPromise?.cancel(true)
        preparePromise?.cancel(true)

        logger.debug("Disposing device $this for reason $reason")
        disposeResources()
    }

    private val deviceLock = ReentrantLock()

    @Volatile
    private var installTask: Future<Boolean>? = null


    override fun installApplication(appInstaller: AppInstaller, appBundleId: String, appBinaryPath: File) {
        deviceLock.withLock {
            installTask?.let { oldInstallTask ->
                if (!oldInstallTask.isDone) {
                    val message =
                        "Failed to install app $appBundleId to simulator $udid due to previous task is not finished"
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

    private fun disposeResources() {
        stopPeriodicHealthCheck()
        if (useFbsimctlProc) {
            ignoringDisposeErrors { fbsimctlProc.kill() }
        }
        ignoringDisposeErrors { wdaProc.kill() }
        ignoringDisposeErrors { calabashProxy.stop() }
        ignoringDisposeErrors { wdaProxy.stop() }
        ignoringDisposeErrors { mjpegProxy.stop() }
    }

    private fun ignoringDisposeErrors(action: () -> Unit?) {
        try {
            action()
        } catch (e: Throwable) { // FIXME: RuntimeError, SystemCallError in Ruby
            logger.warn(logMarker, "Ignoring $this release error: $e")
        }
    }

    override fun lastCrashLog(): CrashLog {
        return crashLogs(null).lastOrNull() ?: CrashLog("", "")
    }

    fun crashLogs(appName: String?): List<CrashLog> {
        val crashReportsPath = Files.createTempDirectory("crashReports")
        val filter = appName?.let { "--filter \"$appName\"" } ?: ""
        val command = "${
            File(
                remote.homeBrewPath,
                "idevicecrashreport"
            ).absolutePath
        } --udid $udid $filter ${crashReportsPath.toAbsolutePath()}"

        try {
            val result = remote.shell(command, returnOnFailure = true)

            if (!result.isSuccess) {
                logger.error(logMarker, "Failed to collect crash reports. Result stdErr: ${result.stdErr}")
            }

            return File(crashReportsPath.toUri())
                .walk()
                .filter { it.isFile }
                .map { CrashLog(it.name, String(Files.readAllBytes(it.toPath()))) }
                .toList()

        } finally {
            Files.walk(crashReportsPath)
                .sorted(Comparator.reverseOrder())
                .map { it.toFile() }
                .forEach { it.delete() }
        }
    }

    val asyncExecutor = Executors.newFixedThreadPool(3)

    private fun executeAsync(action: () -> Unit?): Future<*>? {
//        val executor = Executors.newSingleThreadExecutor()
//        val future = executor.submit(action)
//        executor.shutdown()

        val future = asyncExecutor.submit(action)

        return future
    }

    override fun prepareAsync() {
        if (preparePromise != null) {
            return
        }

        deviceState = DeviceState.NONE
        stopPeriodicHealthCheck()
        preparePromise = executeAsync {
            try {
                prepare()
            } catch (e: Exception) {
                deviceState = DeviceState.FAILED
                lastException = e
                logger.error(logMarker, e.message)
            }
        }
    }

    fun renewAsync(whitelistedApps: Set<String>, uninstallApps: Boolean) {
        stopPeriodicHealthCheck()
        val currentStatus = status()
        var prepareRequired = false

        if (currentStatus.state == DeviceState.FAILED.value) {
            prepareRequired = true
            deviceState = DeviceState.REVIVING
            logger.warn(logMarker, "$this failed, will try to revive")
        }

        renewPromise = executeAsync {
            try {
                if (uninstallApps) {
                    uninstallUserApps(whitelistedApps = whitelistedApps)
                }

                if (prepareRequired) {
                    preparePromise?.cancel(true)
                    preparePromise = null
                    prepareAsync()
                }
            } catch (e: Exception) {
                deviceState = DeviceState.FAILED
                lastException = e
                logger.error(logMarker, e.message)
            } finally {
                renewPromise = null
            }
        }
    }

    override fun listApps(): List<FBSimctlAppInfo> = remote.fbsimctl.listApps(udid)

    private fun uninstallUserApps(whitelistedApps: Set<String>) {
        logger.debug(logMarker, "About to uninstall user apps on $this")
        val listApps = remote.fbsimctl.listApps(udid)
        val userApps = listApps
            .filter { it.install_type != null && it.install_type.startsWith("user") }
            .map { it.bundle.bundle_id }

        val bundlesToUninstall = userApps.toSet() - whitelistedApps
        logger.info(logMarker, "Uninstalling user apps: $bundlesToUninstall")

        bundlesToUninstall.forEach {
            remote.fbsimctl.uninstallApp(udid, it)
        }
        logger.debug(logMarker, "Uninstalled user apps on $this")
    }

    private fun prepare(timeout: Duration = PREPARE_TIMEOUT) {
        lastException = null
        status.wdaStatus = false
        status.fbsimctlStatus = false

        logger.info(logMarker, "Starting to prepare $this")

        if (useFbsimctlProc) {
            fbsimctlProc.kill()
        }
        wdaProc.kill()

        wdaProxy.stop()
        mjpegProxy.stop()
        calabashProxy.stop()

        executeWithTimeout(timeout, name = "Preparing devices") {
            wdaProxy.start()

            if (!wdaProxy.isHealthy()) {
                throw DeviceException("Failed to start $wdaProxy")
            }

            mjpegProxy.start()

            if (!mjpegProxy.isHealthy()) {
                throw DeviceException("Failed to start $mjpegProxy")
            }

            calabashProxy.start()

            if (!calabashProxy.isHealthy()) {
                throw DeviceException("Failed to start $calabashProxy")
            }

            if (useFbsimctlProc) {
                startFbsimctl()
            }

            startWdaWithRetry()
            startPeriodicHealthCheck()
            logger.info(logMarker, "Finished preparing $this")
            deviceState = DeviceState.CREATED
        }
    }

    @Volatile
    private var healthChecker: Job? = null

    private fun startPeriodicHealthCheck() {
        stopPeriodicHealthCheck()

        val healthCheckInterval = Duration.ofSeconds(30).toMillis()

        healthChecker = launch {
            while (isActive) {
                performWebDriverAgentHealthCheck(10)
                delay(healthCheckInterval)
            }
        }
    }

    private suspend fun performWebDriverAgentHealthCheck(maxWDAFailCount: Int) {
        var wdaFailCount = 0
        if (!wdaProc.isHealthy()) {
            for (i in 0 until maxWDAFailCount) {
                if (wdaProc.isHealthy()) {
                    wdaFailCount = 0
                    break
                } else {
                    val message = "WebDriverAgent health check failed $wdaFailCount times."
                    logger.error(logMarker, message)
                    wdaFailCount += 1
                    delay(Duration.ofSeconds(3).toMillis())
                }
            }

            if (wdaFailCount >= maxWDAFailCount) {
                logger.error(
                    logMarker,
                    "WebDriverAgent health check failed $wdaFailCount times. Restarting WebDriverAgent"
                )

                try {
                    wdaProc.kill()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to kill WebDriverAgent. ${e.message}", e)
                }

                try {
                    wdaProc.start()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to restart WebDriverAgent. ${e.message}", e)
                    deviceState = DeviceState.FAILED
                    throw RuntimeException("$this Failed to restart WebDriverAgent. Stopping health check")
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

    private fun startFbsimctl() {
        logger.info(logMarker, "Starting fbsimctl on $this")

        if (useFbsimctlProc) {
            fbsimctlProc.kill()
            fbsimctlProc.start()

            Thread.sleep(5000)
            pollFor(
                Duration.ofSeconds(60),
                reasonName = "$this Fbsimctl health check",
                retryInterval = Duration.ofSeconds(2),
                logger = logger,
                marker = logMarker
            ) {
                fbsimctlProc.isHealthy()
            }
        }
    }


    private fun startWda() {
        wdaProc.kill()
        wdaProc.start()

        Thread.sleep(DEVICE_AGENT_START_TIME)

        pollFor(
            Duration.ofMinutes(1),
            reasonName = "$this WebDriverAgent health check",
            retryInterval = Duration.ofSeconds(5),
            logger = logger,
            marker = logMarker
        ) {
            if (wdaProc.isProcessAlive) {
                wdaProc.isHealthy()
            } else {
                throw WaitTimeoutError("WebDriverAgent process is not alive")
            }
        }
    }

    private fun startWdaWithRetry() {
        status.wdaStatusRetries = 0

        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            try {
                logger.info(logMarker, "Starting WebDriverAgent on $this. Attempt $attempt/$maxRetries")
                startWda()

                break
            } catch (e: Exception) {
                if (e is WebDriverAgentError || e is WaitTimeoutError) {
                    logger.warn(logMarker, "Attempt $attempt to start WebDriverAgent for ${this} timed out: $e")
                    if (attempt == maxRetries) {
                        throw e
                    }
                } else throw e
            }
        }
    }

    override fun uninstallApplication(bundleId: String, appInstaller: AppInstaller) {
        remote.fbsimctl.uninstallApp(udid, bundleId)
    }

    private companion object {
        private const val CALABASH_PORT = 37265
        private const val WDA_PORT = 8100
        private const val DA_PORT = 27753
        private val PREPARE_TIMEOUT = Duration.ofMinutes(5)
        private const val DEVICE_AGENT_START_TIME = 15_000L
        private const val MAX_WDA_STATUS_CHECKS = 10
    }
}
