package com.sadesocks.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny file logger so problems can be seen inside the app (Show log button). */
object Diag {
    private const val MAX_BYTES = 20000L

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            val f = File(c.filesDir, "app.log")
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("$t $msg\n")
        } catch (e: Throwable) {
            // logging must never crash the app
        }
    }

    private fun tail(f: File, max: Int): String {
        if (!f.exists()) return "(empty)\n"
        return java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val n = if (len > max) max else len.toInt()
            raf.seek(len - n)
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }

    fun read(c: Context): String {
        return try {
            val sb = StringBuilder()
            sb.append("--- app log ---\n")
            sb.append(tail(File(c.filesDir, "app.log"), 2500))
            sb.append("\n--- engine log (latest) ---\n")
            sb.append(tail(File(c.filesDir, "hev.log"), 6000))
            sb.toString()
        } catch (e: Throwable) {
            "(cannot read log: ${e.message})"
        }
    }
}
