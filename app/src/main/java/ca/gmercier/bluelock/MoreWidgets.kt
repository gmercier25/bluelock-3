package ca.gmercier.bluelock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widgets supplémentaires : compact (2×2), commandes (4×1) et bouton de verrouillage (1×1). */
object MoreWidgets {

    private fun cmd(ctx: Context, code: Int, c: String): PendingIntent =
        PendingIntent.getForegroundService(ctx, code, CarCommandService.intent(ctx, c), Notifs.PI_FLAGS)

    /** Le déverrouillage passe par l'app (avec confirmation), jamais directement depuis l'écran d'accueil. */
    private fun unlockViaApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 41,
        Intent(ctx, MainActivity::class.java)
            .setAction("ca.gmercier.bluelock.SHORTCUT_UNLOCK")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        Notifs.PI_FLAGS
    )

    fun compact(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_compact)
        val st = Car.status()
        rv.setTextViewText(R.id.cTitle, Car.displayName())
        if (st == null) {
            rv.setTextViewText(R.id.cEnergy, "—")
            rv.setTextViewText(R.id.cRange, if (Config.load().hasCredentials()) "" else tr("À configurer", "Set up needed"))
            rv.setImageViewResource(R.id.cLockIcon, R.drawable.ic_lock_widget)
            rv.setTextViewText(R.id.cLockText, "")
        } else {
            rv.setTextViewText(R.id.cEnergy, Fmt.energyHeadline(st))
            rv.setTextViewText(R.id.cRange, st.range?.let { "~ ${Fmt.km(it)}" } ?: "")
            rv.setImageViewResource(R.id.cLockIcon, if (st.locked) R.drawable.ic_lock_green else R.drawable.ic_lock_open_red)
            val open = st.doorsOpen || st.trunkOpen || st.hoodOpen
            rv.setTextViewText(
                R.id.cLockText,
                when {
                    open -> tr("Ouvert !", "Open!")
                    st.locked -> tr("Verrouillé", "Locked")
                    else -> tr("Déverrouillé", "Unlocked")
                } + if (st.climate) tr(" · Clim", " · A/C") else ""
            )
        }
        rv.setOnClickPendingIntent(R.id.cRoot, Notifs.openApp(ctx))
        rv.setOnClickPendingIntent(R.id.cLockBtn, cmd(ctx, 30, CarCommandService.LOCK))
        return rv
    }

    fun actions(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_actions)
        rv.setTextViewText(R.id.aLockText, tr("Verrouiller", "Lock"))
        rv.setTextViewText(R.id.aUnlockText, tr("Déverrouiller", "Unlock"))
        rv.setTextViewText(R.id.aWarmText, tr("Chauffer", "Warm"))
        rv.setTextViewText(R.id.aCoolText, tr("Refroidir", "Cool"))
        rv.setTextViewText(R.id.aOffText, tr("Arrêter", "Stop"))
        rv.setOnClickPendingIntent(R.id.aLock, cmd(ctx, 40, CarCommandService.LOCK))
        rv.setOnClickPendingIntent(R.id.aUnlock, unlockViaApp(ctx))
        rv.setOnClickPendingIntent(R.id.aWarm, cmd(ctx, 42, CarCommandService.WARM))
        rv.setOnClickPendingIntent(R.id.aCool, cmd(ctx, 43, CarCommandService.COOL))
        rv.setOnClickPendingIntent(R.id.aOff, cmd(ctx, 44, CarCommandService.CLIMATE_OFF))
        return rv
    }

    fun lock(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_lock)
        val st = Car.status()
        when {
            st == null -> {
                rv.setInt(R.id.lCircle, "setBackgroundResource", R.drawable.widget_circle_idle)
                rv.setImageViewResource(R.id.lCircle, R.drawable.ic_lock_white)
                rv.setTextViewText(R.id.lText, "BlueLock")
            }
            st.locked -> {
                rv.setInt(R.id.lCircle, "setBackgroundResource", R.drawable.widget_circle_ok)
                rv.setImageViewResource(R.id.lCircle, R.drawable.ic_lock_white)
                rv.setTextViewText(R.id.lText, tr("Verrouillé", "Locked"))
            }
            else -> {
                rv.setInt(R.id.lCircle, "setBackgroundResource", R.drawable.widget_circle_warn)
                rv.setImageViewResource(R.id.lCircle, R.drawable.ic_lock_open_white)
                rv.setTextViewText(R.id.lText, tr("Verrouiller", "Lock"))
            }
        }
        rv.setOnClickPendingIntent(R.id.lRoot, cmd(ctx, 50, CarCommandService.LOCK))
        return rv
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val sets: List<Pair<Class<*>, (Context) -> RemoteViews>> = listOf(
            CompactWidgetProvider::class.java to ::compact,
            ActionsWidgetProvider::class.java to ::actions,
            LockWidgetProvider::class.java to ::lock,
        )
        for ((cls, build) in sets) {
            try {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) {
                    val rv = build(ctx)
                    for (id in ids) mgr.updateAppWidget(id, rv)
                }
            } catch (_: Exception) {
            }
        }
    }
}

abstract class BaseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Car.init(ctx)
        Widgets.updateAll(ctx)
        StatusRefreshJob.schedule(ctx)
    }

    override fun onEnabled(ctx: Context) {
        Car.init(ctx)
        StatusRefreshJob.schedule(ctx)
    }
}

class CompactWidgetProvider : BaseWidgetProvider()
class ActionsWidgetProvider : BaseWidgetProvider()
class LockWidgetProvider : BaseWidgetProvider()
