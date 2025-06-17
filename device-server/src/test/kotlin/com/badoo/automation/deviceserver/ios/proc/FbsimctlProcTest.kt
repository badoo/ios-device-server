package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.SubProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.net.URI

class FbsimctlProcTest {
    @Mock private lateinit var remote: IRemote
    private val udid: UDID = "UDID"
    @Mock private lateinit var endpoint: URI
    @Mock private lateinit var subProcess: SubProcess
    private lateinit var actualCommand: List<String>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(remote.hostName).thenReturn("hostName")
        whenever(remote.userName).thenReturn("userName")
        whenever(endpoint.port).thenReturn(1)
    }
}