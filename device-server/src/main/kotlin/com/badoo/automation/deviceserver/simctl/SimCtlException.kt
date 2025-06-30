package com.badoo.automation.deviceserver.simctl

class SimCtlException(
    message: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    constructor(cause: Throwable) : this(cause.message, cause)
    constructor(message: String) : this(message, null)
}