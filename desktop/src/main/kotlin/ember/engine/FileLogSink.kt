package ember.engine

import com.soheil.ember.data.LogSink
import java.io.File
import java.time.LocalDateTime

/** Appends log lines to ember.log (and stderr) so problems can be diagnosed after the fact. */
class FileLogSink(private val file: File) : LogSink {

    init {
        // Keep the log small: start a fresh file when the old one is large.
        if (file.length() > 2_000_000) file.delete()
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = "${LocalDateTime.now()} $level/$tag: $message" +
            (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        System.err.println(line)
        runCatching {
            file.appendText(line + "\n")
            if (error != null) file.appendText(error.stackTraceToString().take(4000) + "\n")
        }
    }

    override fun d(tag: String, message: String) = write("D", tag, message, null)
    override fun i(tag: String, message: String) = write("I", tag, message, null)
    override fun w(tag: String, message: String, error: Throwable?) = write("W", tag, message, error)
    override fun e(tag: String, message: String, error: Throwable?) = write("E", tag, message, error)
}
