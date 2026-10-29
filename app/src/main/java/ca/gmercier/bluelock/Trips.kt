package ca.gmercier.bluelock

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Un trajet, construit localement :
 * début = connexion Bluetooth au véhicule, fin = déconnexion.
 * Odomètre et niveau d'essence viennent des statuts Hyundai avant / après.
 */
data class Trip(
    val start: Long,
    val end: Long,
    val odoStart: Double?,
    val odoEnd: Double?,
    val fuelStart: Int?,
    val fuelEnd: Int?,
) {
    val distanceKm: Double?
        get() = if (odoStart != null && odoEnd != null && odoEnd >= odoStart) odoEnd - odoStart else null

    val durationMin: Long get() = ((end - start) / 60_000L).coerceAtLeast(0)

    /** Plein (ou ajout d'essence) détecté entre le début et la fin. */
    val refuel: Boolean get() = fuelStart != null && fuelEnd != null && fuelEnd > fuelStart + 2

    fun fuelUsedL(tankL: Double): Double? {
        if (fuelStart == null || fuelEnd == null || refuel) return null
        return ((fuelStart - fuelEnd).coerceAtLeast(0)) / 100.0 * tankL
    }

    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("end", end)
        .put("odoStart", odoStart ?: JSONObject.NULL).put("odoEnd", odoEnd ?: JSONObject.NULL)
        .put("fuelStart", fuelStart ?: JSONObject.NULL).put("fuelEnd", fuelEnd ?: JSONObject.NULL)

    companion object {
        fun fromJson(j: JSONObject) = Trip(
            j.optLong("start"), j.optLong("end"),
            num(j.v("odoStart")), num(j.v("odoEnd")),
            num(j.v("fuelStart"))?.roundToInt(), num(j.v("fuelEnd"))?.roundToInt(),
        )
    }
}

/** Statistiques calculées à partir des trajets. */
data class TripStats(
    val distanceKm: Double,
    val fuelL: Double,
    val trips: Int,
) {
    /** L/100 km, seulement si assez de données pour être utile. */
    val lPer100: Double? get() = if (distanceKm >= 15 && fuelL > 0) fuelL / distanceKm * 100 else null
}

object TripLog {
    private const val K_TRIPS = "trips"
    private const val K_STATE = "tripState"
    private const val MAX_TRIPS = 300
    private const val JOB_ID = 4243

    /** Délai avant de lire le statut à l'arrivée (l'auto envoie ses données à l'arrêt). */
    private const val FINISH_DELAY_MS = 3 * 60 * 1000L

    /** Une reconnexion dans ce délai = même trajet (arrêt bref, coupure Bluetooth). */
    private const val SAME_TRIP_MS = 5 * 60 * 1000L

    // ------------------------------------------------------------ Lecture / écriture

