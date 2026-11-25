package com.sadesocks.app

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Quick SOCKS5 handshake test, run BEFORE the VPN starts so errors are clear. */
object Socks5Probe {
    fun check(ip: InetAddress, port: Int, user: String, pass: String) {
        Socket().use { s ->
            s.soTimeout = 8000
            s.connect(InetSocketAddress(ip, port), 8000)
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            val hasAuth = user.isNotEmpty()

            if (hasAuth) out.write(byteArrayOf(5, 2, 0, 2)) else out.write(byteArrayOf(5, 1, 0))
            out.flush()

            val ver = inp.readUnsignedByte()
            val method = inp.readUnsignedByte()
            if (ver != 5) throw IOException("This is not a SOCKS5 server")

            when (method) {
                0 -> Unit
                2 -> {
                    if (!hasAuth) throw IOException("Server needs username and password")
                    val u = user.toByteArray(Charsets.UTF_8)
                    val p = pass.toByteArray(Charsets.UTF_8)
                    if (u.size > 255 || p.size > 255) throw IOException("Username or password too long")
                    out.write(byteArrayOf(1, u.size.toByte()))
                    out.write(u)
                    out.write(byteArrayOf(p.size.toByte()))
                    out.write(p)
                    out.flush()
                    inp.readUnsignedByte()
                    val status = inp.readUnsignedByte()
                    if (status != 0) throw IOException("Wrong username or password")
                }
                else -> throw IOException("Server refused the login method")
            }
        }
    }
}
