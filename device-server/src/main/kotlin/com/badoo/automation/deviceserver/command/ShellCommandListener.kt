package com.badoo.automation.deviceserver.command

import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.nio.charset.StandardCharsets.UTF_8

open class ShellCommandListener(initialStdOutBufferSize: Int = INITIAL_BYTE_ARRAY_SIZE) : IShellCommandListener, NuAbstractProcessHandler() {
    private lateinit var nuProcess: NuProcess
    // setting initial size to non-default for large amounts of data. to avoid too many Arrays.copyOf
    private val stdOutBytes = ByteArrayOutputStream(initialStdOutBufferSize)
    private val stdErrBytes = ByteArrayOutputStream(INITIAL_BYTE_ARRAY_SIZE)
    private val stdOutBytesChannel: WritableByteChannel = Channels.newChannel(stdOutBytes)
    private val stdErrBytesChannel: WritableByteChannel = Channels.newChannel(stdErrBytes)
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override var exitCode: Int = Int.MIN_VALUE
    override val stdOut: String get() = stdOutBytes.toString(UTF_8.name())
    override val stdErr: String get() = stdErrBytes.toString(UTF_8.name())
    override val bytes: ByteArray get() = stdOutBytes.toByteArray()

    override fun onPreStart(nuProcess: NuProcess) {
        this.nuProcess = nuProcess
    }

    override fun onExit(exitCode: Int) {
        this.exitCode = exitCode
    }

    override fun onStderr(buffer: ByteBuffer?, closed: Boolean) {
        fetchOutput(buffer, stdErrBytesChannel)
        super.onStderr(buffer, closed)
    }

    override fun onStdout(buffer: ByteBuffer?, closed: Boolean) {
        fetchOutput(buffer, stdOutBytesChannel)
        super.onStdout(buffer, closed)
    }

    private fun fetchOutput(buffer: ByteBuffer?, writer: WritableByteChannel) {
        if (buffer != null && buffer.hasRemaining()) {
            try {
                writer.write(buffer)
            } catch (e: IOException) {
                // should be ok to catch only IOException, as NonWritableChannelException is not expected as channel is writable
                logger.error("Failed to write data from buffer", e)
                throw e
            }
        }
    }

    companion object {
        const val INITIAL_BYTE_ARRAY_SIZE = Short.MAX_VALUE.toInt() // should be ok for most cases. increase if too many array copy
    }
}