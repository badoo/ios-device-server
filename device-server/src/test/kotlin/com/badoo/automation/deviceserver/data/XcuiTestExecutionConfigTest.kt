package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class XcuiTestExecutionConfigTest {

    private fun fromJson(json: String): XcuiTestExecutionConfig {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesConfig() {
        val json = """{"app_name": "com.app","xctestrun_file_name":"test.xctestrun",
            |"test_name":"ui-tests/TestSome","path_to_dir_with_xctestrun_file":"/tmp/Build/Product"}""".trimMargin()
        val actual = fromJson(json)

        assertEquals(XcuiTestExecutionConfig("com.app", "test.xctestrun",
                "ui-tests/TestSome", Paths.get("/tmp/Build/Product")), actual)
    }
}
