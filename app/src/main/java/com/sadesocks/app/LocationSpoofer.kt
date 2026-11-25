package com.sadesocks.app

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlin.random.Random

/**
 * Feeds a fake location into Android's GPS and network providers.
 * Works only if this app is chosen under Developer options > "Select mock location app".
 */
class LocationSpoofer(private val ctx: Context) {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val names = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    @Volatile private var running = false
    private var worker: Thread? = null

    /** Returns null on success, or a short human-readable error. */
    fun start(lat: Double, lon: Double): String? {
        try {
            for (n in names) {
                try {
                    lm.removeTestProvider(n)
                } catch (e: Throwable) {
                    // not registered yet
                }
                lm.addTestProvider(
                    n, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(n, true)
            }
        } catch (e: SecurityException) {
            cleanup()
            return "Sade Socks is not the selected mock location app " +
                "(Developer options > Select mock location app)"
        } catch (e: Throwable) {
            cleanup()
            return "${e.javaClass.simpleName}: ${e.message}"
        }

        running = true
        val t = Thread({
            while (running) {
                push(lat, lon)
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "sade-mock-location")
        t.isDaemon = true
        worker = t
        t.start()
        Diag.log(ctx, "mock location started $lat,$lon")
        return null
    }

    private fun push(lat: Double, lon: Double) {
        for (n in names) {
            try {
                val l = Location(n)
                // ~1 m of jitter so it behaves like a real GPS fix
                l.latitude = lat + (Random.nextDouble() - 0.5) * 2e-5
                l.longitude = lon + (Random.nextDouble() - 0.5) * 2e-5
                l.accuracy = 5f + Random.nextFloat() * 5f
                l.altitude = 10.0
                l.speed = 0f
                l.bearing = 0f
                l.time = System.currentTimeMillis()
                l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                lm.setTestProviderLocation(n, l)
            } catch (e: Throwable) {
                // keep trying on the next tick
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        cleanup()
        Diag.log(ctx, "mock location stopped")
    }

    private fun cleanup() {
        for (n in names) {
            try {
                lm.setTestProviderEnabled(n, false)
            } catch (e: Throwable) {
                // ignore
            }
            try {
                lm.removeTestProvider(n)
            } catch (e: Throwable) {
                // ignore
            }
        }
    }
}
