package ca.gmercier.bluelock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exécute une commande en arrière-plan (widget, tuile, raccourci, notification),
 * avec une notification de progression puis de résultat.
 */
class CarCommandService : Service() {

    companion object {
        const val LOCK = "LOCK"
        const val UNLOCK = "UNLOCK"
        const val WARM = "WARM"
        const val COOL = "COOL"
        const val CLIMATE_OFF = "CLIMATE_OFF"
        const val REFRESH = "REFRESH"
        private const val EXTRA_CMD = "cmd"

        fun intent(ctx: Context, cmd: String): Intent =
            Intent(ctx, CarCommandService::class.java)
                .setAction("ca.gmercier.bluelock.CMD.$cmd")
                .putExtra(EXTRA_CMD, cmd)

        fun start(ctx: Context, cmd: String) {
            ctx.startForegroundService(intent(ctx, cmd))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressText(cmd: String) = when (cmd) {
        LOCK -> "Verrouillage en cours…"
        UNLOCK -> "Déverrouillage en cours…"
        WARM -> "Démarrage du chauffage…"
        COOL -> "Démarrage de la climatisation…"
        CLIMATE_OFF -> "Arrêt de la climatisation…"
        else -> "Mise à jour du statut…"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Car.init(this)
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: REFRESH
        val name = Car.displayName()
        val notif = Notifs.builder(this, Notifs.CH_CMD, name, progressText(cmd))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_CMD, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifs.ID_CMD, notif)
        }

        pending.incrementAndGet()
        Car.executor.execute {
            val cfg = Config.load()
            try {
                when (cmd) {
                    LOCK -> {
                        Car.lock(null)
                        Notifs.result(this, name, "🔒 Véhicule verrouillé")
                    }
                    UNLOCK -> {
                        Car.unlock(null)
                        Notifs.result(this, name, "🔓 Véhicule déverrouillé", icon = R.drawable.ic_lock_open)
                    }
                    WARM -> {
                        Car.climateOn(ClimateRequest.warm(cfg), null)
                        Notifs.result(this, name, "🔥 Chauffage démarré (${fmtTemp(cfg.warmTemp)})", icon = R.drawable.ic_climate)
                    }
                    COOL -> {
                        Car.climateOn(ClimateRequest.cool(cfg), null)
                        Notifs.result(this, name, "❄️ Climatisation démarrée (${fmtTemp(cfg.coolTemp)})", icon = R.drawable.ic_climate)
                    }
                    CLIMATE_OFF -> {
                        Car.climateOff(null)
                        Notifs.result(this, name, "Climatisation arrêtée", icon = R.drawable.ic_climate)
                    }
                    else -> Car.refresh(false, null)
                }
            } catch (e: Exception) {
                Notifs.result(this, "$name — échec", e.message ?: "Erreur inconnue")
            }
            Widgets.updateAll(this)
            if (pending.decrementAndGet() == 0) {
                main.post {
                    if (pending.get() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
}

fun fmtTemp(t: Double): String =
    (if (t % 1.0 == 0.0) t.toInt().toString() else t.toString().replace('.', ',')) + " °C"
