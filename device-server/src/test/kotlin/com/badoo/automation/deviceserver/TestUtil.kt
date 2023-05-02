package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.data.*
import org.mockito.MockSettings
import org.mockito.Mockito
import java.net.URI

/**
 * Utility methods for tests. Syntax sugar or workarounds for Kotlin's keywords
 */

/**
 * Shortcut to '`Mockito.mock(MyClass::class.java)`' notation.
 * Example: i.e. instead of this:
 * `val myClassMock: MyClass = Mockito.mock(MyClass::class.java)`  you can use this:
 * `val myClassMock: MyClass = mockThis()`
 *
 * @return T instance of whatever type was on the left side of assignment operator.
 */
inline fun <reified T> mockThis(): T = Mockito.mock(T::class.java)
inline fun <reified T> mockThis(name: String): T = Mockito.mock(T::class.java, name)
inline fun <reified T> mockThis(settings: MockSettings): T = Mockito.mock(T::class.java, settings)
fun <T> anyType(): T = Mockito.any<T>() as T

/**
 * Parse a string of permissive JSON.
 */
fun json(json: String) = JsonMapper().readTree(json.byteInputStream())

fun deviceDTOStub(ref: DeviceRef): DeviceDTO {
    return DeviceDTO(
        ref, DeviceState.NONE,
        URI("http://fbsimctl/endpoint/for/testing"),
        URI("http://wda/endpoint/for/testing"),
        0,
        URI("http://calabash/endpoint/for/testing"),
        1,
        2,
        URI("http://appium/endpoint/for/testing"),
        DeviceInfo("", "", "iOS 16.4.1", "", ""),
        Exception().toDto(),
        capabilities = null
    )
}