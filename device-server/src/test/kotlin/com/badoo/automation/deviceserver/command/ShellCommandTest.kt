package com.badoo.automation.deviceserver.command

import org.hamcrest.Matchers.*
import org.junit.Assert.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import kotlin.test.assertFailsWith

class ShellCommandTest {
    private lateinit var systemErr: PrintStream
    private lateinit var systemOut: PrintStream
    private lateinit var shellCommand: ShellCommand

    @Before
    fun setUp() {
        hideTestOutput() // comment out to debug
        shellCommand = ShellCommand(mapOf())
    }

    private fun hideTestOutput() {
        systemErr = System.err
        systemOut = System.out
        val testErr = PrintStream(ByteArrayOutputStream(10_000))
        val testOut = PrintStream(ByteArrayOutputStream(10_000))
        System.setErr(testErr)
        System.setOut(testOut)
    }

    @Test
    fun testCommandWithRealProcess() {
        val result = shellCommand.exec(listOf("ls", "-lah"))
        assertThat("Wrong exit code", result.exitCode, equalTo(0))
        assertThat("StdOut should not be empty", result.stdOut, not(emptyString()))
        assertThat("StdErr should be empty", result.stdErr, emptyString())
    }

    @Test
    fun testCommandThrowsErrorWithRealProcess() {
        assertFailsWith<ShellCommandException> {
            shellCommand.exec(listOf("/bin/cp"), returnFailure = false)
        }
    }

    @Test @Ignore("Flaky in docker container")
    fun testCommandThrowsErrorWhenCommandNotFound() {
        assertFailsWith<ShellCommandException> {
            shellCommand.exec(listOf("/usr/bin/not_existing_command"), returnFailure = false)
        }
    }

    @Test(expected = ShellCommandException::class)
    fun testTimeOutLongRunningCommand() {
        val result = shellCommand.exec(listOf("sleep", "600"), timeOut = Duration.ofMillis(100), returnFailure = false)
        assertThat("Wrong exit code", result.exitCode, equalTo(0))
        assertThat("StdOut should not be empty", result.stdOut, not(emptyString()))
        assertThat("StdErr should be empty", result.stdErr, emptyString())
    }
}
