package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.argForWhich
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import okhttp3.*
import org.junit.Test
import java.net.HttpURLConnection
import org.junit.Assert.*
import java.lang.RuntimeException
import java.net.URI
import java.util.concurrent.*

class AndroidEmulatorsNodeTest {
    private val httpClient: OkHttpClient = mockThis()
    private val host = "hostname:1234"
    private val emulatorLimit = 6
    private val concurrentBoots = 3
    private val mainThreadSynchronous = object : ScheduledThreadPoolExecutor(1) {
        override fun submit(task: Runnable?): Future<*> {
            task?.run()
            return super.submit {}
        }
    }
    private val emulatorsNode = AndroidEmulatorsNode(host, emulatorLimit, concurrentBoots, mainThreadSynchronous, httpClient)
    private val emulatorsNodeEL4 = AndroidEmulatorsNode(host, 4, concurrentBoots, mainThreadSynchronous, httpClient)
    private val emulatorsNodeEL1 = AndroidEmulatorsNode(host, 1, concurrentBoots, mainThreadSynchronous, httpClient)
    private val anyDesiredCaps = DesiredCapabilities(null, null, null)

    @Test
    fun testNodeIsReachableIfReachable() {
        wheneverEndpoint("docker/hello", responseOk("Hello sailor."))

        assertTrue(emulatorsNode.isReachable())
    }

    @Test
    fun testNodeIsNotReachableIfUnreachable() {
        wheneverEndpoint("docker/hello", responseBad("Hello sailor."))
        assertFalse(emulatorsNode.isReachable())
    }

    @Test
    fun testPrepareNodeSetsMaxEmus() {
        val killAll = wheneverEndpoint(
                "docker/kill-all",
                responseOk("with hammers")
        )
        val maxEmus = wheneverEndpoint(
                "docker/set-max-emus/$emulatorLimit",
                responseOk("Was: 2000\nNow: $emulatorLimit")
        )

        emulatorsNode.prepareNode()
        verify(killAll).execute()
        verify(maxEmus).execute()
    }

    @Test(expected = RuntimeException::class)
    fun testPrepareNodeFailsToSetMaxEmus() {
        wheneverEndpoint(
                "docker/set-max-emus/$emulatorLimit",
                responseOk("Was: 2000\nYou suck\nNow: 2000")
        )
        emulatorsNode.prepareNode()
    }

    @Test
    fun testRemoteAddress() {
        assertEquals(host, emulatorsNode.remoteAddress)
    }

    @Test
    fun testTotalCapacity() {
        assertEquals(6, emulatorsNode.totalCapacity(anyDesiredCaps))
    }

    @Test
    fun testCapacityRemainingWhenAllArePlanned() {
        givenListAllWithContainerAndDevice()
        givenSuccessfulCreatedDevice(emulatorsNodeEL1)
        assertEquals(0.0f, emulatorsNodeEL1.capacityRemaining(anyDesiredCaps))
    }

    @Test
    fun testCapacityRemainingWhenNoneArePlanned() {
        assertEquals(1.0f, emulatorsNode.capacityRemaining(anyDesiredCaps))
    }

    @Test
    fun testCapacityRemainingWhenSomeArePending() {
        givenListAllWithContainerAndDevice()
        givenSuccessfulCreatedDevice(emulatorsNodeEL4)
        assertEquals(0.75f, emulatorsNodeEL4.capacityRemaining(anyDesiredCaps))
    }

    @Test
    fun testCreateDeviceAsync() {
        val d: DeviceDTO = givenSuccessfulCreatedDevice(emulatorsNode)
        assertEquals(DeviceState.CREATING, d.state)
        assertEquals("hostname-1234--0", d.ref)
    }

    @Test
    fun testDeviceList() {
        givenListAllWithContainerAndDevice()
        val d1: DeviceDTO = givenSuccessfulCreatedDevice(emulatorsNode)
        val list1 = emulatorsNode.list()
        assertEquals(1, list1.size)
        assertEquals(listOf(expectedDTO(d1, "emulator-5554")), list1)

        givenListAllWithContainerAndDevice("5556")
        val d2: DeviceDTO = givenSuccessfulCreatedDevice(emulatorsNode, "5556", "1")
        val list2 = emulatorsNode.list()
        assertEquals(listOf(expectedDTO(d2, "emulator-5556"), expectedDTO(d1, "emulator-5554")), list2)
    }

