package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class XcuiTestExecutionConfig(
        @JsonProperty("test_name")
        val testName: String,

        @JsonProperty("path_to_xctestrun_file")
        val pathToXctestrunFile: String,

        @JsonProperty("timeout_sec")
        val timeoutSec: Long = 300
)
