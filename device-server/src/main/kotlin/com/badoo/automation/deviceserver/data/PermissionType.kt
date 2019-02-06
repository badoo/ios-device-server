package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonValue

enum class PermissionType(@JsonValue val value: String) {
    Calendar("calendar"),

    Camera("camera"),

    Contacts("contacts"),

    HomeKit("homekit"),

    Microphone("microphone"),

    Photos("photos"),

    Reminders("reminders"),

    MediaLibrary("medialibrary"),

    Motion("motion"),

    Health("health"),

    Siri("siri"),

    Speech("speech");
}

enum class PermissionAllowed(@JsonValue val value: String) {
    Yes("yes"),

    No("no"),

    Always("always"),

    Inuse("inuse"),

    Never("never"),

    Unset("unset");
}

class PermissionSet: HashMap<PermissionType, PermissionAllowed>()

