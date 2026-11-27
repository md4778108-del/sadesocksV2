package com.sadesocks.app

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** SOCKS5 checks, run BEFORE the VPN starts so errors are clear and the right mode is chosen. */
object Socks5Probe {

    private const val TIMEOUT_MS = 6000

    private class Session(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val inp = DataInputStream(socket.getInputStream())
    }

    private fun open(ip: InetAddress, port: Int, user: String, pass: String): Session {
        val s = Socket()
        try {
            s.soTimeout = TIMEOUT_MS
            s.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            val ses = Session(s)
            handshake(ses, user, pass)
            return ses
        } catch (e: Throwable) {
            try {
                s.close()
            } catch (x: Throwable) {
                // ignore
            }
            throw e
        }
    }

    private fun handshake(ses: Session, user: String, pass: String) {
        val hasAuth = user.isNotEmpty()
        if (hasAuth) ses.out.write(byteArrayOf(5, 2, 0, 2)) else ses.out.write(byteArrayOf(5, 1, 0))
        ses.out.flush()

        val ver = ses.inp.readUnsignedByte()
        val method = ses.inp.readUnsignedByte()
        if (ver != 5) throw IOException("This is not a SOCKS5 server")

        when (method) {
            0 -> Unit
            2 -> {
                if (!hasAuth) throw IOException("Server needs username and password")
                val u = user.toByteArray(Charsets.UTF_8)
                val p = pass.toByteArray(Charsets.UTF_8)
                if (u.size > 255 || p.size > 255) throw IOException("Username or password too long")
                ses.out.write(byteArrayOf(1, u.size.toByte()))
                ses.out.write(u)
                ses.out.write(byteArrayOf(p.size.toByte()))
                ses.out.write(p)
                ses.out.flush()
                ses.inp.readUnsignedByte()
                val status = ses.inp.readUnsignedByte()
                if (status != 0) throw IOException("Wrong username or password")
            }
            else -> throw IOException("Server refused the login method")
        }
    }

    /** Handshake only. Throws a clear error if the server or login is wrong. */
    fun check(ip: InetAddress, port: Int, user: String, pass: String) {
        open(ip, port, user, pass).socket.close()
    }

    private fun request(
        ip: InetAddress, port: Int, user: String, pass: String,
        cmd: Int, atyp: Int, addr: ByteArray, targetPort: Int
    ): Int {
        val ses = open(ip, port, user, pass)
        try {
            val req = ByteArrayOutputStream()
            req.write(5)
            req.write(cmd)
            req.write(0)
            req.write(atyp)
            if (atyp == 3) req.write(addr.size)
            req.write(addr)
            req.write(targetPort shr 8)
            req.write(targetPort and 0xff)
            ses.out.write(req.toByteArray())
            ses.out.flush()
            val ver = ses.inp.readUnsignedByte()
            val rep = ses.inp.readUnsignedByte()
            if (ver != 5) throw IOException("Bad SOCKS5 reply")
            return rep
        } finally {
            try {
                ses.socket.close()
            } catch (x: Throwable) {
                // ignore
            }
        }
    }

    /** SOCKS5 reply code. 0 = success. -1 = no answer / error. */
    private fun safe(block: () -> Int): Int = try {
        block()
    } catch (t: Throwable) {
        -1
    }

    /** Can the proxy connect to a DOMAIN NAME? (needed for remote DNS mode) */
    fun testDomain(ip: InetAddress, port: Int, user: String, pass: String): Int = safe {
        request(ip, port, user, pass, 1, 3, "example.com".toByteArray(), 443)
    }

    /** Can the proxy connect to a plain IP address? */
    fun testIp(ip: InetAddress, port: Int, user: String, pass: String): Int = safe {
        request(ip, port, user, pass, 1, 1, byteArrayOf(1, 1, 1, 1), 443)
    }

    /** Does the proxy accept UDP ASSOCIATE? (needed for plain DNS mode) */
    fun testUdp(ip: InetAddress, port: Int, user: String, pass: String): Int = safe {
        request(ip, port, user, pass, 3, 1, byteArrayOf(0, 0, 0, 0), 0)
    }

    fun describe(code: Int): String = when (code) {
        0 -> "ok"
        1 -> "general failure"
        2 -> "not allowed by proxy rules"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        7 -> "command not supported"
        8 -> "address type not supported"
        -1 -> "no answer"
        else -> "code $code"
    }
}
