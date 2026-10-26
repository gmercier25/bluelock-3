package ca.gmercier.bluelock

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Déclenché par le système quand le téléphone se connecte / se déconnecte du Bluetooth.
 * Si c'est le Bluetooth du véhicule choisi dans les réglages → lance le verrouillage auto.
 */
class WalkAwayReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Car.init(ctx)
        val cfg = Config.load()
        if (!cfg.walkEnabled || cfg.walkDeviceAddress.isBlank()) return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = try {
            dev?.address
        } catch (e: SecurityException) {
            null
        } ?: return
        if (!address.equals(cfg.walkDeviceAddress, ignoreCase = true)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> WalkAwayService.start(ctx, dryRun = false)
            BluetoothDevice.ACTION_ACL_CONNECTED -> WalkAwayService.reconnected(ctx)
        }
    }
}

/**
 * Service de premier plan du verrouillage auto :
 *  - mode normal : compte à rebours (annulable) → vérification → verrouillage ;
 *  - mode confirmation : vérification → notification « Verrouiller ? » (ne verrouille jamais seul).
 * Si le Bluetooth du véhicule se reconnecte pendant le compte à rebours, tout est annulé.
 */
class WalkAwayService : Service() {

    companion object {
        private const val ACTION_START = "ca.gmercier.bluelock.WALK_START"
        private const val ACTION_CANCEL = "ca.gmercier.bluelock.WALK_CANCEL"
        private const val ACTION_LOCK_NOW = "ca.gmercier.bluelock.WALK_LOCK_NOW"
        private const val ACTION_RECONNECTED = "ca.gmercier.bluelock.WALK_RECONNECTED"
        private const val EXTRA_DRY = "dry"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context, dryRun: Boolean) {
            val i = Intent(ctx, WalkAwayService::class.java).setAction(ACTION_START).putExtra(EXTRA_DRY, dryRun)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // Android a bloqué le démarrage en arrière-plan : on propose de verrouiller d'un toucher.
                val pi = PendingIntent.getForegroundService(
                    ctx, 77, CarCommandService.intent(ctx, CarCommandService.LOCK), Notifs.PI_FLAGS
                )
                try {
                    ctx.getSystemService(NotificationManager::class.java).notify(
                        Notifs.ID_WALK_RESULT,
                        Notifs.builder(
                            ctx, Notifs.CH_WALK, Car.displayName(),
                            "Tu t'es éloigné du véhicule. Touche ici pour le verrouiller."
                        ).setContentIntent(pi).setAutoCancel(true).build()
                    )
                } catch (_: SecurityException) {
                }
            }
        }

        fun reconnected(ctx: Context) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, WalkAwayService::class.java).setAction(ACTION_RECONNECTED))
            } catch (_: Exception) {
            }
        }
    }

    private enum class Phase { IDLE, COUNTDOWN, CHECKING, CONFIRM, LOCKING }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var dryRun = false
    private var wake: PowerManager.WakeLock? = null
    private var lastNotif: android.app.Notification? = null
    private val fire = Runnable { proceed(Car.WalkMode.LOCK) }
    private val confirmTimeout = Runnable {
        if (phase == Phase.CONFIRM) finish(null, null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, WalkAwayService::class.java).setAction(action), Notifs.PI_FLAGS
    )

    private fun goForeground(n: android.app.Notification) {
        lastNotif = n
        if (Build.VERSION.SDK_INT >= 29) {
            // « Appareil connecté » exige la permission Bluetooth (Android 14) ; sinon (ex. test) : synchro.
            val btOk = Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val type = if (btOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(Notifs.ID_WALK, n, type)
        } else {
            startForeground(Notifs.ID_WALK, n)
        }
    }

    private fun show(n: android.app.Notification) {
        lastNotif = n
        try {
            getSystemService(NotificationManager::class.java).notify(Notifs.ID_WALK, n)
        } catch (_: SecurityException) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Car.init(this)
        when (intent?.action) {
            ACTION_START -> onStart(intent.getBooleanExtra(EXTRA_DRY, false))
            ACTION_CANCEL -> finish(null, null)
            ACTION_LOCK_NOW -> if (phase == Phase.COUNTDOWN || phase == Phase.CONFIRM) {
                main.removeCallbacks(fire)
                main.removeCallbacks(confirmTimeout)
                proceed(if (dryRun) Car.WalkMode.DRY_RUN else Car.WalkMode.LOCK)
            }
            ACTION_RECONNECTED -> if (phase == Phase.COUNTDOWN || phase == Phase.CONFIRM) {
                finish("Verrouillage auto annulé", "Le téléphone s'est reconnecté au véhicule.")
            }
        }
        if (phase == Phase.IDLE && !running) stopSelf()
        return START_NOT_STICKY
    }

    private fun onStart(dry: Boolean) {
        val name = Car.displayName()
        if (phase != Phase.IDLE) {
            // Déjà en cours : il faut quand même rappeler startForeground.
            lastNotif?.let { goForeground(it) }
            return
        }
        running = true
        dryRun = dry
        val cfg = Config.load()
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bluelock:walkaway").apply {
            setReferenceCounted(false)
            acquire((cfg.walkDelaySec + 240) * 1000L)
        }
        val prefix = if (dry) "[Test] " else ""

        if (cfg.walkConfirm) {
            goForeground(
                Notifs.builder(this, Notifs.CH_WALK, "$prefix$name", "Tu t'éloignes… vérification du véhicule.", R.drawable.ic_walk)
                    .setOngoing(true).setProgress(0, 0, true).build()
            )
            proceed(Car.WalkMode.CHECK_ONLY)
            return
        }

        val delayMs = cfg.walkDelaySec.coerceAtLeast(0) * 1000L
        phase = Phase.COUNTDOWN
        val deadline = System.currentTimeMillis() + delayMs
        val n = Notifs.builder(
            this, Notifs.CH_WALK, "${prefix}Verrouillage auto — $name",
            if (dry) "Simulation : vérification à la fin du compte à rebours (aucun verrouillage)."
            else "Le véhicule sera verrouillé à la fin du compte à rebours s'il est déverrouillé et arrêté.",
            R.drawable.ic_walk
        )
            .setOngoing(true)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(android.app.Notification.CATEGORY_STATUS)
            .addAction(android.app.Notification.Action.Builder(null, "Annuler", pi(ACTION_CANCEL, 1)).build())
            .addAction(android.app.Notification.Action.Builder(null, "Verrouiller maintenant", pi(ACTION_LOCK_NOW, 2)).build())
            .build()
        goForeground(n)
        main.postDelayed(fire, delayMs)
    }

    private fun proceed(requested: Car.WalkMode) {
        val mode = if (dryRun && requested == Car.WalkMode.LOCK) Car.WalkMode.DRY_RUN else requested
        phase = if (mode == Car.WalkMode.CHECK_ONLY) Phase.CHECKING else Phase.LOCKING
        val name = Car.displayName()
        show(
            Notifs.builder(
                this, Notifs.CH_WALK, name,
                if (mode == Car.WalkMode.LOCK) "Vérification puis verrouillage…" else "Vérification du véhicule…",
                R.drawable.ic_walk
            ).setOngoing(true).setProgress(0, 0, true).build()
        )
        Car.executor.execute {
            val result = Car.walkAway(mode)
            main.post { onResult(result) }
        }
    }

    private fun onResult(r: WalkResult) {
        val name = Car.displayName()
        val cfg = Config.load()
        val prefix = if (dryRun) "[Test] " else ""
        when (r) {
            is WalkResult.Locked -> finish("🔒 $name verrouillé", "Verrouillé automatiquement quand tu t'es éloigné.")
            is WalkResult.AlreadyLocked ->
                if (cfg.walkNotifyAlreadyLocked || dryRun) finish("$prefix$name déjà verrouillé ✓", "Aucune action nécessaire.")
                else finish(null, null)
            is WalkResult.Running -> finish("$prefix$name en marche", "Moteur ou climatisation en marche : pas de verrouillage automatique.")
            is WalkResult.WouldLock -> finish("[Test] Verrouillage auto OK", "Le véhicule est déverrouillé et arrêté : il aurait été verrouillé.")
            is WalkResult.Failed -> finish("${prefix}Verrouillage auto impossible", r.message)
            is WalkResult.NeedsConfirm -> {
                phase = Phase.CONFIRM
                show(
                    Notifs.builder(
                        this, Notifs.CH_WALK, "$prefix$name est déverrouillé",
                        "Tu t'es éloigné du véhicule. Le verrouiller ?", R.drawable.ic_lock_open
                    )
                        .setOngoing(true)
                        .setCategory(android.app.Notification.CATEGORY_REMINDER)
                        .addAction(android.app.Notification.Action.Builder(null, "Verrouiller", pi(ACTION_LOCK_NOW, 3)).build())
                        .addAction(android.app.Notification.Action.Builder(null, "Ignorer", pi(ACTION_CANCEL, 4)).build())
                        .build()
                )
                main.postDelayed(confirmTimeout, 15 * 60 * 1000L)
            }
        }
    }

    private fun finish(title: String?, text: String?) {
        main.removeCallbacks(fire)
        main.removeCallbacks(confirmTimeout)
        phase = Phase.IDLE
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (title != null && text != null) {
            Notifs.result(this, title, text, Notifs.ID_WALK_RESULT, R.drawable.ic_walk)
        }
        try {
            wake?.release()
        } catch (_: Exception) {
        }
        wake = null
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(fire)
        main.removeCallbacks(confirmTimeout)
        running = false
        try {
            wake?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
