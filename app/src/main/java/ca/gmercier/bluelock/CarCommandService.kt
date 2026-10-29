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
        const val SCHEDULED = "SCHEDULED"
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
        LOCK -> tr("Verrouillage en cours…", "Locking…")
        UNLOCK -> tr("Déverrouillage en cours…", "Unlocking…")
        WARM -> tr("Démarrage du chauffage…", "Starting heat…")
        COOL -> tr("Démarrage de la climatisation…", "Starting A/C…")
        CLIMATE_OFF -> tr("Arrêt de la climatisation…", "Stopping climate…")
        SCHEDULED -> tr("Démarrage programmé…", "Scheduled start…")
        else -> tr("Mise à jour du statut…", "Updating status…")
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
                        Notifs.result(this, name, tr("🔒 Véhicule verrouillé", "🔒 Vehicle locked"))
                    }
                    UNLOCK -> {
                        Car.unlock(null)
                        Notifs.result(this, name, tr("🔓 Véhicule déverrouillé", "🔓 Vehicle unlocked"), icon = R.drawable.ic_lock_open)
                    }
                    WARM -> {
                        Car.climateOn(ClimateRequest.warm(cfg), null)
                        Notifs.result(this, name, tr("🔥 Chauffage démarré (", "🔥 Heating started (") + fmtTemp(cfg.warmTemp) + ")", icon = R.drawable.ic_climate)
                    }
                    COOL -> {
                        Car.climateOn(ClimateRequest.cool(cfg), null)
                        Notifs.result(this, name, tr("❄️ Climatisation démarrée (", "❄️ A/C started (") + fmtTemp(cfg.coolTemp) + ")", icon = R.drawable.ic_climate)
                    }
                    CLIMATE_OFF -> {
                        Car.climateOff(null)
                        Notifs.result(this, name, tr("Climatisation arrêtée", "Climate stopped"), icon = R.drawable.ic_climate)
                    }
                    SCHEDULED -> runScheduled(cfg, name)
                    else -> Car.refresh(false, null)
                }
            } catch (e: Exception) {
                Notifs.result(this, name + tr(" — échec", " — failed"), e.message ?: tr("Erreur inconnue", "Unknown error"))
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

/** Démarrage programmé : météo → chauffer / refroidir / rien. Lancé depuis le fil des commandes. */
private fun CarCommandService.runScheduled(cfg: Config, name: String) {
    val temp = if (cfg.schedMode == "weather") Weather.currentTempC() else null
    val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
    val outside = temp?.let { tr(" (", " (") + Weather.fmt(it) + tr(" dehors)", " outside)") } ?: ""
    when (Weather.decide(cfg, temp, month)) {
        "warm" -> {
            Car.climateOn(ClimateRequest.warm(cfg), null)
            Notifs.result(
                this, name,
                tr("⏰🔥 Préchauffage programmé démarré", "⏰🔥 Scheduled warm-up started") + outside +
                    " · ${cfg.durationMin} min" + if (temp == null && cfg.schedMode == "weather")
                    tr(" · météo indisponible, chauffage par défaut (saison froide)", " · weather unavailable, heating by default (cold season)") else "",
                icon = R.drawable.ic_climate
            )
        }
        "cool" -> {
            Car.climateOn(ClimateRequest.cool(cfg), null)
            Notifs.result(
                this, name, tr("⏰❄️ Refroidissement programmé démarré", "⏰❄️ Scheduled cool-down started") + outside +
                    " · ${cfg.durationMin} min",
                icon = R.drawable.ic_climate
            )
        }
        else -> Notifs.result(
            this, name,
            tr("⏰ Démarrage programmé non nécessaire", "⏰ Scheduled start not needed") + outside,
            icon = R.drawable.ic_climate
        )
    }
}

fun fmtTemp(t: Double): String =
    (if (t % 1.0 == 0.0) t.toInt().toString() else if (Lang.isEn) t.toString() else t.toString().replace('.', ',')) + " °C"
