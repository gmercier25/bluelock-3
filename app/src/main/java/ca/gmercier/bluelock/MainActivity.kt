package ca.gmercier.bluelock

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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
                hint = "Code à 6 chiffres"
            }
            val pad = (20 * act.resources.displayMetrics.density).toInt()
            val box = FrameLayout(act).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
            AlertDialog.Builder(act)
                .setTitle("Code de vérification")
                .setMessage("Hyundai a envoyé un code à $destination.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Valider") { _, _ -> result = input.text.toString(); latch.countDown() }
                .setNegativeButton("Annuler") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await(10, TimeUnit.MINUTES)
        return result?.trim()?.ifBlank { null }
    }
}

/** Écran principal, calqué sur Bluelocke. */
class MainActivity : Activity() {

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
    private lateinit var rowLock: Row
    private lateinit var rowChargeLimit: Row
    private lateinit var rowStatus: Row
    private lateinit var rowOdometer: Row
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
        rowLock = Row(findViewById(R.id.rowLock))
        rowChargeLimit = Row(findViewById(R.id.rowChargeLimit))
        rowStatus = Row(findViewById(R.id.rowStatus))
        rowOdometer = Row(findViewById(R.id.rowOdometer))
        row12v = Row(findViewById(R.id.row12v))
        rowLocation = Row(findViewById(R.id.rowLocation))
        rowWalk = Row(findViewById(R.id.rowWalk))

