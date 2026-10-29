package ca.gmercier.bluelock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Alertes vérifiées à chaque rafraîchissement en arrière-plan (statut serveur, n'éveille pas l'auto) :
 *  - véhicule resté déverrouillé trop longtemps (hors trajet) ;
 *  - portière / coffre / capot ouvert à l'arrêt ;
 *  - batterie 12 V faible (au plus une fois par jour).
 */
object Alerts {
    private const val K = "alertState"
    const val ID_UNLOCKED = 2001
    const val ID_OPEN = 2002
    const val ID_12V = 2003

    private data class State(
        val unlockedSince: Long = 0,
        val unlockedNotified: Boolean = false,
        val openNotified: Boolean = false,
        val last12vDay: Int = -1,
    )

    private fun load(): State = Store.get(K)?.let {
        try {
            val j = JSONObject(it)
            State(j.optLong("u"), j.optBoolean("un"), j.optBoolean("on"), j.optInt("d", -1))
        } catch (e: Exception) {
            State()
        }
    } ?: State()

    private fun save(s: State) = Store.put(
        K, JSONObject().put("u", s.unlockedSince).put("un", s.unlockedNotified)
            .put("on", s.openNotified).put("d", s.last12vDay).toString()
    )

    private fun lockAction(ctx: Context): Notification.Action = Notification.Action.Builder(
        null, tr("Verrouiller", "Lock"),
        PendingIntent.getForegroundService(ctx, 2100, CarCommandService.intent(ctx, CarCommandService.LOCK), Notifs.PI_FLAGS)
    ).build()

    private fun notify(ctx: Context, id: Int, title: String, text: String, icon: Int, lock: Boolean) {
        try {
            val b = Notifs.builder(ctx, Notifs.CH_ALERT, title, text, icon).setAutoCancel(true)
            if (lock) b.addAction(lockAction(ctx))
            ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancelUnlocked(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_UNLOCKED)
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_OPEN)
        } catch (_: Exception) {
        }
    }

    fun check(ctx: Context, st: CarStatus) {
        val cfg = Config.load()
        var s = load()
        val now = System.currentTimeMillis()
        val inTrip = TripLog.inProgressSince() != null
        val name = Car.displayName()
        val parked = !st.engine && !st.climate && !inTrip

        // Déverrouillé trop longtemps
        if (!st.locked && parked) {
            val since = if (s.unlockedSince > 0) s.unlockedSince else minOf(now, st.lastRemote.takeIf { it > 0 } ?: now)
            s = s.copy(unlockedSince = since)
            if (cfg.alertUnlocked && !s.unlockedNotified && now - since >= cfg.alertUnlockedMin * 60_000L) {
                val min = ((now - since) / 60_000L).toInt()
                notify(
                    ctx, ID_UNLOCKED, tr("🔓 $name est déverrouillé", "🔓 $name is unlocked"),
                    tr("Déverrouillé depuis environ $min min.", "Unlocked for about $min min."),
                    R.drawable.ic_lock_open, lock = true
                )
                s = s.copy(unlockedNotified = true)
            }
        } else {
            if (st.locked) cancelUnlockedOnly(ctx)
            s = s.copy(unlockedSince = 0, unlockedNotified = false)
        }

        // Portière / coffre / capot ouvert à l'arrêt
        val open = listOfNotNull(
            tr("une portière", "a door").takeIf { st.doorsOpen },
            tr("le coffre", "the trunk").takeIf { st.trunkOpen },
            tr("le capot", "the hood").takeIf { st.hoodOpen },
        )
        if (open.isNotEmpty() && parked) {
            if (cfg.alertOpen && !s.openNotified) {
                notify(
                    ctx, ID_OPEN, tr("⚠ $name : ouvert", "⚠ $name: open"),
                    tr("Selon l'auto, ", "According to the car, ") + open.joinToString(", ") +
                        tr(" est ouvert.", " is open."),
                    R.drawable.ic_car, lock = false
                )
                s = s.copy(openNotified = true)
            }
        } else {
            s = s.copy(openNotified = false)
        }

        // Batterie 12 V faible
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val v = st.twelve
        if (cfg.alert12v && v != null && v > 0 && v < cfg.alert12vBelow && s.last12vDay != day) {
            notify(
                ctx, ID_12V, tr("🔋 Batterie 12 V faible", "🔋 Low 12V battery"),
                tr(
                    "$name : batterie 12 V à $v %. Évite d'utiliser les commandes à distance trop souvent et roule un peu pour la recharger.",
                    "$name: 12V battery at $v %. Avoid frequent remote commands and drive a bit to recharge it."
                ),
                R.drawable.ic_battery, lock = false
            )
            s = s.copy(last12vDay = day)
        }
        save(s)
    }

    private fun cancelUnlockedOnly(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_UNLOCKED)
        } catch (_: Exception) {
        }
    }
}
