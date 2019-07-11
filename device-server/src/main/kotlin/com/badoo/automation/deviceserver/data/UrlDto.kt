package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.Path

class UrlDto(
    @JsonProperty("url")
    val url: String)
