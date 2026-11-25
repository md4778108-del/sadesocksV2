package com.sadesocks.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Finds the real-world location of the proxy's EXIT IP.
 * The request goes through the SOCKS5 proxy itself (our app is excluded from the VPN, so we
 * open the proxy connection by hand), which means the geo service sees the exit IP.
 */
object GeoIp {

    data class Geo(val lat: Double, val lon: Double, val ip: String, val place: String)

    private class Provider(val host: String, val path: String)

    private val providers = listOf(
        Provider("ipwho.is", "/"),
        Provider("ipapi.co", "/json/")
    )

    fun lookupExit(c: Context, cfg: Config, server: InetAddress): Geo {
        for (p in providers) {
            for (asDomain in listOf(true, false)) {
                try {
                    val g = viaProxy(cfg, server, p, asDomain)
                    Diag.log(c, "geo ok via proxy (${p.host}): ${g.ip} ${g.place} ${g.lat},${g.lon}")
                    return g
                } catch (t: Throwable) {
                    Diag.log(c, "geo ${p.host} domain=$asDomain failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        // Last resort: locate the proxy SERVER address (usually the same place as the exit IP).
        val g = direct(server)
        Diag.log(c, "geo fallback (server ip): ${g.ip} ${g.place}")
        return g
    }

    private fun viaProxy(cfg: Config, server: InetAddress, p: Provider, asDomain: Boolean): Geo {
        val raw = Socks5Probe.connectVia(server, cfg.port, cfg.user, cfg.pass, p.host, 443, asDomain)
        var ssl: SSLSocket? = null
        try {
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, p.host, 443, true) as SSLSocket
            ssl.soTimeout = 10000
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(p.host, ssl.session)) {
                throw IOException("Certificate does not match ${p.host}")
            }
            val req = "GET ${p.path} HTTP/1.0\r\n" +
                "Host: ${p.host}\r\n" +
                "User-Agent: Mozilla/5.0\r\n" +
                "Accept: application/json\r\n" +
                "Connection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray())
            ssl.outputStream.flush()

            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(4096)
            try {
                while (buf.size() < 100_000) {
                    val n = ssl.inputStream.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
            } catch (e: IOException) {
                if (buf.size() == 0) throw e
            }
            return parse(buf.toString("UTF-8"))
        } finally {
            try {
                (ssl ?: raw).close()
            } catch (x: Throwable) {
                // ignore
            }
        }
    }

    private fun direct(server: InetAddress): Geo {
        val addr = (server.hostAddress ?: "").substringBefore('%')
        val con = URL("https://ipwho.is/$addr").openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 8000
            con.readTimeout = 8000
            con.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            con.disconnect()
        }
    }

    private fun parse(text: String): Geo {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("Geo service sent no JSON")
        val o = JSONObject(text.substring(a, b + 1))
        if (!o.optBoolean("success", true)) throw IOException(o.optString("message", "lookup failed"))
        if (o.optBoolean("error", false)) throw IOException(o.optString("reason", "lookup failed"))
        if (o.isNull("latitude") || o.isNull("longitude")) throw IOException("No coordinates in answer")
        val lat = o.getDouble("latitude")
        val lon = o.getDouble("longitude")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw IOException("Bad coordinates")
        val country = o.optString("country").ifEmpty { o.optString("country_name") }
        val place = listOf(o.optString("city"), country)
            .filter { it.isNotEmpty() && it != "null" }
            .joinToString(", ")
        return Geo(lat, lon, o.optString("ip"), place.ifEmpty { "unknown place" })
    }
}