    private fun expectedDTO(d1: DeviceDTO, udid: String): DeviceDTO {
        return DeviceDTO(
            d1.ref, DeviceState.CREATED, d1.fbsimctl_endpoint, URI("tcp:automation7.d4:9125"),
            0, setOf(),
            DeviceInfo(FBSimctlDevice(udid = udid, model = "android-avd-banana-snapshot-geometry=234x234", state = "")),
            null, null
        )
    }

    private fun givenSuccessfulCreatedDevice(node: AndroidEmulatorsNode, emuport: String = "5554", ix: String = "0"): DeviceDTO {
        wheneverEndpoint(
                "docker/request/id=hostname-1234--$ix/device=emulator-$emuport/image=android-avd-banana-snapshot-geometry=234x234",
                responseOk("""
                    :INFO: Processed 'id=hostname-1234--$ix'
                    :INFO: Processed 'device=emulator-$emuport'
                    :INFO: Processed 'image=android-avd-banana-snapshot-geometry=234x234'
                    :INFO: Starting dockerio.badoo.com/automation/android-avd-banana-snapshot-geometry=234x234 as dserv-emulator-$emuport-hostname-1234--$ix at 2018-09-24.13:08:25
                    :VAL: CONTAINER_ID=ca482be5b60da80023348f0ef13693c887589ba0ba6ebe78670abf04984ad850
                    :VAL: CONTAINER_NAME=dserv-emulator-$emuport-hostname-1234--$ix
                    :VAL: ADB_DEVICE=emulator-$emuport
                    :VAL: ADB_SERVER_SOCKET=tcp:automation7.d4:9125
                    :INFO: -L tcp:automation7.d4:9125 -s emulator-$emuport
                    :SUCCESS: Docker run $emuport dockerio.badoo.com/automation/android-avd-banana-snapshot-geometry=234x234
                    == end
                """.trimIndent())
        )
        return node.createDeviceAsync(
                DesiredCapabilities(null, "android-avd-banana-snapshot-geometry=234x234", null))
    }

    private val expectedDeviceRef = "hostname-1234--0"

    @Test
    fun testGetDeviceDTOFailsOnUnstartedContainer() {
        wheneverEndpoint(
                "docker/request/id=hostname-1234--0/device=emulator-5554/image=android-avd-banana-snapshot-geometry=234x234",
                responseOk("""
                    :INFO: Processed 'id=tim-3'
                    :INFO: Processed 'device=emulator-5554'
                    :INFO: Processed 'image=android-avd-oreo-snapshot'
                    dserv-emulator-5554-hostname-1234--0
                    :FAILURE: Found unstopped dserv-emulator- container/s
                    == end
                """.trimIndent())
        )
        givenListAllWithBaseAndExtras("5554", "")
        emulatorsNode.createDeviceAsync(
                DesiredCapabilities(null, "android-avd-banana-snapshot-geometry=234x234", null))
        val d = emulatorsNode.getDeviceDTO(expectedDeviceRef)
        assertEquals(DeviceState.FAILED, d.state)
        assertEquals(expectedDeviceRef, d.ref)
    }

    @Test
    fun testGetDeviceDTOAbortsOnCrashedContainer() {
        val extra = ":CONTAINER: $expectedDeviceRef Exited (0) 1 second ago\n" +
                "                        "
        givenListAllWithBaseAndExtras("5554", extra)
        givenSuccessfulCreatedDevice(emulatorsNode)
        emulatorsNode.createDeviceAsync(
                DesiredCapabilities(null, "android-avd-banana-snapshot-geometry=234x234", null))
        val d = emulatorsNode.getDeviceDTO(expectedDeviceRef)
        assertEquals(DeviceState.FAILED, d.state)
        assertEquals(expectedDeviceRef, d.ref)
    }

