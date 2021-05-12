package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

class SysLogCaptureOptions {
    @JsonProperty("predicate_string")
    val predicateString: String = ""

    @JsonProperty("matching_processes")
    val matchingProcesses: String = ""

    @JsonProperty("mute_kernel")
    val shouldMuteKernel: Boolean = false

    @JsonProperty("mute_system_processes")
    val shouldMuteSystemProcesses: Boolean = false
}
