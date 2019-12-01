package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackupError
import com.nhaarman.mockito_kotlin.firstValue
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.Matchers.matchesPattern
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

class SimulatorBackupTest {
    private val metaJson = """
        {"version":${SimulatorBackup.CURRENT_VERSION},"created":"2018-01-12 01:46:48 +0000"}
    """.trimIndent()

    @Mock private lateinit var remote: IRemote
    private val udid: UDID = "M-Y-P-H-O-N-E"
    private val deviceSetPath = "/home/user/backup"
    private val captor = ArgumentCaptor.forClass(listOf("").javaClass)
    private val resultStub = CommandResult("", "", 0, pid = 1)
    private val resultFailureStub = CommandResult("", "", 1, pid = 1)

    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test fun shouldExistBackup() {
        val resultWithMeta = CommandResult(metaJson, "", 0, pid = 1)

        whenever(remote.isDirectory(anyString())).thenReturn(true)
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultWithMeta)

        val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath)

        assertTrue("Backup should exist", backup.isExist())
    }

    @Test fun shouldNotExistBackup() {
        whenever(remote.isDirectory(anyString())).thenReturn(true)
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultStub)

        val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath)

        assertFalse("Backup should not exist", backup.isExist())
    }

    @Test fun shouldCreateBackup() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultStub)
        whenever(remote.shell(anyString(), anyBoolean())).thenReturn(resultStub)

        SimulatorBackup(remote, udid, deviceSetPath).create()

        verify(remote, times(3)).execIgnoringErrors(captor.capture() ?: emptyList(), anyMap(), anyLong())

        assertEquals("rm -rf $deviceSetPath/${udid}_BACKUP", captor.allValues[0].joinToString(" "))
        assertEquals("cp -R $deviceSetPath/$udid /home/user/backup/${udid}_BACKUP", captor.allValues[1].joinToString(" "))
        assertEquals("mkdir -p $deviceSetPath/${udid}_BACKUP/data/device_server", captor.allValues[2].joinToString(" "))

        val cmdCaptor = ArgumentCaptor.forClass("".javaClass)
        verify(remote, times(1)).shell(cmdCaptor.capture() ?: "", anyBoolean())
        val redirectPath = "$deviceSetPath/${udid}_BACKUP/data/device_server/meta.json"
        val regex = """echo \\\{\\"version\\":[0-9]+,\\"created\\":\\"[0-9T:-]+\\"\\} > $redirectPath""".toRegex()
        assertThat(cmdCaptor.firstValue,  matchesPattern(regex.pattern))
    }

    @Test(expected = SimulatorBackupError::class)
    fun shouldDeleteThrow() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultFailureStub)
        SimulatorBackup(remote, udid, deviceSetPath).delete()
    }

    @Test(expected = SimulatorBackupError::class)
    fun shouldCreateThrow() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultFailureStub)
        SimulatorBackup(remote, udid, deviceSetPath).create()
    }

    @Test(expected = SimulatorBackupError::class)
    fun shouldRestoreThrow() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultFailureStub)
        whenever(remote.shell(anyString(), anyBoolean())).thenReturn(resultFailureStub)
        SimulatorBackup(remote, udid, deviceSetPath).restore()
    }
}