    @Synchronized
    fun trips(): List<Trip> {
        val raw = Store.get(K_TRIPS) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Trip::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveTrips(list: List<Trip>) {
        val a = JSONArray()
        list.takeLast(MAX_TRIPS).forEach { a.put(it.toJson()) }
        Store.put(K_TRIPS, a.toString())
    }

    @Synchronized
    fun clear() {
        Store.put(K_TRIPS, null)
        Store.put(K_STATE, null)
    }

    private data class State(
        val start: Long,
        val odoStart: Double?,
        val fuelStart: Int?,
        val pendingEnd: Long?,   // déconnexion en attente de confirmation
    )

    private fun state(): State? = Store.get(K_STATE)?.let {
        try {
            val j = JSONObject(it)
            State(
                j.optLong("start"), num(j.v("odoStart")), num(j.v("fuelStart"))?.roundToInt(),
                num(j.v("pendingEnd"))?.toLong()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveState(s: State?) {
        Store.put(
            K_STATE, s?.let {
                JSONObject().put("start", it.start)
                    .put("odoStart", it.odoStart ?: JSONObject.NULL)
                    .put("fuelStart", it.fuelStart ?: JSONObject.NULL)
                    .put("pendingEnd", it.pendingEnd ?: JSONObject.NULL).toString()
            }
        )
    }

    // ------------------------------------------------------------ Événements Bluetooth

    /** Téléphone connecté au véhicule : début de trajet (ou reprise si coupure brève). */
    @Synchronized
    fun onConnect(ctx: Context) {
        val now = System.currentTimeMillis()
        val s = state()
        if (s != null && s.pendingEnd != null && now - s.pendingEnd < SAME_TRIP_MS) {
            // Simple coupure : on continue le même trajet.
            saveState(s.copy(pendingEnd = null))
            cancelFinish(ctx)
            CrashLog.step("Trajet : reprise après coupure")
            return
        }
        if (s != null && s.pendingEnd == null && now - s.start < 12 * 3600_000L) {
            return // déjà en cours
        }
        // Valeurs de départ : fin du trajet précédent, sinon dernier statut connu.
        val last = trips().lastOrNull()
        val st = Car.status()
        val useLast = last != null && (st == null || last.end >= st.lastRemote)
        saveState(
            State(
                start = now,
                odoStart = if (useLast) last!!.odoEnd else st?.odometer,
                fuelStart = if (useLast) last!!.fuelEnd else st?.fuel,
                pendingEnd = null,
            )
        )
        cancelFinish(ctx)
        CrashLog.step("Trajet : début")
    }

    /** Téléphone déconnecté : fin probable du trajet, confirmée quelques minutes plus tard. */
    @Synchronized
    fun onDisconnect(ctx: Context) {
        val s = state() ?: return
        if (s.pendingEnd != null) return
        saveState(s.copy(pendingEnd = System.currentTimeMillis()))
        scheduleFinish(ctx)
        CrashLog.step("Trajet : fin en attente")
    }

    /** Appelé par la tâche différée : lit le statut et enregistre le trajet. */
    fun finish(): Boolean {
        val s = synchronized(this) { state() } ?: return true
        val end = s.pendingEnd ?: return true
        var st = try {
            Car.refresh(false, null)
        } catch (e: Exception) {
            return false
        }
        // Si le serveur n'a pas encore reçu le statut d'arrivée, on interroge l'auto.
        if (st.lastRemote < end - 60_000L) {
            st = try {
                Car.refresh(true, null)
            } catch (e: Exception) {
                st
            }
        }
        synchronized(this) {
            val cur = state()
            if (cur == null || cur.pendingEnd != end) return true // reconnecté entre-temps
            val trip = Trip(s.start, end, s.odoStart, st.odometer, s.fuelStart, st.fuel)
            val dist = trip.distanceKm
            // On ignore les « trajets » sans déplacement (ex. auto démarrée dans l'entrée).
            if (dist == null || dist >= 0.5) {
                saveTrips(trips() + trip)
            }
            saveState(null)
        }
        CrashLog.step("Trajet : enregistré")
        return true
    }

    private fun scheduleFinish(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        try {
            js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, TripFinishJob::class.java))
                .setMinimumLatency(FINISH_DELAY_MS)
                .setOverrideDeadline(FINISH_DELAY_MS + 15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
        )
        } catch (e: Exception) {
            CrashLog.step("Trajet : planification impossible")
        }
    }

    private fun cancelFinish(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    // ------------------------------------------------------------ Statistiques

    private fun stats(list: List<Trip>, tankL: Double): TripStats {
        var d = 0.0
        var f = 0.0
        var n = 0
        for (t in list) {
            val dist = t.distanceKm ?: continue
            val fuel = t.fuelUsedL(tankL) ?: continue
            d += dist; f += fuel; n++
        }
        return TripStats(d, f, n)
    }

    /** Depuis le dernier plein détecté (hausse du niveau d'essence). */
    fun sinceRefuel(tankL: Double): TripStats {
        val all = trips()
        var idx = 0
        for (i in all.indices) {
            val t = all[i]
            val prev = all.getOrNull(i - 1)
            val refuelBetween = prev?.fuelEnd != null && t.fuelStart != null && t.fuelStart > prev.fuelEnd + 2
            if (t.refuel || refuelBetween) idx = i + (if (t.refuel) 1 else 0)
        }
        return stats(all.drop(idx), tankL)
    }

    fun overall(tankL: Double): TripStats = stats(trips(), tankL)

    fun last30Days(tankL: Double): TripStats {
        val since = System.currentTimeMillis() - 30L * 24 * 3600_000L
        return stats(trips().filter { it.end >= since }, tankL)
    }

    fun inProgressSince(): Long? = state()?.takeIf { it.pendingEnd == null }?.start
}

/** Tâche différée qui clôt le trajet quelques minutes après la déconnexion. */
class TripFinishJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Car.init(this)
        Car.executor.execute {
            val done = try {
                TripLog.finish()
            } catch (e: Exception) {
                true
            }
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

// ------------------------------------------------------------ Mise en forme

object TripFmt {
    private val fr: Locale get() = Lang.locale()

    fun num1(v: Double): String = String.format(fr, "%.1f", v)

    fun km(v: Double): String = if (v >= 100) "${v.roundToInt()} km" else "${num1(v)} km"

    fun duration(min: Long): String = if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "$min min"

    fun when_(ms: Long): String {
        val d = Date(ms)
        return DateFormat.getDateInstance(DateFormat.MEDIUM, fr).format(d) + ", " +
            DateFormat.getTimeInstance(DateFormat.SHORT, fr).format(d)
    }

    fun consumption(s: TripStats): String =
        s.lPer100?.let { "${num1(it)} L/100 km" } ?: tr("pas encore assez de données", "not enough data yet")

    /** Ligne courte pour l'écran principal. */
    fun summary(tankL: Double): String {
        val last = TripLog.trips().lastOrNull() ?: return tr("Trajets : aucun pour l'instant", "Trips: none yet")
        val parts = mutableListOf<String>()
        last.distanceKm?.let { parts += km(it) }
        parts += duration(last.durationMin)
        val avg = TripLog.sinceRefuel(tankL).lPer100 ?: TripLog.overall(tankL).lPer100
        avg?.let { parts += tr("moy. ", "avg ") + "${num1(it)} L/100" }
        return tr("Dernier trajet : ", "Last trip: ") + parts.joinToString(" · ")
    }
}

/** Écran « Trajets et consommation ». */
class TripsActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    private lateinit var col: LinearLayout
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Car.init(this)
        pad = (16 * resources.displayMetrics.density).toInt()
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 3, pad + pad / 4, pad + pad / 3, pad * 2)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        render()
    }

    private fun text(s: String, size: Float, bold: Boolean = false, secondary: Boolean = false, top: Int = 0): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            val attr = if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
            val ta = obtainStyledAttributes(intArrayOf(attr))
            setTextColor(ta.getColor(0, 0xFF000000.toInt()))
            ta.recycle()
            setPadding(0, top, 0, 0)
        }

