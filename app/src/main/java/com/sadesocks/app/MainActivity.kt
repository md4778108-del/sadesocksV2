package com.sadesocks.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqVpn = 100
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        val sp = Config.prefs(this)
        etHost.setText(sp.getString("host", ""))
        val savedPort = sp.getInt("port", 0)
        if (savedPort > 0) etPort.setText(savedPort.toString())
        etUser.setText(sp.getString("user", ""))
        etPass.setText(sp.getString("pass", ""))

        btnConnect.setOnClickListener { onConnectClicked() }

        findViewById<Button>(R.id.btnVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Open Settings > Network > VPN manually", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings > Apps > Sade Socks > Battery > Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            } catch (e: Exception) {
                // notification permission is optional
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    /** Accepts a pasted line like  ip:port:user:pass  in the first box. */
    private fun splitLine(s: String): List<String>? {
        val p = s.trim().split(":")
        if (p.size != 4) return null
        if (p.any { it.isEmpty() }) return null
        val port = p[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return p
    }

    private fun onConnectClicked() {
        val st = SocksVpnService.state
        if (st == SocksVpnService.State.CONNECTED || st == SocksVpnService.State.CONNECTING) {
            startService(Intent(this, SocksVpnService::class.java).setAction(SocksVpnService.ACTION_STOP))
            return
        }

        val pasted = splitLine(etHost.text.toString())
        if (pasted != null) {
            etHost.setText(pasted[0])
            etPort.setText(pasted[1])
            etUser.setText(pasted[2])
            etPass.setText(pasted[3])
        }

        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 0
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter server IP or host", Toast.LENGTH_SHORT).show()
            return
        }
        if (port !in 1..65535) {
            Toast.makeText(this, "Enter a valid port (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }
        Config.save(this, Config(host, port, etUser.text.toString().trim(), etPass.text.toString().trim()))

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, reqVpn)
        } else {
            launchVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn) {
            if (resultCode == RESULT_OK) {
                launchVpn()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchVpn() {
        try {
            startService(Intent(this, SocksVpnService::class.java).setAction(SocksVpnService.ACTION_START))
        } catch (e: Exception) {
            Diag.log(this, "startService failed: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "Cannot start VPN: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLog() {
        tvLog.text = "State: ${SocksVpnService.state} / ${SocksVpnService.message}\n\n" + Diag.read(this)
    }

    private fun render() {
        val st = SocksVpnService.state
        val msg = SocksVpnService.message
        val editable = st == SocksVpnService.State.DISCONNECTED || st == SocksVpnService.State.ERROR

        tvStatus.text = when (st) {
            SocksVpnService.State.DISCONNECTED -> "Disconnected"
            SocksVpnService.State.CONNECTING -> if (msg.isEmpty()) "Connecting..." else msg
            SocksVpnService.State.CONNECTED -> if (msg.isEmpty()) "Connected" else msg
            SocksVpnService.State.ERROR -> "Error: $msg"
        }
        tvStatus.setTextColor(
            resources.getColor(
                when (st) {
                    SocksVpnService.State.CONNECTED -> R.color.sade_green_dark
                    SocksVpnService.State.CONNECTING -> R.color.sade_green
                    else -> R.color.sade_red_dark
                },
                theme
            )
        )
        btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(
            resources.getColor(if (editable) R.color.sade_green_dark else R.color.sade_red, theme)
        )
        btnConnect.text = if (editable) "Connect" else "Disconnect"
        etHost.isEnabled = editable
        etPort.isEnabled = editable
        etUser.isEnabled = editable
        etPass.isEnabled = editable
    }
}
