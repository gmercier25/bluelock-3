package ca.gmercier.bluelock

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class BlueLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        if (CrashLog.isCrashProcess()) return
        CrashLog.step("Démarrage de l'app")
        Car.init(this)
        Notifs.ensureChannels(this)
    }
}

object Notifs {
    const val CH_WALK = "walkaway"
    const val CH_CMD = "commands"
    const val CH_RESULT = "results"
    const val CH_ALERT = "alerts"

    const val ID_WALK = 1001
    const val ID_CMD = 1002
    const val ID_RESULT = 1003
    const val ID_WALK_RESULT = 1004

    const val PI_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WALK, tr("Verrouillage auto", "Auto-lock"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("Compte à rebours et confirmation du verrouillage quand tu t'éloignes", "Countdown and lock confirmation when you walk away")
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CMD, tr("Commandes en cours", "Commands in progress"), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, tr("Alertes du véhicule", "Vehicle alerts"), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, tr("Résultats des commandes", "Command results"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PI_FLAGS
    )

    fun builder(ctx: Context, channel: String, title: String, text: String, icon: Int = R.drawable.ic_lock): Notification.Builder =
        Notification.Builder(ctx, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setColor(ctx.getColor(R.color.accent))

    fun result(ctx: Context, title: String, text: String, id: Int = ID_RESULT, icon: Int = R.drawable.ic_lock) {
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(id, builder(ctx, CH_RESULT, title, text, icon).setAutoCancel(true).build())
        } catch (e: SecurityException) { /* notifications refusées */ }
    }
}
