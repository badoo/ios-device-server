package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
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
import org.junit.Ignore
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.io.File

class SimulatorBackupTest {
    private val metaJson = """
        {"version":${SimulatorBackup.CURRENT_VERSION},"created":"2018-01-12 01:46:48 +0000"}
    """.trimIndent()

    @Mock private lateinit var remote: IRemote
    private val udid: UDID = "M-Y-P-H-O-N-E"
    private val deviceSetPath = "/home/user/backup"
    private val captor = ArgumentCaptor.forClass(listOf("").javaClass)
    private val resultStub = CommandResult("", "", 0, pid = 1)
    private val resultFailureStub = CommandResult("There is no such file or directory!", "", 1, pid = 1)
    @Mock private lateinit var config: ApplicationConfiguration
    private val simulatorDirectory: File = File("/home/user/backup/M-Y-P-H-O-N-E")
    @Mock
    private lateinit var simulatorDataDirectory: File


    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(simulatorDataDirectory.absolutePath).thenReturn("simulatorDataDirectory")
    }

    @Test fun shouldExistBackup() {
        val resultWithMeta = CommandResult(metaJson, "", 0, pid = 1)

        whenever(remote.isDirectory(anyString())).thenReturn(true)
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultWithMeta)

        val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config)

        assertTrue("Backup should exist", backup.isExist())
    }

    @Test fun shouldNotExistBackup() {
        whenever(remote.isDirectory(anyString())).thenReturn(true)
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultStub)

        val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config)

        assertFalse("Backup should not exist", backup.isExist())
    }

    @Ignore
    @Test fun shouldCreateBackup() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultStub)
        whenever(remote.shell(anyString(), anyBoolean())).thenReturn(resultStub)

        SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config).create()

        verify(remote, times(3)).execIgnoringErrors(captor.capture() ?: emptyList(), anyMap(), anyLong())

        assertEquals("rm -rf $deviceSetPath/${udid}_BACKUP", captor.allValues[0].joinToString(" "))
        assertEquals("cp -Rp $deviceSetPath/$udid /home/user/backup/${udid}_BACKUP", captor.allValues[1].joinToString(" "))
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
        SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config).delete()
    }

    @Test(expected = SimulatorBackupError::class)
    fun shouldCreateThrow() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultFailureStub)
        SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config).create()
    }

    @Test(expected = SimulatorBackupError::class)
    fun shouldRestoreThrow() {
        whenever(remote.execIgnoringErrors(anyList(), anyMap(), anyLong())).thenReturn(resultFailureStub)
        whenever(remote.shell(anyString(), anyBoolean())).thenReturn(resultFailureStub)
        SimulatorBackup(remote, udid, deviceSetPath, simulatorDirectory, simulatorDataDirectory, config).restore()
    }
}
