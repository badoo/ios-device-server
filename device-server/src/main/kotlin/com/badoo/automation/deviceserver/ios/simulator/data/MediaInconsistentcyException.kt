package com.badoo.automation.deviceserver.ios.simulator.data

class MediaInconsistentcyException: RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
