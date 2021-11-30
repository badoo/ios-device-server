package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.errors.DeviceCreationException
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.proc.*
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackupError
import com.badoo.automation.deviceserver.ios.simulator.data.*
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import com.badoo.automation.deviceserver.ios.simulator.video.FFMPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.executeWithTimeout
import com.badoo.automation.deviceserver.util.pollFor
import com.badoo.automation.deviceserver.util.withDefers
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
        private val appConfig: ApplicationConfiguration = ApplicationConfiguration(),
        private val trustStoreFile: String = appConfig.trustStorePath,
        private val assetsPath: String = appConfig.assetsPath
) : ISimulator
{
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        private val ENV_VAR_VALIDATE_REGEX = "[a-zA-Z0-9_]+$".toRegex()
    }

    override val ref = deviceRef
    override val udid: UDID = deviceInfo.udid
    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val userPorts = allocatedPorts
    override val calabashPort: Int = allocatedPorts.calabashPort
    override val mjpegServerPort: Int = allocatedPorts.mjpegServerPort

    private fun createVideoRecorder(): VideoRecorder {
        val recorderClassName = appConfig.videoRecorderClassName

        return when (recorderClassName) {
            SimulatorVideoRecorder::class.qualifiedName -> SimulatorVideoRecorder(
                deviceInfo,
                remote,
                location = Paths.get(deviceSetPath, udid, "video.mp4").toFile()
            )
            MJPEGVideoRecorder::class.qualifiedName -> MJPEGVideoRecorder(
                deviceInfo,
                remote,
                mjpegServerPort,
                ref,
                udid
            )
            FFMPEGVideoRecorder::class.qualifiedName -> FFMPEGVideoRecorder(
                remote,
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
        val wdaClassName = appConfig.simulatorWdaClassName

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
            XcodeTestRunnerDeviceAgent::class.qualifiedName-> XcodeTestRunnerDeviceAgent(
                remote,
                wdaRunnerXctest,
                deviceInfo.udid,
                wdaEndpoint,
                mjpegServerPort,
                deviceRef,
                isRealDevice = false
            )
            else -> throw IllegalArgumentException(
                "Wrong class specified as WDA for Simulator: $wdaClassName. " +
                        "Available are: [${SimulatorWebDriverAgent::class.qualifiedName}, ${SimulatorXcrunWebDriverAgent::class.qualifiedName}]"
            )
        }
    }
    override val deviceAgentLog get() = webDriverAgent.deviceAgentLog

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
                    disposeResources()
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

            dismissTutorials()

//            installTestHelperApp()

            fbsimctlProc.start()

            if (useWda) {
                logTiming("starting WebDriverAgent") { startWdaWithRetry() }
            }

            logger.info(logMarker, "Finished preparing $this")
            startPeriodicHealthCheck()
            deviceState = DeviceState.CREATED
        }
    }

    private fun installTestHelperApp() {
        val testHelperAppBundle = File(appConfig.remoteTestHelperAppBundleRoot, "TestHelper.app")
        if (!remote.shell("test -d ${testHelperAppBundle.absolutePath}").isSuccess) {
            logger.error(logMarker, "Failed to install Test Helper app. App directory does not exist: ${testHelperAppBundle.absolutePath}")
        }

        logger.debug(logMarker, "Installing Test Helper app on Simulator $udid with xcrun simctl")

        val nanos = measureNanoTime {
            val result = remote.execIgnoringErrors(listOf("xcrun", "simctl", "install", udid, testHelperAppBundle.absolutePath), timeOutSeconds = 120)

            if (!result.isSuccess) {
                val errorMessage = "Failed to install TestHelper app $testHelperAppBundle.absolutePath to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
            }

            pollFor(
                Duration.ofSeconds(60),
                "Installing TestHelper host application ${testHelperAppBundle.absolutePath}",
                true,
                Duration.ofSeconds(5),
                logger,
                logMarker
            ) {
                remote.execIgnoringErrors(listOf(
                    "/usr/bin/xcrun",
                    "simctl",
                    "get_app_container",
                    udid,
                    "com.bumble.automation.TestHelper"
                )).isSuccess

            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_TestHelperApp",
            "duration" to seconds
        )
        measurement.putAll(commonLogMarkerDetails)

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed TestHelper app on Simulator with xcrun simctl. Took $seconds seconds")
    }

    private fun dismissTutorials() {
        logger.info(logMarker, "Saving Preference that Continuous Path Introduction was shown")
        writeSimulatorDefaults("com.apple.Preferences DidShowContinuousPathIntroduction -bool true") // iOS 13
        writeSimulatorDefaults("com.apple.keyboard.preferences DidShowContinuousPathIntroduction -bool true") // iOS 14.5 and up
        writeSimulatorDefaults("com.apple.mobileslideshow LastWhatsNewShown -int 7") // iOS 15.0 What's New
        writeSimulatorDefaults("com.apple.suggestions SuggestionsAppLibraryEnabled -bool false") // iOS 15.0 What's New
        writeSimulatorDefaults("com.apple.mt KeepAppsUpToDateAppList -dict com.apple.news 0") // iOS 15.0 News App
        writeSimulatorDefaults("com.apple.suggestions SiriCanLearnFromAppBlacklist -array com.apple.mobileslideshow com.apple.mobilesafari") // iOS 15.0 News App
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

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(30), retryInterval: Duration = Duration.ofSeconds(2)) {
        val maxRetries = 7

        for (attempt in 1..maxRetries) {
            try {
                logger.info(logMarker, "Starting WebDriverAgent on ${this@Simulator}")

                webDriverAgent.kill()
                webDriverAgent.start()

                Thread.sleep(8000)

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

                val wdaLogLines = webDriverAgent.deviceAgentLog.readLines().takeLast(200)
                wdaLogLines.forEach { logLine ->
                    logger.warn(logMarker, "[WDA]: $logLine")
                }

                if (attempt == maxRetries) {
                    throw e
                }
            }
        }
    }

    private fun eraseSimulatorAndCreateBackup() {
        logger.info(logMarker, "Erasing simulator ${this@Simulator} before creating a backup")
        remote.xcrunSimctl.eraseSimulator(udid)

        if (trustStoreFile.isNotEmpty()) {
            copyTrustStore()
        }

        logger.info(logMarker, "Booting ${this@Simulator} before creating a backup")
        logTiming("initial boot") { boot() }

        dismissTutorials()

        if (assetsPath.isNotEmpty()) {
            copyMediaAssetsWithRetry()
        }

        if (useWda && !remote.isLocalhost()) {
            webDriverAgent.installHostApp()
        }


//        installTestHelperApp()

        launchMobileSafari("https://localhost")
        Thread.sleep(5000)
//        2F3F7A68-5D24-4073-96D1-37B57DBA058E % /usr/bin/xcrun simctl spawn 2F3F7A68-5D24-4073-96D1-37B57DBA058E defaults write
//        Command line interface to a user's defaults.
//        Syntax:
//
//        'defaults' [-currentHost | -host <hostname>] followed by one of the following:
//
//        read                                 shows all defaults
//                read <domain>                        shows defaults for given domain
//                read <domain> <key>                  shows defaults for given domain, key
//
//        read-type <domain> <key>             shows the type for the given domain, key
//
//        write <domain> <domain_rep>          writes domain (overwrites existing)
//        write <domain> <key> <value>         writes key for domain
//
//        rename <domain> <old_key> <new_key>  renames old_key to new_key
//
//        delete <domain>                      deletes domain
//        delete <domain> <key>                deletes key in domain
//
//        import <domain> <path to plist>      writes the plist at path to domain
//        import <domain> -                    writes a plist from stdin to domain
//        export <domain> <path to plist>      saves domain as a binary plist to path
//                export <domain> -                    writes domain as an xml plist to stdout
//                domains                              lists all domains
//                find <word>                          lists all entries containing word
//                help                                 print this help
//
//        <domain> is ( <domain_name> | -app <application_name> | -globalDomain )
//        or a path to a file omitting the '.plist' extension
//
//        <value> is one of:
//        <value_rep>
//                -string <string_value>
//        -data <hex_digits>
//        -int[eger] <integer_value>
//        -float  <floating-point_value>
//                -bool[ean] (true | false | yes | no)
//        -date <date_rep>
//        -array <value1> <value2> ...
//        -array-add <value1> <value2> ...
//        -dict <key1> <value1> <key2> <value2> ...
//        -dict-add <key1> <value1> ...

        // +            "com.apple.Preferences" = 1;

//        +    "com.apple.mt" =     {
//            +        KeepAppsUpToDateAppList =         {
//                +            "com.apple.news" = 0;
//                +        };
//            +    };

//        com.apple.voiceservices
//        +        ReduceMotionAutoplayMessagesEffectsEnabled = 0;

//        "com.apple.assistant.support" =     {
//            +        "Assistant Enabled" = 0;

//        +    "com.apple.siri.embeddedspeech" =     {
//            +    };
//
//        +    "com.apple.spotlightui" =     {
//            +        "11.2.Migrated" = 1;
//            +        SBSearchDisabledApps =         (
//                    +            "com.apple.mobileslideshow"
//                            +        );
//            +        SBSearchDisabledBundles =         (
//                    +            "com.apple.mobileslideshow"
//                            +        );
//            +        SBSearchDisabledDomains =         (
//                    +            "DOMAIN_ZKWS",
//            +            "DOMAIN_PARSEC"
//            +        );
//            +        ShowInLookupEnabled = 0;
//            +    };
//        +        SiriCanLearnFromAppBlacklist =         (
//                +            "com.apple.mobileslideshow",
//        +            "com.apple.mobilesafari"
//        +        );

//
        // TODO: wait until PSCoreSpolightIndexerHasPerformediOS13Migration = 1 ?
//        "com.apple.Preferences" =     {
//            +        PSCoreSpolightIndexerHasPerformediOS13Migration = 1;
//            +        PSCoreSpolightIndexerLastIndexBuild = 19A339;
//            +        PSCoreSpolightIndexerLastIndexDate = "2021-11-16 12:53:23 +0000";
//            +        PSCoreSpolightIndexerLastIndexLanguage = en;
//            +        PSCoreSpolightIndexerNeedsIndex = 0;
//            +        VSDeveloperIdentityProviderAvailabilityStatus = 2;
//            +        VSIdentityProviderAvailabilityStatus = 2;
//            +        VSStoreIdentityProviderAvailabilityStatus = 2;
//            +        WebDatabaseDirectory = "/Users/vfrolov/Library/Developer/CoreSimulator/Devices/2F3F7A68-5D24-4073-96D1-37B57DBA058E/data/Library/WebKit/Databases";
//            +        WebKitLocalStorageDatabasePathPreferenceKey = "/Users/vfrolov/Library/Developer/CoreSimulator/Devices/2F3F7A68-5D24-4073-96D1-37B57DBA058E/data/Library/WebKit/LocalStorage";


            logger.info(logMarker, "Shutting down ${this@Simulator} before creating a backup")
        shutdown()

        backup.create()
    }

    private fun openSimulatorApp() {
        try {
            val result = remote.execIgnoringErrors(listOf("/bin/ps", "axo", "pid,stat,command"))
            val simulatorApp = "/Simulator.app/"

            if (result.isSuccess && result.stdOut.lines().none { it.contains(simulatorApp) }) {
                remote.shell("open -a Simulator.app")
            }
        } catch (t: Throwable) {
            logger.error(logMarker, "Failed to launch Simulator.app application. Error ${t.javaClass.name} ${t.message}")
        }
    }

    private fun useSoftwareKeyboard() {
        try {
            val devicePreferencesResult = remote.execIgnoringErrors(listOf("/usr/bin/defaults", "read", "com.apple.iphonesimulator", "DevicePreferences"))
            if (devicePreferencesResult.isSuccess) {
                if (devicePreferencesResult.stdOut.contains(udid)) {
                    return
                }
            }

            val cmd = listOf("/usr/bin/defaults", "write", "com.apple.iphonesimulator", "DevicePreferences", "-dict-add", udid, "'<dict><key>ConnectHardwareKeyboard</key><integer>0</integer></dict>'")
            val result = remote.execIgnoringErrors(cmd)

            val simulatorApp = "/Simulator.app/"

            if (result.isSuccess && result.stdOut.lines().none { it.contains(simulatorApp) }) {
                remote.shell("open -a Simulator.app")
            }
        } catch (t: Throwable) {
            logger.error(logMarker, "Failed to launch Simulator.app application. Error ${t.javaClass.name} ${t.message}")
        }
    }

    private val MEDIA_COPY_ATTEMPTS = 3

    private fun copyMediaAssetsWithRetry() {
        (1..MEDIA_COPY_ATTEMPTS).forEach {
            try {
                logger.info(logMarker, "Copying media assets to simulator. Attempt: $it")
                copyMediaAssets()
                logger.info(logMarker, "Copied media assets to simulator successfully")
                return
            } catch (e: MediaInconsistentcyException) {
                logger.error(e.message)
                if (it == MEDIA_COPY_ATTEMPTS) {
                    throw e
                }
            }
        }
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

        val mediaFiles = File(assetsPath).walk().filter { it.isFile }.toList()
        media.addMedia(mediaFiles)

        val assets = media.list()
        val recordedAssets = media.listPhotoData()

        if (recordedAssets.size != recordedAssets.toSet().size) {
            throw MediaInconsistentcyException("Recorded media contains wrong data. Assets: ${assets.joinToString(",")}. Recorded assets: ${recordedAssets.joinToString(",")}")
        }

        if (assets.size != recordedAssets.size) {
            throw MediaInconsistentcyException("Actual media is in wrong state. Assets: ${assets.joinToString(",")}. Recorded assets: ${recordedAssets.joinToString(",")}")
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
        ignoringErrors({ videoRecorder.dispose() })
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
            shouldReturnOnTimeout = true,
            logger = logger,
            marker = logMarker
        ) {
            isSimulatorShutdown()
        }

        logger.info(logMarker, "Successfully shut down ${this@Simulator}")
    }

    private fun disabledServices(): List<String> {
        val cmdLine = listOf(
            "Spotlight",
            "Spotlight.app",
            "UIKitApplication:com.apple.Spotlight",
            "com.apple.Maps.mapspushd",
            "com.apple.Maps",
            "com.apple.MapsUI",
            "com.apple.Maps.GeneralMapsWidget",
            "com.apple.NPKCompanionAgent",
            "com.apple.SafariBookmarksSyncAgent",
            "com.apple.ScreenTimeAgent",
            "com.apple.GameController.gamecontrollerd",
            "com.apple.ScreenTimeWidgetApplication",
            "com.apple.ScreenTimeWidgetApplication.ScreenTimeWidgetExtension",

            "com.apple.nanonewscd",
//            "com.apple.nanoregistryd",
//            "com.apple.nanoregistrylaunchd",
//            "com.apple.nanoprefsyncd.2",
//            "com.apple.nanomapscd",
//            "com.apple.nanosystemsettingsd",
//            "com.apple.nanobackupd",
//            "com.apple.nanoappregistryd",
//            "com.apple.nanotimekitcompaniond",


            "com.apple.MapKit.SnapshotService",
            "com.apple.Spotlight",
            "com.apple.WallpaperKit",
            "com.apple.WallpaperKit.WallpaperMigrator",
            "com.apple.WebBookmarks.webbookmarksd",
            "com.apple.accessibility.AccessibilityUIServer",
            "com.apple.addressbooksyncd",
            "com.apple.ap.adprivacyd",
            "com.apple.assistant_service",
            "com.apple.assistantd",
            "com.apple.avatarsd",
            "com.apple.bird",
            "com.apple.calaccessd",
            "com.apple.carkitd",
            "com.apple.cloudd",
            "com.apple.companionappd",
            "com.apple.coreservices.useractivityd",
            "com.apple.corespeechd",
            "com.apple.corespotlightservice",
            "com.apple.dataaccess.dataaccessd",
            "com.apple.email.maild",
            "com.apple.familynotification",
            "com.apple.healthappd",
            "com.apple.healthd",
            "com.apple.homed",
//            "com.apple.mobileassetd","com.apple.MobileAsset",
            "com.apple.mobilecal",
            "com.apple.mobilecal.CalendarWidgetExtension",
            "com.apple.mobileslideshow.PhotosReliveWidget",
            "com.apple.mobiletimerd",
            "com.apple.navd",
            "com.apple.news",
            "NewsToday2",
            "com.apple.news.widget",
            "com.apple.news.articlenotificationextension",
            "com.apple.news.NewsArticleQuickLook",
            "com.apple.news.openinnews",
            "com.apple.news.tag",
            "com.apple.news.engagementExtension",
            "com.apple.news.articlenotificationserviceextension",
            "com.apple.news.marketingnotificationextension",
            "com.apple.news.widget",
            "com.apple.news.NewsAudioExtension",
            "com.apple.news.widgetintents",
            "com.apple.news",
            "com.apple.pairedsyncd",
            "com.apple.parsecd", // https://jira.badoojira.com/browse/IOS-33218
            "com.apple.photoanalysisd",
            "com.apple.purplebuddy.budd",
            "com.apple.remindd",
            "com.apple.searchd",
            "com.apple.siri.ClientFlow.ClientScripter",
            "com.apple.siri.context.service",
            "com.apple.siriactionsd",
            "com.apple.suggestd",
            "com.apple.telephonyutilities.callservicesd",
            "com.apple.voiced",
            "spotlight"
//            "com.apple.appstored",
//            "com.apple.itunesstored",
//            "com.apple.passd",
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
                if (remote.isLocalhost()) {
                    useSoftwareKeyboard()
                }

                bootSimulator()

                if (remote.isLocalhost()) {
                    openSimulatorApp()
                }

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

    val simulatorServices = mutableSetOf<RequiredService>()

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

        if (deviceInfo.os.contains("iOS 13") || deviceInfo.os.contains("iOS 14") || deviceInfo.os.contains("iOS 15")) {
            cmd.add("--process")
            cmd.add("SpringBoard")
        }

        cmd.add("--predicate")
        cmd.add(predicate)

        val requiredServices = mutableSetOf<RequiredService>()

        if (deviceInfo.os.contains("iOS 12")) {
            requiredServices.add(RequiredService.SpotlightIos12())
        } else {
            requiredServices.add(RequiredService.Spotlight())
        }

        val longWaitedServices = mutableSetOf<RequiredService>()
        longWaitedServices.add(RequiredService.Locationd())

        simulatorServices.clear()
        simulatorServices.addAll(requiredServices)
        simulatorServices.addAll(longWaitedServices)

//        remote.localExecutor.exec(listOf("/Users/vfrolov/GitHub/ios-device-server-badoo/device-server/simulator_logs_record.sh", udid))

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

            longWaitedServices.forEach { service ->
                if (line.contains(service.identifier)) {
                    service.booted = true
                }
            }

            if (requiredServices.all { it.booted } && longWaitedServices.all { it.booted }) {
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

        pollFor(
            timeOut = Duration.ofMinutes(3),
            reasonName = "Simulator boot process",
            shouldReturnOnTimeout = true,
            retryInterval = Duration.ofMillis(250L),
            logger = logger,
            marker = logMarker
        ) {
            requiredServices.all { it.booted }
        }

        process.destroy()

        if (requiredServices.any { !it.booted }) {
            val failedServices = requiredServices.filter { !it.booted }
            val failedServicesMessage = if (failedServices.isNotEmpty()) "Failed services [${failedServices.joinToString(", ")}]" else ""
            val errorMessage = "Simulator $udid failed to successfully boot to sufficient state. $failedServicesMessage. StdErr: $stdErr. StdOut: $stdOut"
            logger.error(logMarker, "Simulator $udid log has not exited in time. Possible errors. StdErr: $stdErr. StdOut: $stdOut")
            logger.error(logMarker, errorMessage)
            throw DeviceCreationException(errorMessage)
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

    private fun launchMobileSafari(url: String) {
        remote.shell("/usr/bin/xcrun simctl openurl $udid $url", true)
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
                        shutdown()
                        disposeResources()
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
            last_error = lastException?.toDTO(),
            simulator_services = simulatorServices.toSet()
        )
    }
    //endregion

    override fun endpointFor(port: Int): URL {
        val ports = allocatedPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    //region approveAccess

    override fun setPermissions(bundleId: String, permissions: PermissionSet) {
        SimulatorPermissions(remote, udid).setPermissions(bundleId, permissions)
    }

    override fun sendPushNotification(bundleId: String, notificationContent: ByteArray) {
        withDefers(logger) {
            val pushNotificationFile: File = File.createTempFile("push_notification_${deviceInfo.udid}_", ".json")
            defer { pushNotificationFile.delete() }
            pushNotificationFile.writeBytes(notificationContent)

            val pushNotificationPath: String = if (remote.isLocalhost()) {
                pushNotificationFile.absolutePath
            } else {
                val remotePushNotificationDir = remote.execIgnoringErrors(listOf("/usr/bin/mktemp", "-d")).stdOut.trim()
                defer { remote.execIgnoringErrors(listOf("/bin/rm", "-rf", remotePushNotificationDir)) }
                remote.scpToRemoteHost(pushNotificationFile.absolutePath, remotePushNotificationDir, Duration.ofMinutes(1))
                File(remotePushNotificationDir, pushNotificationFile.name).absolutePath
            }

            val result = remote.execIgnoringErrors(listOf("/usr/bin/xcrun", "simctl", "push", udid, bundleId, pushNotificationPath))

            if (!result.isSuccess) {
                throw RuntimeException("Could not simulate push notification to device $udid: $result")
            }
        }
    }

    override fun sendPasteboard(payload: ByteArray) {
        withDefers(logger) {
            val pasteboardPayloadFile: File = File.createTempFile("pasteboard_${deviceInfo.udid}_", ".data")
            defer { pasteboardPayloadFile.delete() }
            pasteboardPayloadFile.writeBytes(payload)

            val pasteboardPayloadPath: String = if (remote.isLocalhost()) {
                pasteboardPayloadFile.absolutePath
            } else {
                val remotePasteboardDir = remote.execIgnoringErrors(listOf("/usr/bin/mktemp", "-d")).stdOut.trim()
                defer { remote.execIgnoringErrors(listOf("/bin/rm", "-rf", remotePasteboardDir)) }
                remote.scpToRemoteHost(pasteboardPayloadFile.absolutePath, remotePasteboardDir, Duration.ofMinutes(1))
                File(remotePasteboardDir, pasteboardPayloadFile.name).absolutePath
            }

            val result = remote.shell("cat $pasteboardPayloadPath | /usr/bin/xcrun simctl pbcopy -v $udid")

            if (!result.isSuccess) {
                throw RuntimeException("Could not send pasteboard to device $udid: $result")
            }
        }
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
        val chmodResult = remote.execIgnoringErrors(listOf("/bin/chmod", "-RP", "755", simulatorDataDirectoryPath), timeOutSeconds = 120L)
        if (!chmodResult.isSuccess) {
            logger.error(logMarker, "Failed to chmod at path: [$simulatorDataDirectoryPath]. Result: $chmodResult")
        }

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
//        deleteSimulatorKeepingMetadata()
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

    override fun listApps(): List<FBSimctlAppInfo> = remote.fbsimctl.listApps(udid)

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

    override fun sharedContainer(): SharedContainer {
        val sharedResourceDirectory = getEnvironmentVariable("SIMULATOR_SHARED_RESOURCES_DIRECTORY")

        return fileSystem.sharedContainer(sharedResourceDirectory)
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

    override fun getEnvironmentVariable(variableName: String): String {
        logger.debug(logMarker, "Getting environment variable $variableName for Simulator $this")
        if(!ENV_VAR_VALIDATE_REGEX.matches(variableName)) {
            throw IllegalArgumentException("Variable name should contain only letters, numbers and underscores. Current value: $variableName")
        }

        return remote.shell("xcrun simctl getenv $udid $variableName").stdOut.trim() // remove last new_line
    }
}
