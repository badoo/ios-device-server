package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.Path

data class XcuiTestExecutionConfig(
        @JsonProperty("app_name")
        val appName: String,

        @JsonProperty("xctestrun_file_name")
        val xctestrunFileName: String,

        @JsonProperty("test_name")
        val testName: String,

        @JsonProperty("path_to_dir_with_xctestrun_file")
        val pathToDirWithXctestrunFile: Path,

        @JsonProperty("timeout_sec")
        val timeoutSec: Long = 300
)
