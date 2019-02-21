package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonValue

enum class PermissionType(@JsonValue val value: String) {
    // region yes/no/unset permissions
    Calendar("calendar"),

    Camera("camera"),

    Contacts("contacts"),

    Health("health"),

    HomeKit("homekit"),

    MediaLibrary("medialibrary"),

    Microphone("microphone"),

    Motion("motion"),

    Notifications("notifications"),

    Photos("photos"),

    Reminders("reminders"),

    Siri("siri"),

    Speech("speech"),
    // endregion

    // region always/inuse/never/unset permissions
    Location("location"),
    // endregion
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