        findViewById<View>(R.id.infoBtn).setOnClickListener { showAbout() }
        findViewById<View>(R.id.rowSettings).setOnClickListener { openSettings(false) }
        rowWalk.root.setOnClickListener { openSettings(true) }
        rowLock.root.setOnClickListener { onLockTap() }
        rowClimate.root.setOnClickListener { onClimateTap() }
        rowCharge.root.setOnClickListener { onChargeTap() }
        rowChargeLimit.root.setOnClickListener { onChargeLimitTap() }
        rowStatus.root.setOnClickListener {
            runAction(rowStatus, "Interrogation du véhicule…", "Statut à jour !", "Échec de la mise à jour") {
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
                openSettings(false)
            }
            return
        }
        StatusRefreshJob.schedule(this)
        loadImage()
        val st = Car.status()
        if (st == null || System.currentTimeMillis() - st.lastCheck > 15 * 60 * 1000L) {
            runAction(rowStatus, "Mise à jour du statut…", "Statut à jour !", "Échec de la mise à jour") {
                Car.refresh(false, mfa)
            }
        }
    }

    private fun openSettings(walk: Boolean) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_WALK, walk))
    }

    // ------------------------------------------------------------ Rendu

    private fun render() {
        val cfg = Config.load()
        val st = Car.status()
        title.text = Car.displayName()

        if (st == null) {
            energyIcon.setImageResource(R.drawable.ic_fuel)
            energyText.text = if (cfg.hasCredentials()) "Statut inconnu" else "Configure ton compte dans les réglages"
        } else if (st.supportsCharge && st.soc != null) {
            energyIcon.setImageResource(R.drawable.ic_battery)
            energyText.text = Fmt.energyLine(st)
        } else {
            energyIcon.setImageResource(R.drawable.ic_fuel)
            energyText.text = Fmt.energyLine(st)
        }

        val hasData = st != null
        listOf(rowClimate, rowLock, rowStatus, rowOdometer, row12v, rowLocation).forEach {
            it.root.visibility = if (hasData) View.VISIBLE else View.GONE
        }
        rowCharge.root.visibility = if (st?.supportsCharge == true) View.VISIBLE else View.GONE
        rowChargeLimit.root.visibility = if (st?.supportsCharge == true) View.VISIBLE else View.GONE

        if (st != null) {
            if (st.supportsCharge) {
                rowCharge.set(
                    R.drawable.ic_bolt,
                    when {
                        st.charging -> "En charge · ${"%.1f".format(st.chargePowerKw).replace('.', ',')} kW" +
                            if (st.remainMin > 0) " · fin vers ${Fmt.time(st.lastRemote + st.remainMin * 60_000L)}" else ""
                        st.plugged -> "Branché, pas en charge"
                        else -> "Pas en charge"
                    },
                    if (st.charging) getColor(R.color.ok) else colorPrimaryText
                )
                rowChargeLimit.set(
                    R.drawable.ic_battery,
                    if (st.chargeAc != null && st.chargeAc > 0) "Limite de charge : AC ${st.chargeAc} % / DC ${st.chargeDc ?: 0} %"
                    else "Définir la limite de charge"
                )
            }
            rowClimate.set(
                R.drawable.ic_climate,
                if (st.climate) "Climatisation en marche" else "Climatisation arrêtée",
                if (st.climate) getColor(R.color.accent) else colorPrimaryText
            )
            val warnings = mutableListOf<String>()
            if (st.doorsOpen) warnings += "portière ouverte"
            if (st.trunkOpen) warnings += "coffre ouvert"
            if (st.hoodOpen) warnings += "capot ouvert"
            rowLock.set(
                if (st.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                (if (st.locked) "Véhicule verrouillé" else "Véhicule déverrouillé") +
                    if (warnings.isNotEmpty()) " · " + warnings.joinToString(", ") else "",
                getColor(if (st.locked) R.color.ok else R.color.warn)
            )
            rowStatus.set(R.drawable.ic_refresh, "Mis à jour : ${Fmt.time(st.lastRemote)}")
            rowOdometer.set(R.drawable.ic_speed, "Odomètre : ${Fmt.km(st.odometer)}")
            row12v.set(
                R.drawable.ic_battery,
                if (st.twelve != null) "Batterie 12 V à ${st.twelve} %" else "État de la batterie 12 V inconnu",
                if (st.twelve != null && st.twelve < 50) getColor(R.color.warn) else colorPrimaryText
            )
            rowLocation.set(R.drawable.ic_place, "Voir l'emplacement du véhicule")
        }
        rowWalk.set(
            R.drawable.ic_walk,
            if (cfg.walkEnabled && cfg.walkDeviceAddress.isNotBlank())
                "Verrouillage auto : activé (${cfg.walkDeviceName.ifBlank { "Bluetooth" }})"
            else "Verrouillage auto : désactivé",
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
            Toast.makeText(this, "Une commande est déjà en cours…", Toast.LENGTH_SHORT).show()
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
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun onLockTap() {
        if (busy) return
        choose("Confirmer l'action", arrayOf("Verrouiller", "Déverrouiller")) { which ->
            if (which == 0) {
                runAction(rowLock, "Verrouillage…", "Véhicule verrouillé !", "Échec du verrouillage") { Car.lock(mfa) }
            } else {
                runAction(rowLock, "Déverrouillage…", "Véhicule déverrouillé !", "Échec du déverrouillage") { Car.unlock(mfa) }
            }
        }
    }

    private fun onClimateTap() {
        if (busy) return
        val cfg = Config.load()
        choose(
            "Climatisation",
            arrayOf("Chauffer (${fmtTemp(cfg.warmTemp)})", "Refroidir (${fmtTemp(cfg.coolTemp)})", "Arrêter")
        ) { which ->
            when (which) {
                0 -> runAction(rowClimate, "Démarrage du chauffage…", "Chauffage en marche !", "Échec du démarrage") {
                    Car.climateOn(ClimateRequest.warm(Config.load()), mfa)
                }
                1 -> runAction(rowClimate, "Démarrage de la climatisation…", "Climatisation en marche !", "Échec du démarrage") {
                    Car.climateOn(ClimateRequest.cool(Config.load()), mfa)
                }
                else -> runAction(rowClimate, "Arrêt de la climatisation…", "Climatisation arrêtée !", "Échec de l'arrêt") {
                    Car.climateOff(mfa)
                }
            }
        }
    }

    private fun onChargeTap() {
        if (busy) return
        choose("Recharge", arrayOf("Démarrer la recharge", "Arrêter la recharge")) { which ->
            if (which == 0) {
                runAction(rowCharge, "Démarrage de la recharge…", "Recharge démarrée !", "Échec") { Car.startCharge(mfa) }
            } else {
                runAction(rowCharge, "Arrêt de la recharge…", "Recharge arrêtée !", "Échec") { Car.stopCharge(mfa) }
            }
        }
    }

    private val chargeLimits = listOf(80 to 80, 90 to 80, 100 to 90, 100 to 100)

    private fun onChargeLimitTap() {
        if (busy) return
        choose("Limite de charge (AC / DC)", chargeLimits.map { "${it.first} % / ${it.second} %" }.toTypedArray()) { which ->
            val (ac, dc) = chargeLimits[which]
            runAction(rowChargeLimit, "Réglage de la limite…", "Limite réglée !", "Échec") { Car.setChargeLimit(ac, dc, mfa) }
        }
    }

    private fun onLocationTap() {
        if (busy) return
        val st = Car.status()
        val lastKnown = if (st?.lat != null && st.lon != null) st.lat to st.lon else null
        val options = mutableListOf("Localiser maintenant")
        if (lastKnown != null) options += "Dernière position connue"
        choose("Emplacement", options.toTypedArray()) { which ->
            if (which == 1 && lastKnown != null) {
                openMap(lastKnown.first, lastKnown.second)
            } else {
                runAction(rowLocation, "Localisation du véhicule…", "Véhicule localisé !", "Échec de la localisation") {
                    val (lat, lon) = Car.locate(mfa)
                    main.post { openMap(lat, lon) }
                }
            }
        }
    }

    private fun openMap(lat: Double, lon: Double) {
        val label = Uri.encode(Car.displayName())
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon")))
        }
    }

    private fun showAbout() {
        val info = Car.info()
        val msg = buildString {
            if (info != null) {
                append("${info.modelYear} ${info.modelName} ${info.trim}".trim()).append('\n')
                if (info.color.isNotBlank()) append("Couleur : ${info.color}\n")
                if (info.vin.isNotBlank()) append("NIV : ${info.vin}\n")
                append('\n')
            }
            append("Tes identifiants, ton NIP, tes jetons et les données du véhicule sont chiffrés sur ce téléphone (Android Keystore) ")
            append("et ne sont jamais sauvegardés dans le nuage.\n\n")
            append("L'app ne parle qu'à Hyundai (mybluelink.ca). L'image du véhicule est téléchargée depuis GitHub, sans aucune donnée personnelle.\n\n")
            append("Inspiré de Bluelocke (licence MIT). Projet personnel non affilié à Hyundai.")
        }
        AlertDialog.Builder(this)
            .setTitle("BlueLock ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}
