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
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.Switch
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
    private lateinit var swMock: Switch
    private lateinit var llMock: View
    private lateinit var rbAuto: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var llManual: View
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var tvLoc: TextView

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

        swMock = findViewById(R.id.swMock)
        llMock = findViewById(R.id.llMock)
        rbAuto = findViewById(R.id.rbAuto)
        rbManual = findViewById(R.id.rbManual)
        llManual = findViewById(R.id.llManual)
        etLat = findViewById(R.id.etLat)
        etLon = findViewById(R.id.etLon)
        tvLoc = findViewById(R.id.tvLoc)

        swMock.isChecked = sp.getBoolean("mock_enabled", false)
        val manual = sp.getString("mock_mode", "auto") == "manual"
        rbManual.isChecked = manual
        rbAuto.isChecked = !manual
        etLat.setText(sp.getString("mock_lat", ""))
        etLon.setText(sp.getString("mock_lon", ""))
        swMock.setOnCheckedChangeListener { _, _ -> updateMockVisibility() }
        rbAuto.setOnCheckedChangeListener { _, _ -> updateMockVisibility() }
        rbManual.setOnCheckedChangeListener { _, _ -> updateMockVisibility() }
        updateMockVisibility()

        findViewById<Button>(R.id.btnDevOptions).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings > Developer options > Select mock location app", Toast.LENGTH_LONG).show()
            }
        }

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

    private fun updateMockVisibility() {
        llMock.visibility = if (swMock.isChecked) View.VISIBLE else View.GONE
        llManual.visibility = if (rbManual.isChecked) View.VISIBLE else View.GONE
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

        val lat = etLat.text.toString().trim()
        val lon = etLon.text.toString().trim()
        if (swMock.isChecked && rbManual.isChecked) {
            val la = lat.toDoubleOrNull()
            val lo = lon.toDoubleOrNull()
            if (la == null || la !in -90.0..90.0 || lo == null || lo !in -180.0..180.0) {
                Toast.makeText(this, "Enter valid latitude (-90..90) and longitude (-180..180)", Toast.LENGTH_LONG).show()
                return
            }
        }
        sp().edit()
            .putBoolean("mock_enabled", swMock.isChecked)
            .putString("mock_mode", if (rbManual.isChecked) "manual" else "auto")
            .putString("mock_lat", lat)
            .putString("mock_lon", lon)
            .apply()

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

    private fun sp() = Config.prefs(this)

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
        btnConnect.text = if (editable) "Connect" else "Disconnect"
        etHost.isEnabled = editable
        etPort.isEnabled = editable
        etUser.isEnabled = editable
        etPass.isEnabled = editable
        swMock.isEnabled = editable
        rbAuto.isEnabled = editable
        rbManual.isEnabled = editable
        etLat.isEnabled = editable
        etLon.isEnabled = editable

        val ms = SocksVpnService.mockStatus
        tvLoc.visibility = if (ms.isEmpty()) View.GONE else View.VISIBLE
        tvLoc.text = ms
    }
}
