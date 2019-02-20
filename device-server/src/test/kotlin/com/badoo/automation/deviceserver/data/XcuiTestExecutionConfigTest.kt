package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Test
import kotlin.test.assertEquals

class XcuiTestExecutionConfigTest {

    private fun fromJson(json: String): XcuiTestExecutionConfig {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesConfig() {
        val json = """{"test_name": "test-scheme/TestName","path_to_xctestrun_file":
            | "/some/path/Build/Product/file_name.xctestrun"}""".trimMargin()
        val actual = fromJson(json)

        assertEquals(XcuiTestExecutionConfig("test-scheme/TestName",
                "/some/path/Build/Product/file_name.xctestrun"), actual)
    }

    @Test
    fun fromJsonParsesConfigWithTimeout() {
        val json = """{"test_name": "test-scheme/TestName","path_to_xctestrun_file":
            | "/some/path/Build/Product/file_name.xctestrun","timeout_sec": 240}""".trimMargin()
        val actual = fromJson(json)

        assertEquals(XcuiTestExecutionConfig("test-scheme/TestName",
                "/some/path/Build/Product/file_name.xctestrun", 240), actual)
    }

    @Test
    fun fromJsonParsesConfigWithTimeoutAsString() {
        val json = """{"test_name": "test-scheme/TestName","path_to_xctestrun_file":
            | "/some/path/Build/Product/file_name.xctestrun","timeout_sec": "240"}""".trimMargin()
        val actual = fromJson(json)

        assertEquals(XcuiTestExecutionConfig("test-scheme/TestName",
                "/some/path/Build/Product/file_name.xctestrun", 240), actual)
    }
}
