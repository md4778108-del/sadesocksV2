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

    fun read(c: Context): String {
        return try {
            val a = File(c.filesDir, "app.log")
            val h = File(c.filesDir, "hev.log")
            val sb = StringBuilder()
            sb.append("--- app log ---\n")
            sb.append(if (a.exists()) a.readText().takeLast(2500) else "(empty)\n")
            sb.append("\n--- engine log ---\n")
            sb.append(if (h.exists()) h.readText().takeLast(2500) else "(empty)\n")
            sb.toString()
        } catch (e: Throwable) {
            "(cannot read log: ${e.message})"
        }
    }
}
