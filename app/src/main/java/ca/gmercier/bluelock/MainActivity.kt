package ca.gmercier.bluelock

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Saisie du code de vérification Hyundai, depuis un fil d'arrière-plan. */
class UiMfa(private val act: Activity) : MfaHandler {
    override fun askCode(destination: String): String? {
        CrashLog.step("Demande du code de vérification")
        val latch = CountDownLatch(1)
        var result: String? = null
        act.runOnUiThread {
            if (act.isFinishing || act.isDestroyed) {
                latch.countDown(); return@runOnUiThread
            }
            val input = EditText(act).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = tr("Code à 6 chiffres", "6-digit code")
            }
            val pad = (20 * act.resources.displayMetrics.density).toInt()
            val box = FrameLayout(act).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            AlertDialog.Builder(act)
                .setTitle(tr("Code de vérification", "Verification code"))
                .setMessage(tr("Hyundai a envoyé un code à ", "Hyundai sent a code to ") + destination + ".")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(tr("Valider", "Confirm")) { _, _ -> result = input.text.toString(); latch.countDown() }
                .setNegativeButton(tr("Annuler", "Cancel")) { _, _ -> latch.countDown() }
                .show()
        }
        latch.await(10, TimeUnit.MINUTES)
        return result?.trim()?.ifBlank { null }
    }
}

/** Écran principal, calqué sur Bluelocke. */
class MainActivity : Activity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    private inner class Row(val root: View) {
        val icon: ImageView = root.findViewById(R.id.rowIcon)
        val text: TextView = root.findViewById(R.id.rowText)
        private var spinner: ObjectAnimator? = null

        fun set(iconRes: Int, label: String, tint: Int = colorPrimaryText) {
            stopSpin()
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(tint)
            text.text = label
            text.setTextColor(colorPrimaryText)
        }

