package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.proc.*
import com.badoo.automation.deviceserver.ios.simulator.data.*
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.video.FFMPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.simctl.SimCtlUtility
import com.badoo.automation.deviceserver.util.*
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.*
import java.net.URI
import java.net.URL
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
    wdaSimulatorBundles: WdaSimulatorBundles,
    private val useWda: Boolean,
    private val appConfig: ApplicationConfiguration = ApplicationConfiguration(),
    private val trustStorePath: String = appConfig.trustStorePath,
    private val assetsPath: String = appConfig.assetsPath
) : ISimulator {
    private companion object {
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
        private val ENV_VAR_VALIDATE_REGEX = "[a-zA-Z0-9_]+$".toRegex()
    }

    private val simCtlUtility: SimCtlUtility = SimCtlUtility(remote.commandExecutor)

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

    override val media: Media = Media(remote, udid, File(appConfig.homeDirectory, "Library/Developer/CoreSimulator/Devices").absolutePath)

    override fun toString() = "<Simulator: $deviceRef>"

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
        throw NotImplementedError("This is not implemented for Simulator")
    }

    var simulatorBootExecutor: ExecutorService? = null

    override fun bootAndPrepareSimulator(concurrentBootsSemaphore: Semaphore, isSimulatorClone: Boolean) {
        val startTime = System.nanoTime()
        executeCriticalWithLock {
            val simulatorType = if (isSimulatorClone) "Cloned" else "Base"
            if (deviceState == DeviceState.CREATING) {
                throw java.lang.IllegalStateException("$simulatorType Simulator $udid is already in state $deviceState")
            }

            logger.info(logMarker, "Starting to boot and prepare $simulatorType ${this@Simulator}")

            deviceState = DeviceState.CREATING

            val bootExecutor: ExecutorService = Executors.newSingleThreadExecutor()
            simulatorBootExecutor = bootExecutor

            scheduleUseSoftwareKeyboard(bootExecutor)

            if (!isSimulatorClone) {
                scheduleCopyTrustStore(bootExecutor)
            }

            scheduleBootSimulator(bootExecutor, concurrentBootsSemaphore)
            scheduleDismissTutorials(bootExecutor)

            if (!isSimulatorClone) {
                scheduleCopyMediaAssets(bootExecutor)
                scheduleLaunchMobileSafari(bootExecutor)
                scheduleWaitForCoreMigrations(bootExecutor)
            }

            if (useWda && isSimulatorClone) {
                scheduleSequentialExecution(
                    bootExecutor,
                    { logTiming("starting $instrumentationAgent") { startWdaWithRetry() } },
                    { "Failed to start instrumentation on simulator $udid" },
                )
            }

            if (isSimulatorClone) {
                scheduleSequentialExecution(
                    bootExecutor,
                    { startPeriodicHealthCheck() },
                    { "Failed to start periodic health checks on simulator $udid" },
                )
            }

            scheduleSequentialExecution(
                bootExecutor,
                {
                    logger.info(logMarker, "Finished preparing simulator $this")
                    deviceState = DeviceState.CREATED
                },
                { "Failed to prepare simulator $udid" },
            )

            bootExecutor.shutdown()

            var isBootFinishedGracefully = false
            var isBootInterrupted = false

            try {
                isBootFinishedGracefully = bootExecutor.awaitTermination(10, TimeUnit.MINUTES)
            } catch (e: InterruptedException) {
                isBootInterrupted = true
            }

            if (!isBootFinishedGracefully && !isBootInterrupted) {
                logger.warn(logMarker, "Simulator $udid did not finish booting gracefully. Cancelling boot sequence.")
                cancelBootSequence()
            }

            val nanos = System.nanoTime() - startTime

            val seconds = NANOSECONDS.toSeconds(nanos)
            val measurement = mutableMapOf(
                "action_name" to "bootAndPrepareSimulator",
                "is_base_simulator" to !isSimulatorClone,
                "duration" to seconds,
                "is_success" to isBootFinishedGracefully,
                "is_interrupted" to isBootInterrupted,
            )
            measurement.putAll(commonLogMarkerDetails)

            val message: String = if (isBootFinishedGracefully) {
                "finished gracefully. Total simulator preparation time took $seconds seconds"
            } else if (isBootInterrupted) {
                "was interrupted. Interrupted after $seconds seconds"
            } else {
                "did not finish gracefully. Timed out after $seconds seconds"
            }

            logger.info(MapEntriesAppendingMarker(measurement), "$simulatorType ${this@Simulator} boot process ended: $message")

            if (isBootInterrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun scheduleLaunchMobileSafari(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            {
                launchMobileSafari("https://localhost")
                Thread.sleep(Duration.ofSeconds(10)) // give Safari some time to launch
            },
            { "Failed to launch MobileSafari on simulator $udid" },
        )
    }

    private fun scheduleWaitForCoreMigrations(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            {
                Thread.sleep(Duration.ofSeconds(180)) // TODO: replace with proper check for core migrations. Downloading Assets can take a while, so we need to wait for them to finish.
            },
            { "Failed to wait for core migrations on simulator $udid" },
        )
    }

    private fun scheduleCopyMediaAssets(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            {
                copyMediaAssets()
            },
            { "Failed to copy media assets to simulator $udid" },
        )
    }

    private fun scheduleDismissTutorials(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            {
                dismissTutorials()
            },
            { "Failed to dismiss tutorials for simulator $udid" },
        )
    }

    private fun scheduleBootSimulator(simulatorBootExecutor: ExecutorService, concurrentBootsSemaphore: Semaphore) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            {
                concurrentBootsSemaphore.withSemaphore("Boot operation of ${this@Simulator}", logger, logMarker) {
                    logger.info(logMarker, "Booting ${this@Simulator}")
                    val nanos = measureNanoTime {
                        simCtlUtility.bootSimulator(udid = udid, disabledServices = disabledServices(), timeOut = Duration.ofSeconds(180L), logMarker = logMarker)

                        Thread.sleep(1000L) // make sure enough time for initial boot before any other actions

                        simCtlUtility.bootStatusSimulator(udid, Duration.ofSeconds(180), logMarker)
                    }
                    val timingMarker = MapEntriesAppendingMarker(commonLogMarkerDetails + mapOf("simulatoBootTime" to NANOSECONDS.toSeconds(nanos)))
                    logger.info(timingMarker, "Device ${this@Simulator} is sufficiently booted")
                }
            },
            { "Failed to boot simulator $udid" },
        )
    }

    private fun scheduleUseSoftwareKeyboard(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            { useSoftwareKeyboard() },
            { "Failed to set up software keyboard for simulator $udid" },
        )
    }

    private fun scheduleCopyTrustStore(simulatorBootExecutor: ExecutorService) {
        scheduleSequentialExecution(
            simulatorBootExecutor,
            { copyTrustStore() },
            { "Failed to copy TrustStore for simulator $udid" },
        )
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

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(120), retryInterval: Duration = Duration.ofSeconds(1)) {
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            if (Thread.currentThread().isInterrupted) {
                logger.error(logMarker, "Start $instrumentationAgent with retry interrupted")
                return
            }

            try {
                logger.info(logMarker, "Starting $instrumentationAgent")

                instrumentationAgent.kill()
                instrumentationAgent.start()

                pollFor(
                    pollTimeout, reasonName = "${this@Simulator} $instrumentationAgent health check", retryInterval = retryInterval, logger = logger, marker = logMarker
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

    private fun useSoftwareKeyboard() {
        val devicePreferencesResult = remote.execIgnoringErrors(listOf("/usr/bin/defaults", "read", "com.apple.iphonesimulator", "DevicePreferences"))
        if (devicePreferencesResult.isSuccess) {
            if (devicePreferencesResult.stdOut.contains(udid)) {
                return
            }
        }

        val dict = "<dict><key>ConnectHardwareKeyboard</key><integer>0</integer></dict>"
        val cmd = listOf("/usr/bin/defaults", "write", "com.apple.iphonesimulator", "DevicePreferences", "-dict-add", udid, dict)
        val result = remote.execIgnoringErrors(cmd)

        if (!result.isSuccess) {
            logger.error(logMarker, "Failed to set up software keyboard for simulator $udid. Result: $result")
        }
    }

    private fun copyTrustStore() {
        val trustStore = File(trustStorePath)

        if (trustStorePath.isBlank() || !trustStore.exists()) {
            logger.warn("Trust store file $trustStorePath does not exist")
            return
        }

        logger.debug(logMarker, "Copying trust store to ${this@Simulator}")
        val targetTrustStore = File(appConfig.homeDirectory, "Library/Developer/CoreSimulator/Devices/${udid}/data/Library/Keychains/${trustStore.name}")
        trustStore.copyTo(targetTrustStore, overwrite = true)
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

    private fun isSimulatorShutdown(): Boolean {
        val simulator = simCtlUtility.listDevices().values.flatten().find { it.udid == udid }
            ?: throw RuntimeException("Device Simulator $udid does not exist")

        return simulator.state == "Shutdown"
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

    private fun cancelBootSequence() {
        simulatorBootExecutor?.let {
            it.shutdownNow()
            val isTerminated = it.awaitTermination(5, TimeUnit.MINUTES)
            if (isTerminated) {
                logger.info(logMarker, "Simulator boot executor terminated gracefully for ${this@Simulator}")
            } else {
                logger.warn(logMarker, "Simulator boot executor did not terminate gracefully within 3 minutes for ${this@Simulator}. Forcing shutdown.")
            }
        }
    }

    private fun shutdownAndDisposeResources() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")

        val startTime = System.nanoTime()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        listOf(
            { ignoringErrors({ cancelBootSequence() }) },
            { ignoringErrors({ stopPeriodicHealthCheck() }) },
            { ignoringErrors({ cancellInstallTask() }) },
            { ignoringErrors({ videoRecorder.dispose() }) },
            { ignoringErrors({ instrumentationAgent.kill() }) },
        ).forEach {
            executor.submit(it)
        }

        executor.shutdown()
        val isTerminated = executor.awaitTermination(5, TimeUnit.MINUTES)

        if (isTerminated) {
            logger.info(logMarker, "Simulator tasks terminated gracefully for ${this@Simulator}")
        } else {
            logger.warn(logMarker, "Simulator tasks did not terminate gracefully for ${this@Simulator}.")
        }

        val result = simCtlUtility.shutdownSimulator(udid, true)

        if (!result.isSuccess && !result.stdErr.contains("current state: Shutdown")) {
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

        val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)
        logger.info(logMarker, "Successfully shut down ${this@Simulator} in $elapsedSeconds seconds")
    }

    private fun cancellInstallTask() {
        installTask?.let {
            cancelTask(it, "installTask")
        }
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

    private fun writeSimulatorDefaults(setting: String) {
        remote.shell("/usr/bin/xcrun simctl spawn $udid defaults write $setting", true)
    }

    private fun launchMobileSafari(url: String) {
        remote.commandExecutor.exec(listOf("/usr/bin/xcrun", "simctl", "openurl", udid, url))
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

    //region helper functions — execute critical and async
    private fun executeCriticalWithLock(action: () -> Unit) {
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

    private fun scheduleSequentialExecution(simulatorBootExecutor: ExecutorService, action: () -> Unit, lazyErrorMessage: () -> String) {
        simulatorBootExecutor.submit {
            try {
                action()
            } catch (e: Exception) {
                lastException = e
                deviceState = DeviceState.FAILED
                logger.error(logMarker, lazyErrorMessage(), e)
                simulatorBootExecutor.shutdownNow()
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

        return URI("http://${remote.publicHostName}:$port/").toURL()
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
        logTiming("Shutdown simulator $udid on host ${remote.publicHostName}") { ignoringErrors({ shutdownAndDisposeResources() }) }
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
        return result.stdOut.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
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

        envs.keys.forEach { key ->
            envs[key]?.let { value ->
                remote.commandExecutor.exec(
                    listOf(
                        "/usr/bin/xcrun", "simctl", "spawn", udid, "launchctl", "setenv", key, value
                    )
                )
            }
        }

        logger.info(logMarker, "Set environment variables $envs for Simulator $this")
    }

    override fun getEnvironmentVariable(variableName: String): String {
        logger.debug(logMarker, "Getting environment variable $variableName for Simulator $this")
        if (!ENV_VAR_VALIDATE_REGEX.matches(variableName)) {
            throw IllegalArgumentException("Variable name should contain only letters, numbers and underscores. Current value: $variableName")
        }

        return remote.commandExecutor.exec(listOf("/usr/bin/xcrun", "simctl", "getenv", udid, variableName)).stdOut.trim()
    }
}
