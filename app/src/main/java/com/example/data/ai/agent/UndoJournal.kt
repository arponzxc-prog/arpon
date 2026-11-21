package com.example.data.ai.agent

import java.io.File

/**
 * Per-task checkpoint. Instead of copying the whole project, the original state of every file is
 * captured the first time the agent touches it (copy-on-write). [restore] puts everything back,
 * deleting files the agent created. One tap = full Undo of a task.
 */
class UndoJournal(private val root: File) {
    private val originals = LinkedHashMap<String, ByteArray?>()
    private var bytesHeld = 0L

    val touchedCount: Int get() = originals.size
    val isEmpty: Boolean get() = originals.isEmpty()

    /** Call BEFORE modifying/creating/deleting [file]. */
    fun record(file: File) {
        val rel = file.relativeTo(root.canonicalFile).path.replace('\\', '/')
        if (originals.containsKey(rel)) return
        if (file.isFile) {
            val len = file.length()
            if (len > MAX_FILE_BYTES || bytesHeld + len > MAX_TOTAL_BYTES) {
                throw IllegalStateException("Undo checkpoint limit reached; refusing to modify '$rel' without a way to undo.")
            }
            originals[rel] = file.readBytes()
            bytesHeld += len
        } else {
            originals[rel] = null
        }
    }

    /** Returns the relative paths that were restored. */
    fun restore(): List<String> {
        val restored = mutableListOf<String>()
        val canonicalRoot = root.canonicalFile
        for ((rel, bytes) in originals.entries.reversed()) {
            val f = File(canonicalRoot, rel)
            try {
                if (bytes == null) {
                    if (f.isFile) f.delete()
                } else {
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                }
                restored.add(rel)
            } catch (_: Exception) { /* best effort; remaining files still restored */ }
        }
        originals.clear()
        bytesHeld = 0
        return restored
    }

    companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
