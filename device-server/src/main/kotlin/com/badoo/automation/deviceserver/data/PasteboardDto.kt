package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class PasteboardDto(
    @JsonProperty("pasteboard_content")
    val pasteboardСontent: ByteArray
)
