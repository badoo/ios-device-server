package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonValue
import java.lang.IllegalArgumentException

enum class DiagnosticType(@JsonValue val value: String) {
    SystemLog("system_log");

    companion object {
        fun fromString(type: String): DiagnosticType {
            val rv = DiagnosticType.values().find { it.value == type }

            if (rv == null) {
                val msg = "Diagnostic type $type is not one of ${DiagnosticType.values().joinToString(", ")}"
                throw IllegalArgumentException(msg)
            }

            return rv
        }
    }
}
