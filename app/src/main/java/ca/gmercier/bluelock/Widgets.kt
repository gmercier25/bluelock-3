package ca.gmercier.bluelock

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Textes communs à l'app et au widget. */
object Fmt {
    private val fr: Locale get() = Lang.locale()

    fun km(v: Number?): String = if (v == null) "?" else NumberFormat.getIntegerInstance(fr).format(v.toLong()) + " km"

    fun time(ms: Long): String {
        if (ms <= 0) return tr("jamais", "never")
        val d = Date(ms)
        val sameDay = DateFormat.getDateInstance(DateFormat.SHORT, fr).format(d) ==
            DateFormat.getDateInstance(DateFormat.SHORT, fr).format(Date())
        val t = DateFormat.getTimeInstance(DateFormat.SHORT, fr).format(d)
        return if (sameDay) t else DateFormat.getDateInstance(DateFormat.MEDIUM, fr).format(d) + " " + t
    }

    fun energyHeadline(st: CarStatus): String = when {
        st.supportsCharge && st.soc != null -> "${st.soc} %"
        st.fuel != null -> tr("Essence ", "Fuel ") + "${st.fuel} %"
        st.soc != null && st.soc > 0 -> "${st.soc} %"
        else -> tr("Essence ?", "Fuel ?")
    }

    fun energyLine(st: CarStatus): String {
        val range = st.range?.let { " (~ ${km(it)})" } ?: ""
        return when {
            st.supportsCharge && st.soc != null && st.fuel != null -> tr("Batterie ", "Battery ") + "${st.soc} % · " + tr("Essence ", "Fuel ") + "${st.fuel} %$range"
            st.supportsCharge && st.soc != null -> tr("Batterie ", "Battery ") + "${st.soc} %$range"
            st.fuel != null -> tr("Essence ", "Fuel ") + "${st.fuel} %$range"
            else -> tr("Niveau d'essence inconnu", "Fuel level unknown") + range
        }
    }
}

object Widgets {

    private var cachedBitmap: Bitmap? = null
    private var cachedBitmapStamp = 0L

    private fun carBitmap(ctx: Context): Bitmap? {
        Car.init(ctx)
        val f = Car.carImageFile()
        if (!f.exists()) return null
        if (cachedBitmap != null && cachedBitmapStamp == f.lastModified()) return cachedBitmap
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 400) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        cachedBitmap = bmp
        cachedBitmapStamp = f.lastModified()
        return bmp
    }

    fun build(ctx: Context): RemoteViews {
        Car.init(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_car)
        val st = Car.status()
        rv.setTextViewText(R.id.wTitle, Car.displayName())

        val bmp = carBitmap(ctx)
        if (bmp != null) rv.setImageViewBitmap(R.id.wCar, bmp) else rv.setImageViewResource(R.id.wCar, R.drawable.ic_car)

        if (st == null) {
            rv.setTextViewText(R.id.wEnergy, if (Config.load().hasCredentials()) "—" else tr("À configurer", "Set up needed"))
            rv.setTextViewText(R.id.wRange, "")
            rv.setTextViewText(R.id.wLockText, "")
            rv.setViewVisibility(R.id.wLockIcon, View.GONE)
            rv.setTextViewText(R.id.wFooter, tr("Ouvre l'app pour commencer", "Open the app to get started"))
        } else {
            rv.setTextViewText(R.id.wEnergy, Fmt.energyHeadline(st))
            rv.setTextViewText(R.id.wRange, st.range?.let { "~ ${Fmt.km(it)}" } ?: "")
            rv.setViewVisibility(R.id.wLockIcon, View.VISIBLE)
            rv.setImageViewResource(R.id.wLockIcon, if (st.locked) R.drawable.ic_lock_green else R.drawable.ic_lock_open_red)
            rv.setTextViewText(
                R.id.wLockText,
                (if (st.locked) tr("Verrouillé", "Locked") else tr("Déverrouillé", "Unlocked")) + if (st.climate) tr(" · Clim", " · A/C") else ""
            )
            val parts = mutableListOf<String>()
            st.odometer?.let { parts += Fmt.km(it) }
            parts += Fmt.time(st.lastRemote)
            st.twelve?.let { parts += "12 V $it %" }
            rv.setTextViewText(R.id.wFooter, parts.joinToString(" · "))
        }

        rv.setOnClickPendingIntent(R.id.wRoot, Notifs.openApp(ctx))
        rv.setOnClickPendingIntent(
            R.id.wLockBtn,
            PendingIntent.getForegroundService(ctx, 10, CarCommandService.intent(ctx, CarCommandService.LOCK), Notifs.PI_FLAGS)
        )
        rv.setOnClickPendingIntent(
            R.id.wRefreshBtn,
            PendingIntent.getForegroundService(ctx, 11, CarCommandService.intent(ctx, CarCommandService.REFRESH), Notifs.PI_FLAGS)
        )
        return rv
    }

    fun updateAll(ctx: Context) {
        CrashLog.step("Mise à jour des widgets")
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val rv = build(ctx)
                for (id in ids) mgr.updateAppWidget(id, rv)
            }
        } catch (_: Exception) {
        }
        try {
            MoreWidgets.updateAll(ctx)
        } catch (_: Exception) {
        }
        try {
            MoreWidgets.updateAll(ctx)
        } catch (_: Exception) {
        }
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, LockTileService::class.java))
        } catch (_: Exception) {
        }
    }
}

class CarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Car.init(ctx)
        val rv = Widgets.build(ctx)
        for (id in ids) mgr.updateAppWidget(id, rv)
        StatusRefreshJob.schedule(ctx)
    }

    override fun onEnabled(ctx: Context) {
        StatusRefreshJob.schedule(ctx)
    }
}

/** Rafraîchit le statut (serveur Hyundai, sans réveiller l'auto) toutes les 30 min. */
class StatusRefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CrashLog.step("Tâche de fond : rafraîchissement")
        Car.init(this)
        Car.executor.execute {
            try {
                if (Config.load().hasCredentials()) {
                    val st = Car.refresh(false, null)
                    try { Alerts.check(this, st) } catch (_: Exception) {}
                    Car.ensureImage()
                }
            } catch (_: Exception) {
            }
            Widgets.updateAll(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4244
        private const val OLD_JOB_ID = 4242

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(OLD_JOB_ID) != null) js.cancel(OLD_JOB_ID)
            if (js.getPendingJob(JOB_ID) != null) return
            try { js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, StatusRefreshJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            ) } catch (e: Exception) { CrashLog.step("Planification impossible : ${e.javaClass.simpleName}") }
        }
    }
}

/** Tuile des réglages rapides : verrouiller d'un geste. */
class LockTileService : TileService() {
    override fun onStartListening() {
        Car.init(this)
        val t = qsTile ?: return
        val st = Car.status()
        t.label = tr("Verrouiller", "Lock")
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                st == null -> Car.displayName()
                st.locked -> tr("Verrouillé", "Locked")
                else -> tr("Déverrouillé", "Unlocked")
            }
        }
        t.state = if (st != null && !st.locked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, ShortcutActivity::class.java)
            .setAction(ShortcutActivity.ACTION_LOCK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 20, i, Notifs.PI_FLAGS))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
