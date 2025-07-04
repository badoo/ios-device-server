package com.badoo.automation.deviceserver.util

import org.slf4j.Logger
import org.slf4j.Marker
import java.util.concurrent.Semaphore

fun Semaphore.withSemaphore(actionName: String, logger: Logger, logMarker: Marker, action: () -> Unit) {
    try {
        logger.info(logMarker, "Will acquire a semaphore for executing action <$actionName>")
        this.acquire()
        logger.info(logMarker, "Have acquired a semaphore for executing action <$actionName>. Executing action now")
        action()
    } finally {
        this.release()
        logger.info(logMarker, "Have released a semaphore for executing action <$actionName>. Semaphore is now available for other actions")
    }
}