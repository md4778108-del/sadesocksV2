package com.sadesocks.app

/**
 * Intentionally empty.
 *
 * An older experimental GeoIp.kt (mock-location lookup) called Socks5Probe.connectVia(),
 * which does not exist in this version and broke the build ("Unresolved reference: connectVia").
 * Extracting this zip over an older checkout overwrites that stale file with this one.
 * Nothing in v3.4 uses it, so it is safe to delete.
 */
object GeoIp
