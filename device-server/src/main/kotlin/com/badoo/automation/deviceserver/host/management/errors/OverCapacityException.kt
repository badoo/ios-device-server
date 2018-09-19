package com.badoo.automation.deviceserver.host.management.errors

class OverCapacityException : RuntimeException {
    constructor(message: String, e: Throwable) : super(message, e)
    constructor(message: String) : super(message)
}
