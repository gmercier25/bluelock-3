package ca.gmercier.bluelock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Démarrage programmé : alarme exacte à l'heure choisie, décision selon la météo. */
object Scheduler {
    private const val ACTION_FIRE = "ca.gmercier.bluelock.SCHEDULE_FIRE"
    private const val K_SKIP = "schedSkip"

    /** Jour Calendar → bit (lundi = 0 … dimanche = 6). */
    fun dayBit(calDay: Int): Int = (calDay + 5) % 7

    /** Prochaine occurrence après [from], ou null si aucun jour choisi. */
    fun next(cfg: Config, from: Long): Long? {
        if (cfg.schedDays and 0x7F == 0) return null
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, cfg.schedHour)
        c.set(Calendar.MINUTE, cfg.schedMinute)
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_MONTH, 1)
        repeat(8) {
            if (cfg.schedDays and (1 shl dayBit(c.get(Calendar.DAY_OF_WEEK))) != 0) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    /** Prochaine occurrence qui sera réellement exécutée (en tenant compte d'un « sauter »). */
    fun nextEffective(cfg: Config): Long? {
        if (!cfg.schedEnabled) return null
        val n = next(cfg, System.currentTimeMillis()) ?: return null
        return if (skipped() == n) next(cfg, n) else n
    }

    fun skipped(): Long? = Store.get(K_SKIP)?.toLongOrNull()

    fun skipNext(ctx: Context) {
        val cfg = Config.load()
        val n = next(cfg, System.currentTimeMillis()) ?: return
        Store.put(K_SKIP, n.toString())
        reschedule(ctx)
    }

    fun unskip(ctx: Context) {
        Store.put(K_SKIP, null)
        reschedule(ctx)
    }

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 300, Intent(ctx, ScheduleAlarmReceiver::class.java).setAction(ACTION_FIRE), Notifs.PI_FLAGS
    )

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx))
        val cfg = Config.load()
        val at = nextEffective(cfg) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }
        CrashLog.step("Démarrage programmé : prochaine alarme planifiée")
    }

    fun isFire(intent: Intent?) = intent?.action == ACTION_FIRE

    fun describe(cfg: Config): String {
        val days = if (Lang.isEn) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        else listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")
        val daysText = when (cfg.schedDays and 0x7F) {
            0b0011111 -> tr("lun-ven", "Mon-Fri")
            0b1111111 -> tr("tous les jours", "every day")
            0b1100000 -> tr("fin de semaine", "weekends")
            else -> (0..6).filter { cfg.schedDays and (1 shl it) != 0 }.joinToString(" ") { days[it] }
        }
        return "${timeText(cfg.schedHour, cfg.schedMinute)} ($daysText)"
    }

    fun timeText(h: Int, m: Int): String =
        if (Lang.isEn) String.format(Locale.CANADA, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
        else String.format(Locale.CANADA_FRENCH, "%d h %02d", h, m)
}

/** Replanification après redémarrage / mise à jour / changement d'heure (ne démarre jamais l'auto). */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Car.init(ctx)
        Scheduler.reschedule(ctx)
        StatusRefreshJob.schedule(ctx)
    }
}

/** Alarme du démarrage programmé (non exporté : seule l'app peut le déclencher). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Car.init(ctx)
        if (Scheduler.isFire(intent)) {
            val cfg = Config.load()
            val skip = Scheduler.skipped()
            val now = System.currentTimeMillis()
            val wasSkipped = skip != null && kotlin.math.abs(skip - now) < 30 * 60_000L
            if (wasSkipped) Store.put("schedSkip", null)
            Scheduler.reschedule(ctx)
            if (cfg.schedEnabled && !wasSkipped && cfg.hasCredentials()) {
                try {
                    CarCommandService.start(ctx, CarCommandService.SCHEDULED)
                } catch (e: Exception) {
                    Notifs.result(ctx, tr("Démarrage programmé", "Scheduled start"), e.message ?: "?", icon = R.drawable.ic_climate)
                }
            }
        }
    }
}

/** Température extérieure (Open-Meteo, sans compte ni clé ; position arrondie à ~1 km). */
object Weather {
    private const val DEFAULT_LAT = 45.50  // Montréal, si aucune position connue
    private const val DEFAULT_LON = -73.57

    fun position(): Pair<Double, Double> {
        Parking.last()?.let { return it.lat to it.lon }
        Car.status()?.let { st -> if (st.lat != null && st.lon != null) return st.lat to st.lon }
        return DEFAULT_LAT to DEFAULT_LON
    }

    fun currentTempC(): Double? {
        val (lat, lon) = position()
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m",
            lat, lon
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            num(JSONObject(txt).optJSONObject("current")?.opt("temperature_2m"))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Décision : "warm", "cool" ou null (pas nécessaire). */
    fun decide(cfg: Config, tempC: Double?, month: Int): String? = when (cfg.schedMode) {
        "warm" -> "warm"
        "cool" -> "cool"
        else -> when {
            tempC == null -> if (month in listOf(10, 11, 0, 1, 2, 3)) "warm" else null // repli saisonnier
            tempC <= cfg.schedWarmBelow -> "warm"
            tempC >= cfg.schedCoolAbove -> "cool"
            else -> null
        }
    }

    fun fmt(t: Double): String = "${t.roundToInt()} °C"
}