    @Test
    fun testGetDeviceDTOIsHappyOnceDeviceIsHere() {
        givenListAllWithContainerAndDevice()
        givenSuccessfulCreatedDevice(emulatorsNode)
        val d = emulatorsNode.getDeviceDTO(expectedDeviceRef)
        assertEquals(DeviceState.CREATED, d.state)
        assertEquals("emulator-5554", d.info.udid)
        assertEquals(URI("tcp:automation7.d4:9125"), d.wda_endpoint)
        assertEquals(expectedDeviceRef, d.ref)
    }

    @Test
    fun testDeleteReleaseSucceeds() {
        givenListAllWithContainerAndDevice()
        givenSuccessfulCreatedDevice(emulatorsNode)
        val call = wheneverEndpoint(
                "docker/release/$expectedDeviceRef",
                responseOk("""
                    ========= LOGS: $expectedDeviceRef
                    ...
                    emulator-5554	device
                    /local/emulauncher.sh: Watchdog polling pm list packages from 2018-09-24.14:24:07
                    ========= RELEASE: $expectedDeviceRef
                    $expectedDeviceRef
                    :SUCCESS: $expectedDeviceRef released
                    == end
                """.trimIndent())
        )

        assertTrue(emulatorsNode.deleteRelease(expectedDeviceRef, "because I want to"))

        verify(call).execute()
    }

    @Test
    fun testDeleteReleaseIgnoresNonexistentRef() {
        wheneverEndpoint(
            "docker/release/unexpected-ref",
            responseOk("""
                    No such unexpected-ref
                    == end
                """.trimIndent())
        )
        assertFalse(emulatorsNode.deleteRelease("unexpected-ref", "because I want to"))
    }

    @Test
    fun testDeleteReleaseIgnoresIfServerLostTicket() {
        testCreateDeviceAsync()

        wheneverEndpoint(
                "docker/release/$expectedDeviceRef",
                responseOk("""========= LOGS: dserv-emulator-5554-tim-3
                    Error: No such container: dserv-emulator-5554-tim-3
                    ========= RELEASE: dserv-emulator-5554-tim-3
                    Error: No such container: dserv-emulator-5554-tim-3
                    :FAILURE:
                    == end
                """.trimIndent())
        )

        assertFalse(emulatorsNode.deleteRelease(expectedDeviceRef, "because I want to"))
    }

    private fun givenListAllWithContainerAndDevice(
        a5554: String = "5554",
        extra: String = ":CONTAINER: $expectedDeviceRef Up 16 seconds\n" +
                "                        :ADB: emulator-$a5554\tdevice\n" +
                "                        ") {
        givenListAllWithBaseAndExtras(a5554, extra)
    }


    private fun givenListAllWithBaseAndExtras(a5554: String, extra: String) {
        wheneverEndpoint(
            "docker/list-all/emulator-$a5554",
            responseOk(
                """
                        :VAL: EMUS=1
                        :VAL: MAX_EMUS=14
                        :VAL: ADB_LOCAL_TRANSPORT_MAX=32
                        :VAL: ADB_SERVER_SOCKET=tcp:automation7.d4:9125
                        :VAL: NOW=2018-09-24.14:01:35
                        :VAL: UP= 49 min,  0 users,  load average: 3.02, 4.33, 2.91
                        $extra== end
                    """.trimIndent()
            )
        )
    }

    private fun wheneverEndpoint(endpoint: String, response: Response): Call {
        val call: Call = mockThis()
        whenever(httpClient.newCall(argForWhich {
            val expecting = "http://$host/$endpoint"
            val result = url().toString() == expecting
            println("mockEndpoint match=$result : have ${url()} and expecting $expecting")
            result
        })).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        return call
    }

    private fun responseOk(body: String): Response {
        return responseCode(HttpURLConnection.HTTP_OK, body)
    }

    private fun responseBad(body: String): Response {
        return responseCode(HttpURLConnection.HTTP_NOT_FOUND, body)
    }

    private fun responseCode(ok: Int, body: String): Response {
        return Response.Builder()
                .code(ok)
                .body(ResponseBody.create(
                        MediaType.parse("body/plain"),
                        body))
                .request(Request.Builder().url("http://$host/dummy-path").build())
                .protocol(Protocol.HTTP_1_1)
                .message("You suck")
                .build()
    }
}