    private fun section(title: String) {
        col.addView(text(title.uppercase(Lang.locale()), 13f, bold = true, top = pad * 3 / 2).apply {
            setTextColor(getColor(R.color.accent))
            letterSpacing = 0.06f
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = pad / 2
        layoutParams = lp
        col.addView(this)
    }

    private fun LinearLayout.line(label: String, value: String) {
        val row = LinearLayout(this@TripsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 4, 0, pad / 4)
        }
        row.addView(text(label, 15f, secondary = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(value, 15f, bold = true).apply { gravity = Gravity.END })
        addView(row)
    }

    private fun render() {
        col.removeAllViews()
        val tank = Config.load().tankL
        col.addView(text(tr("Trajets", "Trips"), 32f, bold = true))
        col.addView(
            text(
                tr(
                    "Calculé sur ce téléphone à partir de l'odomètre et du niveau d'essence envoyés par l'auto. " +
                        "Le niveau d'essence est arrondi au % près : la consommation d'un trajet court est approximative, " +
                        "la moyenne devient fiable sur plusieurs trajets.",
                    "Calculated on this phone from the odometer and fuel level reported by the car. " +
                        "Fuel level is rounded to the nearest %, so a short trip's consumption is approximate; " +
                        "the average becomes reliable over several trips."
                ),
                13f, secondary = true, top = pad / 3
            )
        )

        val trips = TripLog.trips()
        val cfg = Config.load()
        if (cfg.walkDeviceAddress.isBlank()) {
            col.addView(
                text(
                    tr("⚠ Choisis le Bluetooth de ton véhicule dans Réglages → Verrouillage auto pour que les trajets soient détectés.", "⚠ Pick your vehicle's Bluetooth in Settings → Auto-lock so trips can be detected."),
                    14f, top = pad
                ).apply { setTextColor(getColor(R.color.warn)) }
            )
        }
        TripLog.inProgressSince()?.let {
            col.addView(text(tr("🚗 Trajet en cours depuis ", "🚗 Trip in progress since ") + TripFmt.when_(it), 15f, top = pad))
        }

        section(tr("Consommation moyenne", "Average consumption"))
        card().apply {
            val r = TripLog.sinceRefuel(tank)
            val m = TripLog.last30Days(tank)
            val all = TripLog.overall(tank)
            line(tr("Depuis le dernier plein", "Since last fill-up"), TripFmt.consumption(r))
            line(tr("30 derniers jours", "Last 30 days"), TripFmt.consumption(m))
            line(tr("Depuis le début", "All time"), TripFmt.consumption(all))
            line(tr("Distance enregistrée", "Distance recorded"), TripFmt.km(all.distanceKm))
            line(tr("Essence utilisée (estimée)", "Fuel used (estimated)"), "${TripFmt.num1(all.fuelL)} L")
        }

        section(tr("Dernier trajet", "Last trip"))
        val last = trips.lastOrNull()
        card().apply {
            if (last == null) {
                addView(text(tr("Aucun trajet enregistré pour l'instant. Le prochain sera détecté automatiquement.", "No trips recorded yet. The next one will be detected automatically."), 15f))
            } else {
                line(tr("Départ", "Departure"), TripFmt.when_(last.start))
                line(tr("Arrivée", "Arrival"), TripFmt.when_(last.end))
                line(tr("Durée", "Duration"), TripFmt.duration(last.durationMin))
                line(tr("Distance", "Distance"), last.distanceKm?.let { TripFmt.km(it) } ?: tr("inconnue", "unknown"))
                if (last.refuel) {
                    line(tr("Essence", "Fuel"), tr("plein détecté", "fill-up detected"))
                } else {
                    val used = last.fuelUsedL(tank)
                    line(tr("Essence", "Fuel"), if (last.fuelStart != null && last.fuelEnd != null) "${last.fuelStart} % → ${last.fuelEnd} %" else tr("inconnue", "unknown"))
                    if (used != null) line(tr("Essence utilisée (≈)", "Fuel used (≈)"), "${TripFmt.num1(used)} L")
                    val dist = last.distanceKm
                    if (used != null && dist != null && dist >= 5 && used > 0) {
                        line(tr("Consommation (≈)", "Consumption (≈)"), "${TripFmt.num1(used / dist * 100)} L/100 km")
                    }
                }
                last.distanceKm?.let { d ->
                    if (last.durationMin > 0) line(tr("Vitesse moyenne", "Average speed"), "${(d / (last.durationMin / 60.0)).roundToInt()} km/h")
                }
            }
        }

        if (trips.size > 1) {
            section(tr("Historique", "History"))
            card().apply {
                trips.reversed().take(50).forEachIndexed { i, t ->
                    if (i > 0) addView(View(this@TripsActivity).apply {
                        setBackgroundColor(getColor(R.color.divider))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                    val dist = t.distanceKm?.let { TripFmt.km(it) } ?: "? km"
                    val fuel = when {
                        t.refuel -> tr(" · plein ⛽", " · fill-up ⛽")
                        t.fuelUsedL(tank) != null && t.fuelUsedL(tank)!! > 0 -> " · ≈ ${TripFmt.num1(t.fuelUsedL(tank)!!)} L"
                        else -> ""
                    }
                    addView(text(TripFmt.when_(t.start), 13f, secondary = true, top = pad / 3))
                    addView(text("$dist · ${TripFmt.duration(t.durationMin)}$fuel", 15f).apply { setPadding(0, 0, 0, pad / 3) })
                }
            }
        }

        col.addView(Button(this).apply {
            text = tr("Effacer l'historique des trajets", "Clear trip history")
            setTextColor(getColor(R.color.warn))
            background = null
            setOnClickListener {
                AlertDialog.Builder(this@TripsActivity)
                    .setTitle(tr("Effacer les trajets ?", "Clear trips?"))
                    .setMessage(tr("Supprime l'historique et les moyennes de ce téléphone.", "Deletes the history and averages from this phone."))
                    .setPositiveButton(tr("Effacer", "Clear")) { _, _ -> TripLog.clear(); render() }
                    .setNegativeButton(tr("Annuler", "Cancel"), null)
                    .show()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = pad * 2
            layoutParams = lp
        })
    }
}
