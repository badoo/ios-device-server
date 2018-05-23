package com.badoo.automation.deviceserver.data

enum class DeviceState(val value: String) {
    NONE("none"),
    CREATING("creating"),
    RESETTING("resetting"),
    CREATED("created"),
    REVIVING("reviving"),
    FAILED("failed");

    override fun toString(): String {
        return value
    }
}
