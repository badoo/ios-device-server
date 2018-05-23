package com.badoo.automation.deviceserver.ios.fbsimctl

open class FBSimctlError(message: String?, cause: Throwable? = null) : IllegalStateException(message, cause)

class FBSimctlResponseParseError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)
