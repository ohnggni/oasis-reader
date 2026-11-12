package com.oasisreader.app.data

import java.io.File

/**
 * Writes [text] so that the file is either the old one or the new one, never half of it: a queue
 * or cache file cut short by a crash used to read as "empty". Written beside the file, then renamed.
 */
fun writeTextAtomicTo(file: File, text: String) = file.writeTextAtomic(text)

fun File.writeTextAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        // some file systems will not rename over an existing file
        writeText(text)
        tmp.delete()
    }
}
