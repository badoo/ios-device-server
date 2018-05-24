package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.WaitTimeoutError
import org.slf4j.Logger
import org.slf4j.Marker
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun <T> executeWithTimeout(timeout: Duration, name: String, action: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit(action)
    executor.shutdown() // does not cancel already scheduled task, prevents new tasks

    try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        future.cancel(true)
        throw TimeoutException("$name timed out after ${timeout.seconds} seconds")
    } catch (e: ExecutionException) {
        when {
            e.cause == null -> throw e
            else -> throw e.cause!!
        }
    }
}

/**
 * [timeOut] — timeout seconds
 */
fun pollFor(timeOut: Duration, reasonName: String, shouldReturnOnTimeout: Boolean = false,
            retryInterval: Duration = Duration.ofSeconds(2), logger: Logger, marker: Marker, action: () -> Boolean) {
    var isSuccess: Boolean
    val startMillis = System.currentTimeMillis()
    val stopMillis = startMillis + timeOut.toMillis()

    logger.trace(marker, "Awaiting for: $reasonName...")
    do {
        isSuccess = action() // what if this hangs on IO ??

        if (isSuccess) {
            logger.trace(marker, "Awaited successfully for: $reasonName")
            break
        } else {
            Thread.sleep(retryInterval.toMillis())
        }
    } while (!isSuccess && stopMillis > System.currentTimeMillis())

    if (!isSuccess && !shouldReturnOnTimeout) {
        val message = "$reasonName failed after waiting ${timeOut.seconds} seconds"
        logger.error(marker, message)
        throw WaitTimeoutError(message)
    }
}

/**
 * returns [LocalDateTime] object containing Time.now in UTC
 */
fun dateNowUTC(): LocalDateTime = LocalDateTime.now(Clock.systemUTC())

/**
 *  Ensures [condition] is true, otherwise throws error [exception]
 */
fun ensure(condition: Boolean, exception: () -> RuntimeException) {
    if (!condition) {
        throw exception()
    }
}

fun uriWithPath(uri: URI, path: String): URI {
    return URI(listOf(uri.toString(), path).joinToString("/")).normalize()
}
