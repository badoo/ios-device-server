package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonValue

@Suppress("unused")
enum class PermissionType(@JsonValue val value: String) {
    All("all"),
    Calendar("calendar"),
    Camera("camera"),
    ContactsLimited("contacts-limited"),
    Contacts("contacts"),
    Location("location"),
    LocationAlways("location-always"),
    MediaLibrary("media-library"),
    Microphone("microphone"),
    Motion("motion"),
    Photos("photos"),
    PhotosAdd("photos-add"),
    Reminders("reminders"),
    Siri("siri"),
}

enum class PermissionAllowed(@JsonValue val value: String) {
    Grant("grant"),
    Revoke("revoke"),
    Reset("reset"),
}

class PermissionSet : HashMap<PermissionType, PermissionAllowed>()

