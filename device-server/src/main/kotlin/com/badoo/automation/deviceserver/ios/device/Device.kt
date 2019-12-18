package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.WaitTimeoutError
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.IDevice
import com.badoo.automation.deviceserver.ios.WdaClient
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceState
import com.badoo.automation.deviceserver.ios.proc.WebDriverAgentError
import com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.executeWithTimeout
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import com.badoo.automation.deviceserver.util.pollFor
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

class Device(
    private val remote: IRemote,
    override val deviceInfo: DeviceInfo,
    override val userPorts: DeviceAllocatedPorts,
    wdaRunnerXctest: File,
    usbProxy: UsbProxyFactory = UsbProxyFactory(remote)
) : IDevice {
    override val udid: String = deviceInfo.udid
    override val ref: DeviceRef by lazy {
        val unsafe = Regex("[^\\-_a-zA-Z\\d]")
        "${udid}-${remote.publicHostName}".replace(unsafe, "-")
    }

    private val calabashProxy = usbProxy.create(
        udid = deviceInfo.udid,
        localPort = userPorts.calabashPort,
        devicePort = CALABASH_PORT
    )

    private val wdaProxy = usbProxy.create(
        udid = deviceInfo.udid,
        localPort = userPorts.wdaPort,
        devicePort = WDA_PORT
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
    override val videoRecorder: VideoRecorder = MJPEGVideoRecorder(deviceInfo, remote, wdaEndpoint, mjpegServerPort, deviceRefFromUDID(deviceInfo.udid, remote.publicHostName), deviceInfo.udid)

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
    private val wdaProc = DeviceWebDriverAgent(remote, wdaRunnerXctest, deviceInfo.udid, wdaEndpoint, wdaProxy.devicePort, mjpegServerPort)

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

        val wdaStatus = wdaProc.isHealthy()
        val fbsimctlStatus = fbsimctlProc.isHealthy()

        // check if WDA or fbsimctl crashed after being ok for some time

        if (wdaStatus) {
            status.wdaStatusRetries = 0
        } else {
            status.wdaStatusRetries += 1
        }

        val maxAttempts = 3

        if (status.wdaStatusRetries >= maxAttempts) {
            deviceState = DeviceState.FAILED
            val message = "${this} WebDriverAgent crashed. Last $maxAttempts health checks failed"
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
        status.wdaStatus = wdaStatus
    }

    override fun endpointFor(port: Int): URL {
        val ports = userPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    override fun release(reason: String) {
        renewPromise?.cancel(true)
        preparePromise?.cancel(true)

        logger.debug("Disposing device $this for reason $reason")
        disposeResources()
    }

    override fun installApplication(appInstaller: AppInstaller, appBundleId: String, appBinaryPath: File) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun appInstallationStatus(): Map<String, Boolean> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun disposeResources() {
        ignoringDisposeErrors { fbsimctlProc.kill() }
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
        val command = "/usr/local/bin/idevicecrashreport --udid $udid $filter ${crashReportsPath.toAbsolutePath()}"

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

    private fun executeAsync(action: () -> Unit?): Future<*>? {
        val executor = Executors.newSingleThreadExecutor()
        val future =  executor.submit(action)
        executor.shutdown()

        return future
    }

    override fun prepareAsync() {
        if (preparePromise != null) {
            return
        }

        deviceState = DeviceState.NONE

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
        val currentStatus = status()
        var prepareRequired = false

        if (currentStatus.state == DeviceState.FAILED.value) {
            prepareRequired = true
            deviceState = DeviceState.REVIVING
            logger.warn(logMarker, "$this failed, will try to revive")
        }

        renewPromise = executeAsync {
            try {
                if (currentStatus.wda_status) {
                    try {
                        ensureNoAlerts(maxAttempts = 3)
                    } catch (e: Exception) {
                        logger.warn(logMarker, "Ensuring alerts on $this ignored error $e")
                    }
                }

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

    private fun ensureNoAlerts(maxAttempts: Int) {
        val client = WdaClient(commandExecutor = wdaEndpoint.toURL())
        client.attachToSession()

        for (attempt in 1..maxAttempts) {
            val alertText = client.alertText() ?: return

            logger.debug(logMarker, "Will dismiss alert $alertText")
            client.dismissAlert()
            Thread.sleep(1000)
        }
    }

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

        fbsimctlProc.kill()
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

            startFbsimctl()
            startWdaWithRetry()

            logger.info(logMarker, "Finished preparing $this")
            deviceState = DeviceState.CREATED
        }

    }

    private fun startFbsimctl() {
        logger.info(logMarker, "Starting fbsimctl on $this")

        fbsimctlProc.kill()
        fbsimctlProc.start()

        pollFor(
            Duration.ofSeconds(10),
            reasonName = "$this Fbsimctl health check",
            retryInterval = Duration.ofSeconds(1),
            logger = logger,
            marker = logMarker
        ) {
            fbsimctlProc.isHealthy()
        }
    }

    private fun startWda() {
        wdaProc.kill()
        wdaProc.start()

        pollFor(
            Duration.ofMinutes(1),
            reasonName = "$this WebDriverAgent health check",
            retryInterval = Duration.ofSeconds(2),
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
        private val PREPARE_TIMEOUT = Duration.ofMinutes(4)
    }
}