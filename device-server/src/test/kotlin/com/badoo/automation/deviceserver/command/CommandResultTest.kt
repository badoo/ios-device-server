package com.badoo.automation.deviceserver.command

import org.junit.Assert
import org.junit.Test

class CommandResultTest {
    @Test
    fun commandResultContainsRelevantFields() {
        val expectedResult = "CommandResult(cmd=[rm, -rf, /], exitCode=0, stdOut=out, stdErr=err)"

        val actualResult = CommandResult(
                stdOut = "out",
                stdErr = "err",
                exitCode = 0,
                cmd = listOf("rm", "-rf", "/"),
                pid = 1
        ).toString()

        Assert.assertEquals("Wrong result string", expectedResult, actualResult)
    }
}