package com.badoo.automation.deviceserver.ios.proc

import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.TimeUnit

class LongRunningProcessListener(
    private val outReader: ((line: String) -> Unit)?,
    private val errReader: ((line: String) -> Unit)?
) : NuAbstractCharsetHandler(UTF_8.newEncoder(), UTF_8.newDecoder(), UTF_8.newDecoder()) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val errStringBuilder = StringBuilder(BUFFER_SIZE)
    private val outStringBuilder = StringBuilder(BUFFER_SIZE)
    private lateinit var nuProcess: NuProcess

    val isAlive: Boolean get() = nuProcess.isRunning
    val pid: Int get() = nuProcess.pid
    var exitCode: Int = Int.MIN_VALUE
        private set

    fun writeStdin(string: String) {
        nuProcess.writeStdin(ByteBuffer.wrap(string.toByteArray()))
    }

    fun destroy(force: Boolean, timeOut: Duration = Duration.ofSeconds(1)): Int {
        try {
            nuProcess.destroy(force)
        } catch (e: RuntimeException) {
            // destroy throws exception when it failed to send the signal to process (RuntimeException)
            logger.debug("Exception while terminating process $this", e)
        }

        return nuProcess.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun onPreStart(nuProcess: NuProcess) {
        this.nuProcess = nuProcess
        super.onPreStart(nuProcess)
    }

    override fun onExit(exitCode: Int) {
        logger.warn("Exiting process with pid [$pid] with exit code [$exitCode]")
        this.exitCode = exitCode
        super.onExit(exitCode)
    }

    //region read out and err
    override fun onStderrChars(buffer: CharBuffer?, closed: Boolean, coderResult: CoderResult?) {
        if (errReader != null) {
            fetchOutput(buffer, closed, errStringBuilder, errReader)
        }

        super.onStderrChars(buffer, closed, coderResult)
    }

    override fun onStdoutChars(buffer: CharBuffer?, closed: Boolean, coderResult: CoderResult?) {
        if (outReader != null) {
            fetchOutput(buffer, closed, outStringBuilder, outReader)
        }

        super.onStdoutChars(buffer, closed, coderResult)
    }

    private fun fetchOutput(
        buffer: CharBuffer?,
        closed: Boolean,
        stringBuilder: StringBuilder,
        readerFunction: (string: String) -> Unit
    ) {
        if (buffer != null && buffer.hasRemaining()) {
            val chars = CharArray(buffer.remaining())
            buffer.get(chars) // writes from buffer to char array

            for (c in chars) { // in case output is multi-line
                if (NEW_LINE == c) {
                    readerFunction(stringBuilder.toString())
                    stringBuilder.setLength(0) // clear
                } else {
                    stringBuilder.append(c)
                }
            }

            if (closed && stringBuilder.isNotEmpty()) { // in case no new line at the end
                readerFunction(stringBuilder.toString())
                stringBuilder.setLength(0) // clear
            }
        }
    }
    //endregion

    private companion object {
        const val BUFFER_SIZE = 262144 // should be ok: example 200 simulators * 2buffers * 256KB == 100 MB
        const val NEW_LINE: Char = '\n'
    }
}