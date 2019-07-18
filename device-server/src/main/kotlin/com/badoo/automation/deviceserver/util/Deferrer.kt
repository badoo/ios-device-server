package com.badoo.automation.deviceserver.util

import org.slf4j.Logger

/**
 * Deferrer tries to mimic Defer behaviour of Golang (see https://golang.org/ref/spec#Defer_statements)
 * with slightly changed behaviour - all deferred blocks are executed even if first one throws exception
 */
class Deferrer() {
    private val deferredActions = arrayListOf<() -> Unit>()

    /**
     * Adds a function or a block to a list of deferred functions which are executed in reverse order.
     * @param block function or code block
     */
    fun defer(block: () -> Unit) {
        deferredActions.add(block)
    }

    fun done(logger: Logger) {
        deferredActions.reversed().forEach { deferredAction ->
            try {
                deferredAction()
            } catch (e: Exception) {
                logger.error("Deferred action has caused an exception:", e)
            }
        }
    }
}

/**
 * Executes enclosing code in context of Deferrer tries to mimic Defer behaviour of Golang.
 * All deferred blocks are executed even if first one throws exception.
 * @see Deferrer
 */
inline fun <T> withDefers(logger: Logger, body: Deferrer.() -> T): T {
    val deferrer = Deferrer()
    return try {
        deferrer.body()
    } finally {
        deferrer.done(logger)
    }
}
