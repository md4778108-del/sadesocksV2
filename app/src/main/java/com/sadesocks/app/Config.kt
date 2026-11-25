package com.sadesocks.app

import android.content.Context
import android.content.SharedPreferences

data class Config(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String
) {
    companion object {
        private const val FILE = "sade_socks_cfg"

        fun prefs(c: Context): SharedPreferences =
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun load(c: Context): Config? {
            val sp = prefs(c)
            val host = (sp.getString("host", "") ?: "").trim()
            val port = sp.getInt("port", 0)
            if (host.isEmpty() || port !in 1..65535) return null
            return Config(
                host,
                port,
                sp.getString("user", "") ?: "",
                sp.getString("pass", "") ?: ""
            )
        }

        fun save(c: Context, cfg: Config) {
            prefs(c).edit()
                .putString("host", cfg.host)
                .putInt("port", cfg.port)
                .putString("user", cfg.user)
                .putString("pass", cfg.pass)
                .apply()
        }
    }
}
