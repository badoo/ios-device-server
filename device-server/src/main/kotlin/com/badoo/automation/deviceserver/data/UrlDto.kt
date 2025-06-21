package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

class UrlDto(
    @JsonProperty("url")
    val url: String)