        fun updating(label: String) {
            icon.setImageResource(R.drawable.ic_refresh)
            icon.imageTintList = ColorStateList.valueOf(getColor(R.color.orange))
            text.text = label
            text.setTextColor(getColor(R.color.orange))
            spinner = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        fun flash(label: String, ok: Boolean) {
            stopSpin()
            text.text = label
            text.setTextColor(getColor(if (ok) R.color.ok else R.color.warn))
        }

        private fun stopSpin() {
            spinner?.cancel()
            spinner = null
            icon.rotation = 0f
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var colorPrimaryText = 0
    private var busy = false
    private var openedSettingsOnce = false
    private val mfa by lazy { UiMfa(this) }

    private lateinit var title: TextView
    private lateinit var energyIcon: ImageView
    private lateinit var energyText: TextView
    private lateinit var carImage: ImageView
    private lateinit var rowCharge: Row
    private lateinit var rowClimate: Row
    private lateinit var rowSchedule: Row
    private lateinit var rowLock: Row
    private lateinit var rowChargeLimit: Row
    private lateinit var rowStatus: Row
    private lateinit var rowOdometer: Row
    private lateinit var rowTrip: Row
    private lateinit var row12v: Row
    private lateinit var rowLocation: Row
    private lateinit var rowWalk: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Car.init(this)
        if (CrashLog.read(this) != null) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        colorPrimaryText = ta.getColor(0, 0xFF000000.toInt())
        ta.recycle()

        title = findViewById(R.id.title)
        energyIcon = findViewById(R.id.energyIcon)
        energyText = findViewById(R.id.energyText)
        carImage = findViewById(R.id.carImage)
        rowCharge = Row(findViewById(R.id.rowCharge))
        rowClimate = Row(findViewById(R.id.rowClimate))
        rowSchedule = Row(findViewById(R.id.rowSchedule))
        rowLock = Row(findViewById(R.id.rowLock))
        rowChargeLimit = Row(findViewById(R.id.rowChargeLimit))
        rowStatus = Row(findViewById(R.id.rowStatus))
        rowOdometer = Row(findViewById(R.id.rowOdometer))
        rowTrip = Row(findViewById(R.id.rowTrip))
        row12v = Row(findViewById(R.id.row12v))
        rowLocation = Row(findViewById(R.id.rowLocation))
        rowWalk = Row(findViewById(R.id.rowWalk))

        findViewById<View>(R.id.infoBtn).setOnClickListener { showAbout() }
        findViewById<View>(R.id.rowSettings).setOnClickListener { openSettings() }
        rowWalk.root.setOnClickListener { openSettings(SettingsActivity.EXTRA_WALK) }
        rowTrip.root.setOnClickListener { startActivity(Intent(this, TripsActivity::class.java)) }
        rowSchedule.root.setOnClickListener { onScheduleTap() }
        rowLock.root.setOnClickListener { onLockTap() }
        rowClimate.root.setOnClickListener { onClimateTap() }
        rowCharge.root.setOnClickListener { onChargeTap() }
        rowChargeLimit.root.setOnClickListener { onChargeLimitTap() }
        rowStatus.root.setOnClickListener {
            runAction(rowStatus, tr("Interrogation du véhicule…", "Querying the vehicle…"),
                tr("Statut à jour !", "Status updated!"), tr("Échec de la mise à jour", "Update failed")) {
                Car.refresh(true, mfa)
            }
        }
        rowLocation.root.setOnClickListener { onLocationTap() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == "ca.gmercier.bluelock.SHORTCUT_UNLOCK") {
            main.post { onLockTap() }
        }
    }

    override fun onResume() {
        super.onResume()
        CrashLog.step("Écran principal : affichage")
        render()
        CrashLog.step("Écran principal : affiché")
        val cfg = Config.load()
        if (!cfg.hasCredentials()) {
            if (!openedSettingsOnce) {
                openedSettingsOnce = true
                openSettings()
            }
            return
        }
        StatusRefreshJob.schedule(this)
        Scheduler.reschedule(this)
        loadImage()
        val st = Car.status()
        if (st == null || System.currentTimeMillis() - st.lastCheck > 15 * 60 * 1000L) {
            runAction(rowStatus, tr("Mise à jour du statut…", "Updating status…"),
                tr("Statut à jour !", "Status updated!"), tr("Échec de la mise à jour", "Update failed")) {
                val s = Car.refresh(false, mfa)
                try { Alerts.check(applicationContext, s) } catch (_: Exception) {}
            }
        }
    }

    private fun openSettings(extra: String? = null) {
        val i = Intent(this, SettingsActivity::class.java)
        if (extra != null) i.putExtra(extra, true)
        startActivity(i)
    }

    // ------------------------------------------------------------ Rendu

    private fun ago(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> tr("à l'instant", "just now")
            min < 60 -> tr("il y a $min min", "$min min ago")
            min < 24 * 60 -> tr("il y a ${min / 60} h", "${min / 60} h ago")
            else -> Fmt.time(ms)
        }
    }

