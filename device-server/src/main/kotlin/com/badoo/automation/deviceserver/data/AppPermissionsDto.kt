package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class AppPermissionsDto(
    @JsonProperty("bundle_id")
    val bundleId: String,

    @JsonProperty("permissions")
    val permissions: PermissionSet
)
