package com.sadesocks.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.concurrent.thread

class SocksVpnService : VpnService() {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    companion object {
        const val ACTION_START = "com.sadesocks.app.START"
        const val ACTION_STOP = "com.sadesocks.app.STOP"
        private const val MTU = 1500
        private const val CHANNEL_ID = "sade_socks_vpn"

        @Volatile var state: State = State.DISCONNECTED
        @Volatile var message: String = ""
        @Volatile var mockStatus: String = ""
    }

    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var engineRunning = false
    private var session = 0
    private var spoofer: LocationSpoofer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
        } else {
            // ACTION_START, or the system starting us for Always-on VPN
            startTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        var id = 0
        var busy = false
        synchronized(lock) {
            if (state == State.CONNECTING || state == State.CONNECTED) {
                busy = true
            } else {
                session++
                id = session
                state = State.CONNECTING
                message = "Checking server..."
            }
        }
        if (busy) return

        val sid = id
        showForeground()
        Diag.log(this, "start requested")

        val cfg = Config.load(this)
        if (cfg == null) {
            fail(sid, "Server is not configured. Open the app, fill it and save.")
            return
        }
        thread(name = "sade-connect") { connect(sid, cfg) }
    }

    private fun connect(id: Int, cfg: Config) {
        try {
            val host = cfg.host.trim().trim('[', ']')
            Diag.log(this, "resolving $host")
            val addrs = InetAddress.getAllByName(host)
            val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()

            Diag.log(this, "probing ${ip.hostAddress}:${cfg.port}")
            Socks5Probe.check(ip, cfg.port, cfg.user, cfg.pass)
            Diag.log(this, "probe ok")

            // Find out what this proxy can really do, then pick the working DNS mode.
            message = "Testing proxy..."
            var remoteDns = true
            val domainRep = Socks5Probe.testDomain(ip, cfg.port, cfg.user, cfg.pass)
            Diag.log(this, "test domain connect: ${Socks5Probe.describe(domainRep)}")
            if (domainRep != 0) {
                val ipRep = Socks5Probe.testIp(ip, cfg.port, cfg.user, cfg.pass)
                Diag.log(this, "test ip connect: ${Socks5Probe.describe(ipRep)}")
                if (ipRep != 0) {
                    throw IllegalStateException(
                        "Proxy login works but it cannot open connections " +
                            "(domain: ${Socks5Probe.describe(domainRep)}, ip: ${Socks5Probe.describe(ipRep)})"
                    )
                }
                val udpRep = Socks5Probe.testUdp(ip, cfg.port, cfg.user, cfg.pass)
                Diag.log(this, "test udp: ${Socks5Probe.describe(udpRep)}")
                if (udpRep != 0) {
                    throw IllegalStateException(
                        "This proxy cannot resolve domain names and has no UDP, so websites cannot work through it"
                    )
                }
                remoteDns = false
            }
            Diag.log(this, if (remoteDns) "mode: remote DNS (domain names via proxy)" else "mode: plain DNS over UDP")

            synchronized(lock) {
                if (id != session) return
                message = "Starting tunnel..."

                val builder = Builder()
                    .setSession("Sade Socks")
                    .setMtu(MTU)
                    .addAddress("198.18.0.1", 32)
                    .addAddress("fc00::1", 128)
                    .addRoute("0.0.0.0", 0)      // all IPv4 through the tunnel
                    .addRoute("::", 0)           // all IPv6 through the tunnel (no IPv6 leak)
                    .addDisallowedApplication(packageName) // our own socket to the server stays outside
                if (remoteDns) {
                    builder.addDnsServer("198.18.0.2")   // answered inside the tunnel (no DNS leak)
                } else {
                    builder.addDnsServer("1.1.1.1")
                    builder.addDnsServer("8.8.8.8")
                }

                Diag.log(this, "establishing vpn")
                val pfd = builder.establish()
                    ?: throw IllegalStateException("VPN permission was not granted")
                tun = pfd

                File(filesDir, "hev.log").delete()
                val cfgFile = File(filesDir, "tunnel.yml")
                cfgFile.writeText(buildYaml(cfg, ip, remoteDns))

                Diag.log(this, "starting engine")
                val ok = TProxyService.TProxyStartService(cfgFile.absolutePath, pfd.fd)
                if (!ok) throw IllegalStateException("Tunnel engine failed to start")
                engineRunning = true

                state = State.CONNECTED
                message = if (remoteDns) "Connected" else "Connected (plain DNS mode)"
                Diag.log(this, "connected")
            }
            applyMockLocation(id, cfg, ip)
        } catch (t: Throwable) {
            Diag.log(this, "error: ${t.javaClass.simpleName}: ${t.message}")
            fail(id, friendly(t))
        }
    }

    /** After the tunnel is up: find the exit IP's location (or use manual lat/lon) and mock it. */
    private fun applyMockLocation(id: Int, cfg: Config, server: InetAddress) {
        try {
            val sp = Config.prefs(this)
            if (!sp.getBoolean("mock_enabled", false)) {
                mockStatus = ""
                return
            }
            val lat: Double
            val lon: Double
            val label: String
            if (sp.getString("mock_mode", "auto") == "manual") {
                lat = sp.getString("mock_lat", "")?.toDoubleOrNull()
                    ?: throw IllegalStateException("Manual latitude is invalid")
                lon = sp.getString("mock_lon", "")?.toDoubleOrNull()
                    ?: throw IllegalStateException("Manual longitude is invalid")
                label = "manual"
            } else {
                mockStatus = "Detecting exit IP location..."
                val g = GeoIp.lookupExit(this, cfg, server)
                lat = g.lat
                lon = g.lon
                label = "${g.place} (exit IP ${g.ip})"
            }
            synchronized(lock) {
                if (id != session) return
                spoofer?.stop()
                spoofer = null
                val s = LocationSpoofer(this)
                val err = s.start(lat, lon)
                if (err != null) {
                    mockStatus = "Mock location failed: $err"
                    return
                }
                spoofer = s
                mockStatus = "Mock location ON: $label  " + String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
            }
        } catch (t: Throwable) {
            Diag.log(this, "mock location error: ${t.javaClass.simpleName}: ${t.message}")
            mockStatus = "Mock location failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is UnknownHostException -> "Cannot find this host. Check the server address."
        is ConnectException -> "Cannot connect to the server. Wrong IP/port, or server is down."
        is SocketTimeoutException -> "Server did not respond (timeout). It may be dead or blocked."
        is UnsatisfiedLinkError, is ExceptionInInitializerError, is NoClassDefFoundError ->
            "Engine library failed to load: ${t.message}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"

    private fun buildYaml(cfg: Config, ip: InetAddress, remoteDns: Boolean): String {
        val addr = (ip.hostAddress ?: "").substringBefore('%')
        val logPath = File(filesDir, "hev.log").absolutePath
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  mtu: $MTU\n")
        sb.append("  ipv4: 198.18.0.1\n")
        sb.append("  ipv6: 'fc00::1'\n")
        sb.append("socks5:\n")
        sb.append("  port: ${cfg.port}\n")
        sb.append("  address: ${q(addr)}\n")
        sb.append("  udp: 'udp'\n")
        if (cfg.user.isNotEmpty()) {
            sb.append("  username: ${q(cfg.user)}\n")
            sb.append("  password: ${q(cfg.pass)}\n")
        }
        if (remoteDns) {
            sb.append("mapdns:\n")
            sb.append("  address: 198.18.0.2\n")
            sb.append("  port: 53\n")
            sb.append("  network: 100.64.0.0\n")
            sb.append("  netmask: 255.192.0.0\n")
            sb.append("  cache-size: 10000\n")
        }
        sb.append("misc:\n")
        sb.append("  log-file: ${q(logPath)}\n")
        sb.append("  log-level: warn\n")
        return sb.toString()
    }

    private fun showForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
            val n = b.setContentTitle("Sade Socks")
                .setContentText("VPN is running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(1, n)
        } catch (e: Throwable) {
            // The VPN still works without the foreground notification.
            Diag.log(this, "foreground notification skipped: ${e.javaClass.simpleName}")
        }
    }

    private fun removeForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun releaseResources() {
        synchronized(lock) {
            try {
                spoofer?.stop()
            } catch (e: Throwable) {
                // ignore
            }
            spoofer = null
            mockStatus = ""
            if (engineRunning) {
                try {
                    TProxyService.TProxyStopService()
                } catch (e: Throwable) {
                    // ignore
                }
                engineRunning = false
            }
            try {
                tun?.close()
            } catch (e: Throwable) {
                // ignore
            }
            tun = null
        }
    }

    private fun fail(id: Int, msg: String) {
        var stale = false
        synchronized(lock) {
            if (id != session) {
                stale = true
            } else {
                releaseResources()
                state = State.ERROR
                message = msg
            }
        }
        if (!stale) {
            removeForeground()
            stopSelf()
        }
    }

    private fun stopTunnel() {
        synchronized(lock) {
            session++
            releaseResources()
            state = State.DISCONNECTED
            message = "Disconnected"
        }
        removeForeground()
        Diag.log(this, "stopped")
    }

    override fun onRevoke() {
        Diag.log(this, "VPN revoked by system or another VPN app")
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (state == State.CONNECTED || state == State.CONNECTING) stopTunnel()
        super.onDestroy()
    }
}