    private fun render() {
        val cfg = Config.load()
        val st = Car.status()
        title.text = Car.displayName()

        if (st == null) {
            energyIcon.setImageResource(R.drawable.ic_fuel)
            energyText.text = if (cfg.hasCredentials()) tr("Statut inconnu", "Status unknown")
            else tr("Configure ton compte dans les réglages", "Set up your account in Settings")
        } else if (st.supportsCharge && st.soc != null) {
            energyIcon.setImageResource(R.drawable.ic_battery)
            energyText.text = Fmt.energyLine(st)
        } else {
            energyIcon.setImageResource(R.drawable.ic_fuel)
            energyText.text = Fmt.energyLine(st)
        }

        val hasData = st != null
        listOf(rowClimate, rowSchedule, rowLock, rowStatus, rowTrip, rowOdometer, row12v, rowLocation).forEach {
            it.root.visibility = if (hasData) View.VISIBLE else View.GONE
        }
        rowCharge.root.visibility = if (st?.supportsCharge == true) View.VISIBLE else View.GONE
        rowChargeLimit.root.visibility = if (st?.supportsCharge == true) View.VISIBLE else View.GONE

        if (st != null) {
            if (st.supportsCharge) {
                rowCharge.set(
                    R.drawable.ic_bolt,
                    when {
                        st.charging -> tr("En charge · ", "Charging · ") + TripFmt.num1(st.chargePowerKw) + " kW" +
                            if (st.remainMin > 0) tr(" · fin vers ", " · done around ") + Fmt.time(st.lastRemote + st.remainMin * 60_000L) else ""
                        st.plugged -> tr("Branché, pas en charge", "Plugged in, not charging")
                        else -> tr("Pas en charge", "Not charging")
                    },
                    if (st.charging) getColor(R.color.ok) else colorPrimaryText
                )
                rowChargeLimit.set(
                    R.drawable.ic_battery,
                    if (st.chargeAc != null && st.chargeAc > 0) tr("Limite de charge : ", "Charge limit: ") + "AC ${st.chargeAc} % / DC ${st.chargeDc ?: 0} %"
                    else tr("Définir la limite de charge", "Set charge limit")
                )
            }
            rowClimate.set(
                R.drawable.ic_climate,
                if (st.climate) tr("Climatisation en marche", "Climate on") else tr("Climatisation arrêtée", "Climate off"),
                if (st.climate) getColor(R.color.accent) else colorPrimaryText
            )
            val next = Scheduler.nextEffective(cfg)
            rowSchedule.set(
                R.drawable.ic_alarm,
                when {
                    !cfg.schedEnabled -> tr("Démarrage programmé : désactivé", "Scheduled start: off")
                    next == null -> tr("Démarrage programmé : aucun jour choisi", "Scheduled start: no day selected")
                    else -> tr("Prochain démarrage : ", "Next start: ") + Fmt.time(next) +
                        if (cfg.schedMode == "weather") tr(" (selon météo)", " (weather)") else ""
                },
                if (cfg.schedEnabled) getColor(R.color.accent) else colorPrimaryText
            )
            val warnings = mutableListOf<String>()
            if (st.doorsOpen) warnings += tr("portière ouverte", "door open")
            if (st.trunkOpen) warnings += tr("coffre ouvert", "trunk open")
            if (st.hoodOpen) warnings += tr("capot ouvert", "hood open")
            rowLock.set(
                if (st.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                (if (st.locked) tr("Véhicule verrouillé", "Vehicle locked") else tr("Véhicule déverrouillé", "Vehicle unlocked")) +
                    if (warnings.isNotEmpty()) " · " + warnings.joinToString(", ") else "",
                getColor(if (st.locked) R.color.ok else R.color.warn)
            )
            rowStatus.set(R.drawable.ic_refresh, tr("Mis à jour : ", "Updated: ") + Fmt.time(st.lastRemote))
            rowTrip.set(R.drawable.ic_route, TripFmt.summary(cfg.tankL))
            rowOdometer.set(R.drawable.ic_speed, tr("Odomètre : ", "Odometer: ") + Fmt.km(st.odometer))
            row12v.set(
                R.drawable.ic_battery,
                if (st.twelve != null) tr("Batterie 12 V à ${st.twelve} %", "12V battery at ${st.twelve} %")
                else tr("État de la batterie 12 V inconnu", "12V battery status unknown"),
                if (st.twelve != null && st.twelve < cfg.alert12vBelow) getColor(R.color.warn) else colorPrimaryText
            )
            val spot = Parking.last()
            val driving = TripLog.inProgressSince() != null
            rowLocation.set(
                R.drawable.ic_place,
                when {
                    driving -> tr("En route · voir l'emplacement", "On the road · see location")
                    spot != null -> tr("Garée : ", "Parked: ") + (spot.address ?: tr("position enregistrée", "position saved")) + " · " + ago(spot.time)
                    else -> tr("Voir l'emplacement du véhicule", "See vehicle location")
                }
            )
        }
        rowWalk.set(
            R.drawable.ic_walk,
            if (cfg.walkEnabled && cfg.walkDeviceAddress.isNotBlank())
                tr("Verrouillage auto : activé (", "Auto-lock: on (") + cfg.walkDeviceName.ifBlank { "Bluetooth" } + ")"
            else tr("Verrouillage auto : désactivé", "Auto-lock: off"),
            if (cfg.walkEnabled) getColor(R.color.accent) else colorPrimaryText
        )
    }

    private fun loadImage() {
        showImage()
        Car.executor.execute {
            val changed = try { Car.ensureImage() } catch (_: Exception) { false }
            if (changed) main.post { if (!isDestroyed) showImage() }
        }
    }

    private fun showImage() {
        CrashLog.step("Écran principal : image")
        val f = Car.carImageFile()
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        if (bmp != null) {
            carImage.imageTintList = null
            carImage.setImageBitmap(bmp)
        } else {
            carImage.setImageResource(R.drawable.ic_car)
            carImage.imageTintList = ColorStateList.valueOf(getColor(R.color.muted))
        }
    }

    // ------------------------------------------------------------ Actions

    private fun runAction(row: Row, updatingText: String, okText: String, failText: String, block: () -> Unit) {
        if (busy) {
            Toast.makeText(this, tr("Une commande est déjà en cours…", "A command is already running…"), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        row.updating(updatingText)
        Car.executor.execute {
            val err = try {
                block(); null
            } catch (e: Exception) {
                e
            }
            main.post {
                busy = false
                if (isDestroyed) return@post
                render()
                row.flash(if (err == null) okText else failText, err == null)
                main.postDelayed({ if (!isDestroyed && !busy) render() }, 2500)
                if (err != null) {
                    AlertDialog.Builder(this)
                        .setTitle(failText)
                        .setMessage(err.message ?: err.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun choose(title: String, options: Array<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which -> onPick(which) }
            .setNegativeButton(tr("Annuler", "Cancel"), null)
            .show()
    }

    private fun onLockTap() {
        if (busy) return
        choose(tr("Confirmer l'action", "Confirm action"), arrayOf(tr("Verrouiller", "Lock"), tr("Déverrouiller", "Unlock"))) { which ->
            if (which == 0) {
                runAction(rowLock, tr("Verrouillage…", "Locking…"), tr("Véhicule verrouillé !", "Vehicle locked!"),
                    tr("Échec du verrouillage", "Lock failed")) { Car.lock(mfa) }
            } else {
                runAction(rowLock, tr("Déverrouillage…", "Unlocking…"), tr("Véhicule déverrouillé !", "Vehicle unlocked!"),
                    tr("Échec du déverrouillage", "Unlock failed")) { Car.unlock(mfa) }
            }
        }
    }

    private fun onClimateTap() {
        if (busy) return
        val cfg = Config.load()
        choose(
            tr("Climatisation", "Climate"),
            arrayOf(
                tr("Chauffer", "Warm up") + " (${fmtTemp(cfg.warmTemp)})",
                tr("Refroidir", "Cool down") + " (${fmtTemp(cfg.coolTemp)})",
                tr("Arrêter", "Stop")
            )
        ) { which ->
            when (which) {
                0 -> runAction(rowClimate, tr("Démarrage du chauffage…", "Starting heat…"), tr("Chauffage en marche !", "Heating on!"),
                    tr("Échec du démarrage", "Start failed")) { Car.climateOn(ClimateRequest.warm(Config.load()), mfa) }
                1 -> runAction(rowClimate, tr("Démarrage de la climatisation…", "Starting A/C…"), tr("Climatisation en marche !", "A/C on!"),
                    tr("Échec du démarrage", "Start failed")) { Car.climateOn(ClimateRequest.cool(Config.load()), mfa) }
                else -> runAction(rowClimate, tr("Arrêt de la climatisation…", "Stopping climate…"), tr("Climatisation arrêtée !", "Climate stopped!"),
                    tr("Échec de l'arrêt", "Stop failed")) { Car.climateOff(mfa) }
            }
        }
    }

    private fun onScheduleTap() {
        val cfg = Config.load()
        val skipped = Scheduler.skipped()?.takeIf { it > System.currentTimeMillis() }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (cfg.schedEnabled) {
            if (skipped == null) {
                options += tr("Sauter le prochain démarrage", "Skip the next start") to {
                    Scheduler.skipNext(this); render()
                    Toast.makeText(this, tr("Prochain démarrage sauté", "Next start skipped"), Toast.LENGTH_SHORT).show()
                }
            } else {
                options += tr("Rétablir le démarrage sauté (", "Restore skipped start (") + Fmt.time(skipped) + ")" to {
                    Scheduler.unskip(this); render()
                }
            }
        }
        options += tr("Réglages du démarrage programmé", "Scheduled start settings") to { openSettings(SettingsActivity.EXTRA_SCHED) }
        choose(
            if (cfg.schedEnabled) tr("Démarrage programmé · ", "Scheduled start · ") + Scheduler.describe(cfg) else tr("Démarrage programmé", "Scheduled start"),
            options.map { it.first }.toTypedArray()
        ) { which -> options[which].second() }
    }

    private fun onChargeTap() {
        if (busy) return
        choose(tr("Recharge", "Charging"), arrayOf(tr("Démarrer la recharge", "Start charging"), tr("Arrêter la recharge", "Stop charging"))) { which ->
            if (which == 0) {
                runAction(rowCharge, tr("Démarrage de la recharge…", "Starting charge…"), tr("Recharge démarrée !", "Charging started!"),
                    tr("Échec", "Failed")) { Car.startCharge(mfa) }
            } else {
                runAction(rowCharge, tr("Arrêt de la recharge…", "Stopping charge…"), tr("Recharge arrêtée !", "Charging stopped!"),
                    tr("Échec", "Failed")) { Car.stopCharge(mfa) }
            }
        }
    }

    private val chargeLimits = listOf(80 to 80, 90 to 80, 100 to 90, 100 to 100)

    private fun onChargeLimitTap() {
        if (busy) return
        choose(tr("Limite de charge (AC / DC)", "Charge limit (AC / DC)"), chargeLimits.map { "${it.first} % / ${it.second} %" }.toTypedArray()) { which ->
            val (ac, dc) = chargeLimits[which]
            runAction(rowChargeLimit, tr("Réglage de la limite…", "Setting limit…"), tr("Limite réglée !", "Limit set!"),
                tr("Échec", "Failed")) { Car.setChargeLimit(ac, dc, mfa) }
        }
    }

    private fun onLocationTap() {
        if (busy) return
        val st = Car.status()
        val spot = Parking.last()
        val lastKnown = if (st?.lat != null && st.lon != null) st.lat to st.lon else null
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (spot != null) {
            options += tr("Où je me suis garé (", "Where I parked (") + ago(spot.time) + ")" to { openMap(spot.lat, spot.lon) }
            options += tr("Itinéraire à pied jusqu'à l'auto", "Walking directions to the car") to { walkTo(spot.lat, spot.lon) }
        }
        options += tr("Localiser maintenant (via l'auto)", "Locate now (via the car)") to {
            runAction(rowLocation, tr("Localisation du véhicule…", "Locating the vehicle…"), tr("Véhicule localisé !", "Vehicle located!"),
                tr("Échec de la localisation", "Location failed")) {
                val (lat, lon) = Car.locate(mfa)
                main.post { openMap(lat, lon) }
            }
        }
        if (lastKnown != null) {
            options += tr("Dernière position donnée par l'auto", "Last position reported by the car") to { openMap(lastKnown.first, lastKnown.second) }
        }
        choose(tr("Emplacement", "Location"), options.map { it.first }.toTypedArray()) { which -> options[which].second() }
    }

    private fun openMap(lat: Double, lon: Double) {
        val label = Uri.encode(Car.displayName())
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon")))
        }
    }

    private fun walkTo(lat: Double, lon: Double) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w")))
        } catch (e: Exception) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=walking"))
            )
        }
    }

    private fun showAbout() {
        val info = Car.info()
        val msg = buildString {
            if (info != null) {
                append("${info.modelYear} ${info.modelName} ${info.trim}".trim()).append('\n')
                if (info.color.isNotBlank()) append(tr("Couleur : ", "Colour: ") + info.color + "\n")
                if (info.vin.isNotBlank()) append(tr("NIV : ", "VIN: ") + info.vin + "\n")
                append('\n')
            }
            append(
                tr(
                    "Tes identifiants, ton NIP, tes jetons et les données du véhicule sont chiffrés sur ce téléphone (Android Keystore) et ne sont jamais sauvegardés dans le nuage.\n\n",
                    "Your credentials, PIN, tokens and vehicle data are encrypted on this phone (Android Keystore) and never backed up to the cloud.\n\n"
                )
            )
            append(
                tr(
                    "L'app ne parle qu'à Hyundai (mybluelink.ca). L'image du véhicule vient de GitHub et la météo d'Open-Meteo (position arrondie), sans aucune donnée personnelle.\n\n",
                    "The app only talks to Hyundai (mybluelink.ca). The vehicle image comes from GitHub and weather from Open-Meteo (rounded location), with no personal data.\n\n"
                )
            )
            append(tr("Inspiré de Bluelocke (licence MIT). Projet personnel non affilié à Hyundai.", "Inspired by Bluelocke (MIT licence). Personal project, not affiliated with Hyundai."))
        }
        AlertDialog.Builder(this)
            .setTitle("BlueLock ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}
