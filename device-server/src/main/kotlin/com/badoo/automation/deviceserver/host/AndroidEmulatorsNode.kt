package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.errors.DeviceCreationException
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import com.badoo.automation.deviceserver.util.CustomHttpClient
import okhttp3.*
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.NoSuchElementException
import kotlin.RuntimeException

typealias EmulatorSerial = String

// Represents one ticket-machine = ticketHost = machine:8088.
class AndroidEmulatorsNode(
    private val ticketHost: String,
    private val emulatorLimit: Int,
    private val concurrentBoots: Int,
    private val concurrentBooter: ExecutorService = Executors.newFixedThreadPool(concurrentBoots),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) : ISimulatorsNode {
    // Unique number for references
    private var serial = AtomicInteger(0)

    // Nicer to keep a list of known serial numbers than write a validating function, because it's similar
    // for real devices which cannot have a validating function and may disappear if its battery dies
    // or someone borrows one.
    private val knownEmulators = (1..emulatorLimit).map { "emulator-${5552 + 2 * it}" }.toSet()

    // Need to keep a list of allocated devices; keep ticket for handy list of existing devices.
    private val allocatedEmulators = ConcurrentHashMap<EmulatorSerial, DeviceRef>()

    // Keep these until released by user or SessionManager
    private val refToDto = HashMap<DeviceRef, DeviceDTO>()

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override val remoteAddress: String get() = ticketHost

    override fun isReachable(): Boolean {
        // Cheapest, quietest 200-OK no-op.
        httpGet("docker/hello").use { return it.code() == 200 }
    }

    val newEmulatorLimit = Regex("^Now: $emulatorLimit$", RegexOption.MULTILINE)

    override fun prepareNode() {
        httpPost("docker/kill-all").use { throwUnlessOK(it) }
        httpPost("docker/set-max-emus/$emulatorLimit")
            .use {
                throwUnlessOK(it)
                val text = it.body()?.string()
                if (text == null || !text.contains(newEmulatorLimit)) {
                    throw RuntimeException("Failed to set emulator max: response=$it body=$text")
                }
            }
    }

    override fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean {
        val emulator = emulatorOrNullFromRef(deviceRef)
        val deallocated = emulator != null && (deallocateEmulatorSlot(emulator) != null)
        val released = releaseOnTicketMachine(deviceRef)
        refToDto.remove(deviceRef)
        return released || deallocated
    }

    override fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO =
        refToDto[deviceRef] ?: throw DeviceNotFoundException("No emulator found for ref=$deviceRef")

    override fun totalCapacity(desiredCaps: DesiredCapabilities): Int = emulatorLimit

    override fun capacityRemaining(desiredCaps: DesiredCapabilities): Float =
        (emulatorLimit - allocatedEmulators.size.toFloat()) / emulatorLimit

    override fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO {
        val ix = serial.getAndAdd(1)
        val ref = "${ticketHost}--$ix".replace(Regex("[^\\w.]"), "-")
        val model = desiredCaps.model
        if (model == null || !model.startsWith("android-avd-")) {
            throw IllegalArgumentException("Model must start with android-avd-, e.g. model=android-avd-oreo-snapshot")
        }
        val deviceDTO = updateDtoForRef(
            ref = ref,
            state = DeviceState.CREATING,
            emulatorSerial = "",
            model = model
        )
        concurrentBooter.submit { requestEmulatorAndAwaitBooted(ref, model) }
        return deviceDTO
    }

    override fun list(): List<DeviceDTO> {
        return allocatedEmulators.values.mapNotNull { refToDto[it] }
    }

    override fun state(deviceRef: DeviceRef): SimulatorStatusDTO {
        val deviceDTO = getDeviceDTO(deviceRef)
        return SimulatorStatusDTO(
            deviceDTO.state == DeviceState.CREATED,
            deviceDTO.wda_endpoint.toString().isNotEmpty(),
            deviceDTO.fbsimctl_endpoint.toString().isNotEmpty(),
            deviceDTO.state.value,
            null)
    }

    private fun requestEmulatorAndAwaitBooted(
        ref: DeviceRef,
        model: String
    ) {
        var lastError: Throwable? = null
        val emulator = allocateEmulatorSlot(ref)
        try {
            requestEmulator(ref, emulator, model)
            awaitEmulatorOrTimeOut(ref, emulator, model)
        } catch (e: Throwable) {
            lastError = e
        } finally {
            deallocateIfNotCreated(ref, emulator, model, lastError)
        }
    }

    private fun awaitEmulatorOrTimeOut(
        ref: DeviceRef,
        emulatorSerial: EmulatorSerial,
        model: String
    ) {
        val timeoutSecs = 30
        for (count in 1..timeoutSecs) {
            val dto = dtoFromTicketMachine(ref, emulatorSerial, model)
            if (dto != null) {
                logger.debug("Found $ticketHost promise for $emulatorSerial $dto")
                if (dto.state == DeviceState.CREATED || dto.state == DeviceState.FAILED) {
                    return
                }
            } else {
                logger.warn("Awaiting $count/$timeoutSecs $ticketHost container for $emulatorSerial $ref to start")
            }
            Thread.sleep(1000)
        }
        throw DeviceCreationException("Server $ticketHost gave no promise within ${timeoutSecs}s - giving up")
    }

    private fun deallocateIfNotCreated(
        ref: DeviceRef,
        emulatorSerial: EmulatorSerial,
        model: String,
        lastError: Throwable?
    ) {
        val dto = refToDto[ref]
        if (dto?.state != DeviceState.CREATED) {
            if (dto?.state != DeviceState.FAILED) {
                logger.error("ref=$ref is in state ${dto?.state} after request. Marking as FAILED: $dto")
            }
            updateDtoForRef(
                ref = ref,
                state = DeviceState.FAILED,
                emulatorSerial = "",
                model = model,
                last_error = lastError
            )
            deallocateEmulatorSlot(emulatorSerial)
        }
    }

    private fun requestEmulator(
        ref: String,
        emulator: EmulatorSerial,
        model: String
    ) {
        logger.info("Requesting $ticketHost promise for ref=$ref emu=$emulator concurrent=$concurrentBoots")
        httpPost("docker/request/id=$ref/device=$emulator/image=${model}")
            .use {
                throwUnlessOK(it)
                val body = it.body()?.string()
                logger.debug("Received response $it $body")
                if (body == null || body.contains(":FAILURE:")) {
                    logger.error("Found $ticketHost :FAILURE: or null for $emulator '${summary(body, it)}'")
                    return
                }
            }
    }

    private fun summary(body: String?, response: Response): String {
        return if (body != null && body.isNotEmpty()) body else response.toString()
    }

    val nullRequestBody = RequestBody.create(null, "")

    private fun httpPost(endpoint: String): Response {
        return httpClient.newCall(buildEndpoint(endpoint).post(nullRequestBody).build()).execute()
    }

    private fun httpGet(endpoint: String): Response {
        return httpClient.newCall(buildEndpoint(endpoint).build()).execute()
    }

    private fun buildEndpoint(endpoint: String) = Request.Builder().url("http://$ticketHost/$endpoint")

    private fun throwUnlessOK(response: Response) {
        if (response.code() != HttpURLConnection.HTTP_OK) {
            throw DeviceCreationException("NOT HTTP_OK: $response")
        }
    }

    private fun releaseOnTicketMachine(ref: DeviceRef): Boolean {
        httpPost("docker/release/$ref")
            .use {
                throwUnlessOK(it)
                val body = it.body()?.string()
                val released = body?.contains(":SUCCESS: $ref released") ?: false
                if (!released) {
                    logger.warn("Unable to release $ref: $body $it")
                }
                return released
            }
    }

    private fun emulatorOrNullFromRef(ref: DeviceRef): EmulatorSerial? {
        val deviceDTO = refToDto[ref]
        if (deviceDTO == null) {
            logger.warn("No emulator for $ref")
            return null
        }
        return deviceDTO.info.udid
    }

    private fun updateDtoForRef(
        ref: String,
        state: DeviceState,
        emulatorSerial: EmulatorSerial,
        adb_socket: URI = URI(""),
        model: String,
        last_error: Throwable? = null
    ): DeviceDTO {
        val deviceDTO = DeviceDTO(
            ref = ref,
            state = state,
            fbsimctl_endpoint = URI(ticketHost),
            wda_endpoint = adb_socket,
            info = DeviceInfo(udid = emulatorSerial, model = model, os = "", arch = "", name = ""),
            calabash_port = 0,
            user_ports = HashSet(),
            capabilities = null,
            last_error = last_error?.toDto()
        )
        refToDto[ref] = deviceDTO
        return deviceDTO
    }

    private fun deallocateEmulatorSlot(emulator: EmulatorSerial): DeviceRef? {
        return allocatedEmulators.remove(emulator)
    }

    private fun allocateEmulatorSlot(ref: DeviceRef): EmulatorSerial {
        synchronized(allocatedEmulators) {
            try {
                val emulator =
                    (knownEmulators - allocatedEmulators.keys).asSequence().sorted().first()
                allocatedEmulators[emulator] = ref
                return emulator
            } catch (e: NoSuchElementException) {
                throw OverCapacityException("No emulators left in $ticketHost pool", e)
            }
        }
    }

    private fun dtoFromTicketMachine(
        ref: DeviceRef,
        emulatorSerial: EmulatorSerial,
        model: String
    ): DeviceDTO? {
        httpGet("docker/list-all/$emulatorSerial").use {
            throwUnlessOK(it)
            val body = it.body()?.string()
            val adbServerSocket = adbSocketFromBody(body)
            if (adbServerSocket == null || body == null || body.contains(Regex(":CONTAINER: \\S+ Exited"))) {
                return updateDtoForRef(
                    ref = ref,
                    state = DeviceState.FAILED,
                    emulatorSerial = emulatorSerial,
                    model = model,
                    last_error = RuntimeException(summary(body, it))
                )
            }
            val containerUp = body.contains(Regex(":CONTAINER: \\S+ Up"))
            val container = body.contains(Regex(":CONTAINER: \\S+"))
            if (container && !containerUp) {
                throw DeviceCreationException("Container is listed but not up - I don't know what that means? $body")
            }
            val adbDeviceReady = body.contains(Regex(":ADB: $emulatorSerial\\s+device"))
            return updateDtoForRef(
                ref = ref,
                state = if (adbDeviceReady) DeviceState.CREATED else DeviceState.CREATING,
                emulatorSerial = emulatorSerial,
                adb_socket = URI(adbServerSocket),
                model = model,
                last_error = null
            )
        }
    }

    private fun adbSocketFromBody(body: String?): String? {
        val line = body?.lines()?.find { it.contains("ADB_SERVER_SOCKET=tcp:") } ?: return null
        return line.split("=")[1]
    }

//region not implemented yet

    override fun dispose() {
        TODO("dispose not implemented")
    }

    override fun supports(desiredCaps: DesiredCapabilities): Boolean {
        TODO("supports not implemented")
    }

    override fun resetAsync(deviceRef: DeviceRef) {
        TODO("resetAsync not implemented")
    }

    override fun approveAccess(deviceRef: DeviceRef, bundleId: String) {
        TODO("approveAccess not implemented")
    }

    override fun clearSafariCookies(deviceRef: DeviceRef) {
        TODO("clearSafariCookies not implemented")
    }

    override fun endpointFor(deviceRef: DeviceRef, port: Int): URL {
        TODO("endpointFor not implemented")
    }

    override fun lastCrashLog(deviceRef: DeviceRef): CrashLog {
        TODO("lastCrashLog not implemented")
    }

    override fun videoRecordingDelete(deviceRef: DeviceRef) {
        TODO("videoRecordingDelete not implemented")
    }

    override fun videoRecordingGet(deviceRef: DeviceRef): ByteArray {
        TODO("videoRecordingGet not implemented")
    }

    override fun videoRecordingStart(deviceRef: DeviceRef) {
        TODO("videoRecordingStart not implemented")
    }

    override fun videoRecordingStop(deviceRef: DeviceRef) {
        TODO("videoRecordingStop not implemented")
    }

    override fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String> {
        TODO("listFiles not implemented")
    }

    override fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray {
        TODO("pullFile not implemented")
    }

    override fun count(): Int {
        TODO("count not implemented")
    }

    override fun shake(deviceRef: DeviceRef) {
        TODO("shake(...) needs implementing")
    }
//endregion
}
