package com.badoo.automation.deviceserver.ios.fbsimctl

open class FBSimctlError(message: String?, cause: Throwable? = null) : IllegalStateException(message, cause)

class FBSimctlResponseParseError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)

class EmptyApplicationsListError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)
class ApplicationNotFoundError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)
class DataContainerNotFoundError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)
class ApplicationContainerNotFoundError(message: String?, cause: Throwable? = null) : FBSimctlError(message, cause)
