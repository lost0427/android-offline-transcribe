package com.voiceping.offlinetranscription.timeline

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Append-only, locally stored write-ahead journal. Call [append] before writing
 * screenshots, OCR results, or reports so a restarted process can recover context.
 */
class EventJournal(private val file: File) {
    @Synchronized
    fun append(event: TimelineEvent) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write((event.toNdjson() + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun readRecords(): List<String> = if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()

    private fun TimelineEvent.toNdjson(): String = JSONObject().apply {
        put("sessionId", sessionId)
        put("timestampMillis", timestampMillis)
        put("packageName", packageName)
        put("type", type.name)
        put("summary", summary)
        put("recovered", recovered)
    }.toString()
}
