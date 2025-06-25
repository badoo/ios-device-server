package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.proc.*
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackupError
import com.badoo.automation.deviceserver.ios.simulator.data.*
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.video.FFMPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.*
import kotlinx.coroutines.Runnable
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.*
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.*
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
    wdaSimulatorBundles: WdaSimulatorBundles,
    private val concurrentBootsPool: ExecutorService,
    headless: Boolean,
    private val useWda: Boolean,
    private val appConfig: ApplicationConfiguration = ApplicationConfiguration(),
    private val trustStoreFile: String = appConfig.trustStorePath,
    private val assetsPath: String = appConfig.assetsPath
) : ISimulator {
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        private val ENV_VAR_VALIDATE_REGEX = "[a-zA-Z0-9_]+$".toRegex()
    }

    override val ref = deviceRef
    override val udid: UDID = deviceInfo.udid
    override val wdaEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val calabashEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.calabashPort}/")
    override val calabashPort: Int = allocatedPorts.calabashPort
    override val mjpegServerPort: Int = allocatedPorts.mjpegServerPort
    override val locationManager: LocationManager = LocationManager(remote, udid)

    private fun createVideoRecorder(): VideoRecorder {
        val recorderClassName = appConfig.videoRecorderClassName

        return when (recorderClassName) {
            FFMPEGVideoRecorder::class.qualifiedName -> FFMPEGVideoRecorder(
                remote,
                mjpegServerPort,
                ref,
                udid
            )

            else -> throw IllegalArgumentException(
                "Wrong class specified as video recorder: $recorderClassName. " +
                        "Available are: [${FFMPEGVideoRecorder::class.qualifiedName}]"
            )
        }
    }

    override val videoRecorder: VideoRecorder = createVideoRecorder()

    override val osLog = OsLog(remote, udid)

    //region instance state variables
    private val deviceLock = ReentrantLock()
    @Volatile
    override var deviceState: DeviceState = DeviceState.NONE // writing from separate thread
        private set

    @Volatile
    override var lastException: Exception? = null // writing from separate thread
        private set

    private val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)

    private val instrumentationAgent = XCTestInstrumentationAgent(
        remote,
        listOf(wdaSimulatorBundles.deviceAgentBundle, wdaSimulatorBundles.webDriverAgentBundle),
        deviceInfo,
        wdaEndpoint,
        mjpegServerPort,
        deviceRef,
        isRealDevice = false
    )

    override val instrumentationAgentLog get() = instrumentationAgent.deviceAgentLog
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
    @Volatile
    private var healthChecker: ScheduledFuture<*>? = null
    //endregion

    override val media: Media = Media(remote, udid, deviceSetPath)

    override fun toString() = "<Simulator: $deviceRef>"

    @Volatile
    private var bootTask: Future<*>? = null

    @Volatile
    private var installTask: Future<InstallResult>? = null

    override fun getInstallTask(): Future<InstallResult>? = installTask

    override fun installApplication(
        appInstaller: AppInstaller,
        appBundleId: String,
        appBinaryPath: File,
        bundleId: String
    ) {
        deviceLock.withLock {
            installTask?.let { oldInstallTask ->
                if (!oldInstallTask.isDone) {
                    val message = "Failed to install app $appBundleId to simulator $udid due to previous task is not finished"
                    logger.error(logMarker, message)
                    throw RuntimeException(message)
                }
            }

            installTask = appInstaller.installApplication(udid, appBundleId, appBinaryPath, false, bundleId)
        }
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

            boot()

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
                remote.execIgnoringErrors(
                    listOf(
                        "/usr/bin/xcrun",
                        "simctl",
                        "get_app_container",
                        udid,
                        "com.bumble.automation.TestHelper"
                    )
                ).isSuccess

            }
        }

        val seconds = NANOSECONDS.toSeconds(nanos)
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

        val wdaFailCount = 0
        val maxFailCount = 3
        val healthCheckInterval = Duration.ofSeconds(60).toMillis()

        healthChecker = periodicTasksPool.scheduleWithFixedDelay({
            if (useWda) {
                performInstrumentationAgentHealthCheck(wdaFailCount, maxFailCount)
            }
        }, 0, healthCheckInterval, TimeUnit.MILLISECONDS)

    }

    private fun performInstrumentationAgentHealthCheck(wdaFailCount: Int, maxFailCount: Int) {
        var wdaFailCount1 = wdaFailCount
        if (instrumentationAgent.isHealthy()) {
            wdaFailCount1 = 0
        } else {
            (1..5).forEach {
                if (Thread.currentThread().isInterrupted) {
                    logger.error(logMarker, "Health check interrupted for $instrumentationAgent")
                    return
                }
                if (instrumentationAgent.isHealthy()) {
                    wdaFailCount1 = 0
                    return@forEach
                } else {
                    val message = "$instrumentationAgent health check failed $wdaFailCount1 times."
                    logger.error(logMarker, message)
                    wdaFailCount1 += 1
                    Thread.sleep(Duration.ofSeconds(2).toMillis())
                }
            }

            if (wdaFailCount1 >= maxFailCount) {
                logger.error(logMarker, "$instrumentationAgent health check failed $wdaFailCount1 times. Restarting WebDriverAgent")

                try {
                    instrumentationAgent.kill()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to kill $instrumentationAgent. ${e.message}", e)
                }

                try {
                    startWdaWithRetry()
                } catch (e: RuntimeException) {
                    logger.error(logMarker, "Failed to restart $instrumentationAgent. ${e.message}", e)
                    deviceState = DeviceState.FAILED
                    throw RuntimeException("${this@Simulator} Failed to restart $instrumentationAgent. Stopping health check")
                }
            }
        }
    }

    private fun stopPeriodicHealthCheck() {
        healthChecker?.let { checker ->
            cancelTask(checker, "health checker")
        }
    }

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(60), retryInterval: Duration = Duration.ofSeconds(2)) {
        val maxRetries = 7

        for (attempt in 1..maxRetries) {
            if (Thread.currentThread().isInterrupted) {
                logger.error(logMarker, "Start $instrumentationAgent with retry interrupted")
                return
            }

            try {
                logger.info(logMarker, "Starting $instrumentationAgent")

                instrumentationAgent.kill()
                instrumentationAgent.start()

                Thread.sleep(8000)

                pollFor(
                    pollTimeout,
                    reasonName = "${this@Simulator} $instrumentationAgent health check",
                    retryInterval = retryInterval,
                    logger = logger,
                    marker = logMarker
                ) {
                    instrumentationAgent.isHealthy()
                }

                logger.info(logMarker, "Started $instrumentationAgent on ${this@Simulator}")

                return
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Attempt $attempt to start $instrumentationAgent for ${this@Simulator} failed: $e")

                val wdaLogLines = instrumentationAgent.deviceAgentLog.readLines().takeLast(200)
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

        if (appConfig.useTestHelperApp) {
            installTestHelperApp()
        }

        launchMobileSafari("https://localhost")
        Thread.sleep(5000)

        logger.info(logMarker, "Shutting down ${this@Simulator} before creating a backup")
        shutdown()

        backup.create()
    }

    private fun useSoftwareKeyboard() {
        try {
            val devicePreferencesResult = remote.execIgnoringErrors(listOf("/usr/bin/defaults", "read", "com.apple.iphonesimulator", "DevicePreferences"))
            if (devicePreferencesResult.isSuccess) {
                if (devicePreferencesResult.stdOut.contains(udid)) {
                    return
                }
            }

            val dict = "<dict><key>ConnectHardwareKeyboard</key><integer>0</integer></dict>"
            val cmd = listOf("/usr/bin/defaults", "write", "com.apple.iphonesimulator", "DevicePreferences", "-dict-add", udid, dict)
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

        remote.shell("cp $trustStoreFile $keyChainLocation", returnOnFailure = false)

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

    private fun deleteSimulator() {
        logger.debug(logMarker, "Will delete simulator $udid")
        val result: CommandResult = remote.fbsimctl.delete(udid)
        if (result.isSuccess) {
            logger.debug(logMarker, "Did delete simulator $udid")
        } else {
            logger.error(logMarker, "Error occurred while deleting simulator $udid. Command exit code: ${result.exitCode}. Result stdErr: ${result.stdErr}")
        }
    }

    private fun cancelTask(task: Future<*>, taskName: String) {
        task.cancel(true)
        val timeOut = Duration.ofSeconds(30)
        val stopTime = System.nanoTime() + timeOut.toNanos()

        while (!task.isDone) {
            if (System.nanoTime() > stopTime) {
                logger.error(logMarker, "Task $taskName was not cancelled in time. Was waiting for ${timeOut.seconds} seconds")
                break
            }

            Thread.sleep(50)
        }
    }

    private fun shutdown() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")
        stopPeriodicHealthCheck()

        bootTask?.let {
            cancelTask(it, "bootTask")
        }

        installTask?.let {
            cancelTask(it, "installTask")
        }

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val tasks = setOf(
            { ignoringErrors({ videoRecorder.dispose() }) },
            { ignoringErrors({ instrumentationAgent.kill() }) },
        ).map { executor.submit(it) }
        tasks.forEach { it.get() }

        val result = remote.fbsimctl.shutdown(udid)

        if (!result.isSuccess && !result.stdErr.contains("current state: Shutdown") && !result.stdOut.contains("current state: Shutdown")) {
            logger.debug(logMarker, "Error occurred while shutting down simulator $udid. Command exit code: ${result.exitCode}. Result stdErr: ${result.stdErr}")
        }

        pollFor(
            timeOut = Duration.ofSeconds(60),
            retryInterval = Duration.ofSeconds(1),
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
            "com.apple.accessibility.AccessibilityUIServer",
            "com.apple.activityawardsd", // Manages user awards/badges in Activity app.
            "com.apple.activitysharingd", // Activity sharing with contacts (Fitness).
            "com.apple.addressbooksyncd",
            "com.apple.AMPIDService", // Apple Music personalized recommendations.
            "com.apple.amsengagementd", // App Store/Music notifications and promotions.
            "com.apple.announced", // Announce notifications (VoiceOver).
            "com.apple.ap.adprivacyd", // App privacy ad measurement.
            "com.apple.ap.promotedcontentd", // Apple-promoted content/ad handling.
            "com.apple.assistant_service",
            "com.apple.assistantd",
            "com.apple.avatarsd", // Memoji/avatar services.
            "com.apple.Batteries.BatteriesWidget",
            "com.apple.biomed", // Health-related biomedical data.
            "com.apple.biomesyncd", // Sync health data between devices.
            "com.apple.bird",
            "com.apple.calaccessd",
            "com.apple.carkitd", // CarPlay interactions.
            "com.apple.chronod", // it launches all the following services for the widgets
            "com.apple.cloudd",
            "com.apple.companionappd",
            "com.apple.coreservices.useractivityd",
            "com.apple.corespeechd", // Voice/Speech recognition.
            "com.apple.corespotlightservice",
            "com.apple.dataaccess.dataaccessd",
            "com.apple.diagnosticd",
            "com.apple.diagnosticextensionsd",
            "com.apple.donotdisturbd",
            "com.apple.email.maild", // Mail app background operations.
            "com.apple.familycircled", // Family sharing service.
            "com.apple.FamilyControlsAgent", // Parental control service.
            "com.apple.familynotification", // Family sharing notifications.
            "com.apple.findmy.findmylocated", // "Find My" location tracking.
            "com.apple.fitcore",
            "com.apple.fitnesscoachingd", // Fitness app logic.
            "com.apple.gamecontroller.ConfigService", // Game controller input.
            "com.apple.GameController.gamecontrollerd",
            "com.apple.gamecontrollerd", // Game controller input.
            "com.apple.gamed", // Game Center interactions.
            "com.apple.geoanalyticsd", // Location analytics.
            "com.apple.Health.Sleep.SleepWidgetExtension",
            "com.apple.healthappd",
            "com.apple.healthd",
            "com.apple.healthrecordsd", // Health/medical records.
            "com.apple.homed", // HomeKit automation.
            "com.apple.icloudmailagent", // iCloud mail background handling.
            "com.apple.intelligenceplatformd",
            "com.apple.MapKit.SnapshotService",
            "com.apple.Maps.GeneralMapsWidget",
            "com.apple.Maps.mapspushd",
            "com.apple.Maps.mapssyncd", // Maps services.
            "com.apple.Maps",
            "com.apple.MapsUI",
            "com.apple.mediaanalysisd", // Analyzes user media (photos/videos).
            "com.apple.mediaremoted",
            "com.apple.mobilecal.CalendarWidgetExtension",
            "com.apple.mobilecal",
            "com.apple.mobileslideshow.PhotosReliveWidget",
            "com.apple.mobiletimerd",
            "com.apple.nanoappregistryd",
            "com.apple.nanobackupd",
            "com.apple.nanomapscd",
            "com.apple.nanonewscd",
            "com.apple.nanoprefsyncd.2",
            "com.apple.nanoregistryd",
            "com.apple.nanoregistrylaunchd",
            "com.apple.nanosystemsettingsd",
            "com.apple.nanotimekitcompaniond",
            "com.apple.navd",
            "com.apple.news.articlenotificationextension",
            "com.apple.news.articlenotificationserviceextension",
            "com.apple.news.engagementExtension",
            "com.apple.news.marketingnotificationextension",
            "com.apple.news.NewsArticleQuickLook",
            "com.apple.news.NewsAudioExtension",
            "com.apple.news.openinnews",
            "com.apple.news.tag",
            "com.apple.news.widget",
            "com.apple.news.widgetintents",
            "com.apple.news",
            "com.apple.newscore",
            "com.apple.newscore2",
            "com.apple.newsd", // Apple News content handling.
            "com.apple.NPKCompanionAgent", // Apple Pay companion tasks.
            "com.apple.pairedsyncd",
            "com.apple.parsecd", // https://jira.badoojira.com/browse/IOS-33218
            "com.apple.Passbook.PassbookWidgets",
            "com.apple.PassbookStub.PassbookWidgets",
            "com.apple.PeopleViewService.PeopleWidget-iOS",
            "com.apple.photoanalysisd", // Photo library analysis (face recognition, etc.).
            "com.apple.PosterBoard", // iOS 16
            "com.apple.posterboardservices", // iOS 16
            "com.apple.purplebuddy.budd", // Initial device setup wizard.
            "com.apple.remindd", // Reminders app
            "com.apple.reminders.WidgetExtension",
            "com.apple.remotemanagementd",
            "com.apple.Safari.passwordbreachd", // Checks compromised passwords.
            "com.apple.SafariBookmarksSyncAgent",
            "com.apple.schooltimed", // ScreenTime management for educational environments.
            "com.apple.ScreenTimeAgent",
            "com.apple.ScreenTimeWidgetApplication.ScreenTimeWidgetExtension",
            "com.apple.ScreenTimeWidgetApplication",
            "com.apple.searchd",
            "com.apple.siri.ClientFlow.ClientScripter",
            "com.apple.siri.context.service",
            "com.apple.siriactionsd",
            "com.apple.siriinferenced",
            "com.apple.siriknowledged",
            "com.apple.sleepd", // Sleep tracking logic.
            "com.apple.suggestd",
            "com.apple.telephonyutilities.callservicesd",
            "com.apple.tvremoted", // Apple TV remote handling.
            "com.apple.UsageTrackingAgent",
            "com.apple.videosubscriptionsd", // Video subscription management (Apple TV).
            "com.apple.voicebankingd", // Voice training for accessibility.
            "com.apple.voiced",
            "com.apple.WallpaperKit.WallpaperMigrator",
            "com.apple.WallpaperKit",
            "com.apple.weatherd", // Weather data updates.
            "com.apple.WebBookmarks.webbookmarksd",
            "com.apple.webkit.adattributiond", // Web ad tracking attribution.
//            "NewsToday2",
        ).map {
            "--disabledJob=$it"
        }

        return cmdLine
    }

    private fun bootSimulator() {
        val cmd = listOf("/usr/bin/xcrun", "simctl", "boot", udid) + disabledServices()
        remote.exec(cmd, mapOf(), false, 120L)
    }

    private fun boot() {
        bootTask?.let { oldBootTask ->
            if (!oldBootTask.isDone) {
                val message = "Failed to boot simulator $udid due to previous task is not finished. Call shutdown() to cancel it."
                logger.error(logMarker, message)
                throw RuntimeException(message)
            }
        }

        logger.info(logMarker, "Booting ${this@Simulator} asynchronously")
        val task = concurrentBootsPool.submit { // using limited number of workers to boot simulator
            val nanos = measureNanoTime {
                useSoftwareKeyboard()
                bootSimulator()
                waitUntilSimulatorBooted()
                dismissTutorials()

                if (appConfig.useTestHelperApp) {
                    installTestHelperApp()
                }

                if (useWda) {
                    logTiming("starting $instrumentationAgent") { startWdaWithRetry() }
                }
            }

            val timingMarker = MapEntriesAppendingMarker(commonLogMarkerDetails + mapOf("simulatoBootTime" to NANOSECONDS.toSeconds(nanos)))
            logger.info(timingMarker, "Device ${this@Simulator} is sufficiently booted")
        }

        bootTask = task
        task.get()
    }

    private fun waitUntilSimulatorBooted() {
        Thread.sleep(5000L) // make sure enough time for initial boot before any other actions
        val startTime = System.nanoTime()
        val bootResult = remote.exec(listOf("/usr/bin/xcrun", "simctl", "bootstatus", udid), mapOf(), true, 240)
        val finishTime = System.nanoTime()
        val elapsedSeconds = NANOSECONDS.toSeconds(finishTime - startTime)
        if (bootResult.isSuccess) {
            val message = "Simulator bootstatus $udid successfully booted to sufficient state. Was waiting for $elapsedSeconds seconds."
            logger.info(logMarker, message)
        } else {
            val message =
                "Simulator bootstatus $udid failed to successfully boot to sufficient state. Was waiting for $elapsedSeconds seconds. Exit code: ${bootResult.exitCode}. StdErr: ${bootResult.stdErr}. StdOut: ${bootResult.stdOut}"
            logger.error(logMarker, message)
        }
    }

    private fun writeSimulatorDefaults(setting: String) {
        remote.shell("/usr/bin/xcrun simctl spawn $udid defaults write $setting", true)
    }

    private fun launchMobileSafari(url: String) {
        remote.shell("/usr/bin/xcrun simctl openurl $udid $url", true)
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
        var isWdaReady = false

        if (deviceState == DeviceState.CREATED) {
            isWdaReady = (if (useWda) {
                instrumentationAgent.isHealthy()
            } else true)
        }

        val isSimulatorReady = deviceState == DeviceState.CREATED && isWdaReady

        return SimulatorStatusDTO(
            ready = isSimulatorReady,
            wdaStatus = isWdaReady,
            state = deviceState.value,
            lastError = lastException?.toDTO()
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

            val pushNotificationPath: String = pushNotificationFile.absolutePath

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

            val pasteboardPayloadPath: String = pasteboardPayloadFile.absolutePath

            val result = remote.shell("cat $pasteboardPayloadPath | /usr/bin/xcrun simctl pbcopy -v $udid")

            if (!result.isSuccess) {
                throw RuntimeException("Could not send pasteboard to device $udid: $result")
            }
        }
    }

    //endregion

    //region release
    override fun release(reason: String) {
        logTiming("Full set of actions to release simulator $udid on host ${remote.publicHostName}") {
            logTiming("Shutdown simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ shutdown() }) }
            logTiming("Dispose resources for simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ disposeResources() }) }
        }
    }

    override fun delete(reason: String) {
        release(reason)
        logTiming("Full set of actions to delete simulator $udid on host ${remote.publicHostName}") {
            logTiming("Delete backup for simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ backup.delete() }) }
            logTiming("Delete simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ deleteSimulator() }) }
            logTiming("Dispose resources for simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ disposeResources(keepMetadata = false) }) }
        }
    }

    private fun deleteSimulatorFolder(keepMetadata: Boolean) {
        val directoryPath = if (keepMetadata) simulatorDataDirectory.absolutePath else simulatorDirectory.absolutePath

        (1..3).any {
            val chmodResult = remote.execIgnoringErrors(listOf("/bin/chmod", "-RP", "755", directoryPath), timeOutSeconds = 120L)

            if (!chmodResult.isSuccess) {
                logger.error(logMarker, "Attempt number $it: Failed to chmod at path: [$directoryPath]. Result: $chmodResult")
            }

            val deleteResult = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", directoryPath), timeOutSeconds = 120L)

            if (!deleteResult.isSuccess) {
                logger.error(logMarker, "Attempt number $it: Failed to delete at path: [$directoryPath]. Result: $deleteResult")
            }

            deleteResult.isSuccess
        }
    }

    private fun disposeResources(keepMetadata: Boolean = true) {
        ignoringErrors({ videoRecorder.dispose() })
        deleteSimulatorFolder(keepMetadata)
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

    override fun shake(): Boolean {
        val command = listOf("xcrun", "simctl", "notify_post", udid, "com.apple.UIKit.SimulatorShake")
        val result = remote.execIgnoringErrors(command)
        return result.isSuccess
    }

    override fun openUrl(url: String): Boolean {
        val urlString = url
        val command = listOf("/usr/bin/xcrun", "simctl", "openurl", udid, urlString)
        val result = remote.execIgnoringErrors(command)

        if (!result.isSuccess) {
            logger.error("Failed to open url $url \nResult:\n$result")
            throw RuntimeException("Failed to open url $url \nResult:\n$result")
        }

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
    private fun listCrashLogs(pastMinutes: Long? = null): List<String> {
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
        if (!ENV_VAR_VALIDATE_REGEX.matches(variableName)) {
            throw IllegalArgumentException("Variable name should contain only letters, numbers and underscores. Current value: $variableName")
        }

        return remote.shell("xcrun simctl getenv $udid $variableName").stdOut.trim() // remove last new_line
    }
}
