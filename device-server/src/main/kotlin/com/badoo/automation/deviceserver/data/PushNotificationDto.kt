package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class PushNotificationDto(
    @JsonProperty("bundle_id")
    val bundleId: String,

    @JsonProperty("notification_content")
    val notificationContent: ByteArray
)
