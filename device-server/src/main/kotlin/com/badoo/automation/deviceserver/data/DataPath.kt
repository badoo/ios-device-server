package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.Path

class DataPath(
    @JsonProperty("bundle_id")
    val bundleId: String,

    @JsonProperty("path")
    val path: Path)