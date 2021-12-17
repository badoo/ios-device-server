package com.badoo.automation.deviceserver.command

import org.junit.Assert.*
import org.junit.Test

class ShellUtilsTest {
    @Test
    fun testEscape() {
        val actual = ShellUtils.escape("""{"version":1,"created":"2018-02-27T11:56:19"}""")
        assertEquals("""\{\"version\":1,\"created\":\"2018-02-27T11:56:19\"\}""", actual)
    }
}
