package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class XcuiTestExecutionResult(
        val command: String,

        @JsonProperty("exit_code")
        val exitCode: Int,

        @JsonProperty("std_out")
        val stdOut: String,

        @JsonProperty("std_err")
        val stdErr: String
)
