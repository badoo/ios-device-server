package com.badoo.automation.deviceserver.util

enum class HttpCodes(val code: Int) {
    OK(200),
    UnknownError(520),
    WebServerIsDown(521),
    ConnectionTimedOut(522),
    OriginIsUnreachable(523),
    NetworkReadTimeoutError(598),
}