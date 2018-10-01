package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Assert
import org.junit.Test

class ErrorDtoKtTest {
    private val exception: Exception = mockThis()

    @Test
    fun toDto() {
        val stackTrace: Array<StackTraceElement> = listOf(
            StackTraceElement("com.badoo.SomeClass", "someMethod", "SomeFile.kt", 2),
            StackTraceElement("com.badoo.SomeClass", "someMethod", "SomeFile.kt", 1),
            StackTraceElement("io.ktor.SomeClass", "someMethod", "SomeFile.kt", 2)
        ).toTypedArray()
        whenever(exception.stackTrace).thenReturn(stackTrace)

        val expectedStackTrace = listOf(
            "com.badoo.SomeClass.someMethod(SomeFile.kt:2)",
            "com.badoo.SomeClass.someMethod(SomeFile.kt:1)"
        )
        Assert.assertEquals(expectedStackTrace, exception.toDto().stackTrace)
    }
}