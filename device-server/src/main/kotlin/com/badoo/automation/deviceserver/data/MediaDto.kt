package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

class MediaDto(
    @JsonProperty("media")
    val media: List<String>)
