package ca.gmercier.bluelock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Où est garée l'auto : position GPS du téléphone au moment où il quitte le Bluetooth du véhicule. */
object Parking {
    private const val KEY = "parking"

    data class Spot(val lat: Double, val lon: Double, val accuracyM: Float, val time: Long, val address: String?)

    fun last(): Spot? = Store.get(KEY)?.let {
        try {
            val j = JSONObject(it)
            Spot(
                j.getDouble("lat"), j.getDouble("lon"), j.optDouble("acc", 0.0).toFloat(), j.optLong("time"),
                j.optString("address").ifBlank { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun save(s: Spot) {
        Store.put(
            KEY, JSONObject().put("lat", s.lat).put("lon", s.lon).put("acc", s.accuracyM.toDouble())
                .put("time", s.time).put("address", s.address ?: "").toString()
        )
    }

    fun clear() = Store.put(KEY, null)

    fun hasForeground(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Capture la position (dernière connue récente + position actuelle, max ~10 s),
     * l'enregistre avec l'adresse si possible, puis appelle [done].
     */
    @SuppressLint("MissingPermission")
    fun capture(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        if (!hasForeground(app)) {
            done(); return
        }
        val lm = app.getSystemService(LocationManager::class.java)
        if (lm == null) {
            done(); return
        }
        val now = System.currentTimeMillis()
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }

        var best: Location? = null
        fun consider(l: Location?) {
            if (l == null) return
            if (now - l.time > 3 * 60_000L) return // trop vieille
            val b = best
            if (b == null || l.accuracy < b.accuracy) best = l
        }
        for (p in providers) {
            try { consider(lm.getLastKnownLocation(p)) } catch (_: Exception) {}
        }

        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            val l = best
            if (l == null) {
                done(); return
            }
            worker.execute {
                val address = try { geocode(app, l.latitude, l.longitude) } catch (_: Exception) { null }
                try {
                    save(Spot(l.latitude, l.longitude, l.accuracy, System.currentTimeMillis(), address))
                    CrashLog.step("Stationnement : position enregistrée (±${l.accuracy.toInt()} m)")
                } catch (_: Exception) {
                }
                done()
            }
        }

        val provider = providers.firstOrNull()
        if (Build.VERSION.SDK_INT >= 30 && provider != null) {
            val cancel = CancellationSignal()
            try {
                lm.getCurrentLocation(provider, cancel, worker) { l ->
                    consider(l)
                    main.post { finish() }
                }
                main.postDelayed({ cancel.cancel(); finish() }, 9_000)
                return
            } catch (_: Exception) {
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun geocode(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val list = Geocoder(ctx, Lang.locale()).getFromLocation(lat, lon, 1) ?: return null
        val a = list.firstOrNull() ?: return null
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null }
        return listOfNotNull(street ?: a.featureName, a.locality).distinct().joinToString(", ").ifBlank { null }
    }
}
