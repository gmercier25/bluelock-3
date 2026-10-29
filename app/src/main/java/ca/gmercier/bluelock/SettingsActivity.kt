package ca.gmercier.bluelock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_WALK = "walk"
        const val EXTRA_SCHED = "sched"
        private const val REQ_BT = 10
        private const val REQ_NOTIF = 11
        private const val REQ_LOC = 12
        private const val REQ_LOC_BG = 13
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())

    private val langs = listOf("auto", "fr", "en")
    private val temps = (0..20).map { 17.0 + it * 0.5 }
    private val seatLevels get() = listOf(tr("Arrêt", "Off"), tr("Bas", "Low"), tr("Moyen", "Medium"), tr("Élevé", "High"))
    private val durations = listOf(5, 10, 15, 20, 25, 30)
    private val delays = listOf(0, 15, 30, 45, 60, 90, 120)
    private val schedModes = listOf("weather", "warm", "cool")
    private val warmBelow = (-20..15).toList()
    private val coolAbove = (18..35).toList()
    private val unlockedMins = listOf(15, 30, 60, 120)
    private val twelveLevels = listOf(40, 50, 60, 70)

    private lateinit var spLang: Spinner
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPin: EditText
    private lateinit var etName: EditText
    private lateinit var etTank: EditText
    private lateinit var rbSms: RadioButton
    private lateinit var rbEmail: RadioButton
    private lateinit var spWarmTemp: Spinner
    private lateinit var spCoolTemp: Spinner
    private lateinit var cbWarmFront: CheckBox
    private lateinit var cbWarmRear: CheckBox
    private lateinit var cbWarmSteering: CheckBox
    private lateinit var spWarmSeat: Spinner
    private lateinit var spCoolSeat: Spinner
    private lateinit var cbSeatsAll: CheckBox
    private lateinit var spDuration: Spinner
    private lateinit var swSched: Switch
    private lateinit var btnSchedTime: Button
    private lateinit var cbDays: List<CheckBox>
    private lateinit var spSchedMode: Spinner
    private lateinit var spWarmBelow: Spinner
    private lateinit var spCoolAbove: Spinner
    private lateinit var swPark: Switch
    private lateinit var swAlertUnlocked: Switch
    private lateinit var spUnlockedMin: Spinner
    private lateinit var swAlertOpen: Switch
    private lateinit var swAlert12v: Switch
    private lateinit var sp12v: Spinner
    private lateinit var spTrim: Spinner
    private lateinit var spColor: Spinner
    private lateinit var swWalk: Switch
    private lateinit var tvWalkDevice: TextView
    private lateinit var spWalkDelay: Spinner
    private lateinit var cbWalkConfirm: CheckBox
    private lateinit var cbWalkNotify: CheckBox
    private lateinit var tvBattery: TextView

    private var deviceAddress = ""
    private var deviceName = ""
    private var pendingDevicePick = false
    private var schedHour = 7
    private var schedMinute = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Car.init(this)
        setContentView(R.layout.activity_settings)

        spLang = findViewById(R.id.spLang)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etPin = findViewById(R.id.etPin)
        etName = findViewById(R.id.etName)
        etTank = findViewById(R.id.etTank)
        rbSms = findViewById(R.id.rbSms)
        rbEmail = findViewById(R.id.rbEmail)
        spWarmTemp = findViewById(R.id.spWarmTemp)
        spCoolTemp = findViewById(R.id.spCoolTemp)
        cbWarmFront = findViewById(R.id.cbWarmFront)
        cbWarmRear = findViewById(R.id.cbWarmRear)
        cbWarmSteering = findViewById(R.id.cbWarmSteering)
        spWarmSeat = findViewById(R.id.spWarmSeat)
        spCoolSeat = findViewById(R.id.spCoolSeat)
        cbSeatsAll = findViewById(R.id.cbSeatsAll)
        spDuration = findViewById(R.id.spDuration)
        swSched = findViewById(R.id.swSched)
        btnSchedTime = findViewById(R.id.btnSchedTime)
        cbDays = listOf(R.id.cbD0, R.id.cbD1, R.id.cbD2, R.id.cbD3, R.id.cbD4, R.id.cbD5, R.id.cbD6).map { findViewById(it) }
        spSchedMode = findViewById(R.id.spSchedMode)
        spWarmBelow = findViewById(R.id.spWarmBelow)
        spCoolAbove = findViewById(R.id.spCoolAbove)
        swPark = findViewById(R.id.swPark)
        swAlertUnlocked = findViewById(R.id.swAlertUnlocked)
        spUnlockedMin = findViewById(R.id.spUnlockedMin)
        swAlertOpen = findViewById(R.id.swAlertOpen)
        swAlert12v = findViewById(R.id.swAlert12v)
        sp12v = findViewById(R.id.sp12v)
        spTrim = findViewById(R.id.spTrim)
        spColor = findViewById(R.id.spColor)
        swWalk = findViewById(R.id.swWalk)
        tvWalkDevice = findViewById(R.id.tvWalkDevice)
        spWalkDelay = findViewById(R.id.spWalkDelay)
        cbWalkConfirm = findViewById(R.id.cbWalkConfirm)
        cbWalkNotify = findViewById(R.id.cbWalkNotify)
        tvBattery = findViewById(R.id.tvBattery)

        spLang.adapter = adapter(listOf(tr("Automatique (langue du téléphone)", "Automatic (phone language)"), "Français", "English"))
        spWarmTemp.adapter = adapter(temps.map { fmtTemp(it) })
        spCoolTemp.adapter = adapter(temps.map { fmtTemp(it) })
        spWarmSeat.adapter = adapter(seatLevels)
        spCoolSeat.adapter = adapter(seatLevels)
        spDuration.adapter = adapter(durations.map { "$it min" })
        spSchedMode.adapter = adapter(
            listOf(
                tr("Selon la météo (recommandé)", "Based on the weather (recommended)"),
                tr("Toujours chauffer", "Always warm up"),
                tr("Toujours refroidir", "Always cool down"),
            )
        )
        spWarmBelow.adapter = adapter(warmBelow.map { "$it °C" })
        spCoolAbove.adapter = adapter(coolAbove.map { "$it °C" })
        spUnlockedMin.adapter = adapter(unlockedMins.map { if (it >= 60) "${it / 60} h" else "$it min" })
        sp12v.adapter = adapter(twelveLevels.map { "$it %" })
        spTrim.adapter = adapter(CarImages.TRIMS.values.toList())
        spColor.adapter = adapter(CarImages.COLORS.values.toList())
        spWalkDelay.adapter = adapter(delays.map { if (it == 0) tr("Immédiat", "Immediate") else "$it s" })
        val dayNames = if (Lang.isEn) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        else listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
        cbDays.forEachIndexed { i, cb -> cb.text = dayNames[i] }

        fill(Config.load())

        btnSchedTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                schedHour = h; schedMinute = m; showTime()
            }, schedHour, schedMinute, !Lang.isEn).show()
        }
        swPark.setOnCheckedChangeListener { _, on -> if (on) askLocation() }
        findViewById<Button>(R.id.btnWalkDevice).setOnClickListener { pickDevice() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.btnWalkTest).setOnClickListener { testWalkAway() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        swWalk.setOnCheckedChangeListener { _, on -> if (on) ensureWalkPermissions() }

        val scrollTo = when {
            intent.getBooleanExtra(EXTRA_WALK, false) -> R.id.walkSection
            intent.getBooleanExtra(EXTRA_SCHED, false) -> R.id.schedSection
            else -> 0
        }
        if (scrollTo != 0) {
            val scroll = findViewById<ScrollView>(R.id.scroll)
            val section = findViewById<View>(scrollTo)
            scroll.post { scroll.smoothScrollTo(0, section.top) }
        }
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        tvBattery.text = if (exempt) tr("✓ Optimisation de batterie désactivée pour BlueLock", "✓ Battery optimization is off for BlueLock")
        else tr(
            "⚠ Recommandé : désactive l'optimisation de batterie pour que le verrouillage auto, les alertes et le démarrage programmé soient fiables.",
            "⚠ Recommended: turn off battery optimization so auto-lock, alerts and scheduled start are reliable."
        )
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun showTime() {
        btnSchedTime.text = Scheduler.timeText(schedHour, schedMinute)
    }

    private fun fill(c: Config) {
        spLang.setSelection(langs.indexOf(c.lang).coerceAtLeast(0))
        etEmail.setText(c.username)
        etPassword.setText(c.password)
        etPin.setText(c.pin)
        etName.setText(c.displayName)
        etTank.setText(TripFmt.num1(c.tankL).replace(",0", "").replace(".0", ""))
        rbSms.isChecked = c.mfaSms
        rbEmail.isChecked = !c.mfaSms
        spWarmTemp.setSelection(temps.indexOfFirst { it == c.warmTemp }.coerceAtLeast(0))
        spCoolTemp.setSelection(temps.indexOfFirst { it == c.coolTemp }.coerceAtLeast(0))
        cbWarmFront.isChecked = c.warmFrontDefrost
        cbWarmRear.isChecked = c.warmRearDefrost
        cbWarmSteering.isChecked = c.warmSteering
        spWarmSeat.setSelection(c.warmSeat.coerceIn(0, 3))
        spCoolSeat.setSelection(c.coolSeat.coerceIn(0, 3))
        cbSeatsAll.isChecked = c.seatsAll
        spDuration.setSelection(durations.indexOf(c.durationMin).coerceAtLeast(0))
        swSched.isChecked = c.schedEnabled
        schedHour = c.schedHour
        schedMinute = c.schedMinute
        showTime()
        cbDays.forEachIndexed { i, cb -> cb.isChecked = c.schedDays and (1 shl i) != 0 }
        spSchedMode.setSelection(schedModes.indexOf(c.schedMode).coerceAtLeast(0))
        spWarmBelow.setSelection(warmBelow.indexOf(c.schedWarmBelow).coerceAtLeast(0))
        spCoolAbove.setSelection(coolAbove.indexOf(c.schedCoolAbove).coerceAtLeast(0))
        swPark.isChecked = c.parkEnabled
        swAlertUnlocked.isChecked = c.alertUnlocked
        spUnlockedMin.setSelection(unlockedMins.indexOf(c.alertUnlockedMin).coerceAtLeast(0))
        swAlertOpen.isChecked = c.alertOpen
        swAlert12v.isChecked = c.alert12v
        sp12v.setSelection(twelveLevels.indexOf(c.alert12vBelow).coerceAtLeast(0))
        spTrim.setSelection(CarImages.TRIMS.keys.indexOf(c.imageTrim).coerceAtLeast(0))
        spColor.setSelection(CarImages.COLORS.keys.indexOf(c.imageColor).coerceAtLeast(0))
        swWalk.isChecked = c.walkEnabled
        deviceAddress = c.walkDeviceAddress
        deviceName = c.walkDeviceName
        showDevice()
        spWalkDelay.setSelection(delays.indexOf(c.walkDelaySec).let { if (it < 0) 2 else it })
        cbWalkConfirm.isChecked = c.walkConfirm
        cbWalkNotify.isChecked = c.walkNotifyAlreadyLocked
    }

    private fun showDevice() {
        tvWalkDevice.text = if (deviceAddress.isBlank()) tr("Aucun appareil choisi", "No device selected")
        else tr("Bluetooth du véhicule : ", "Vehicle Bluetooth: ") + deviceName.ifBlank { deviceAddress }
    }

    private fun pos(sp: Spinner) = sp.selectedItemPosition.coerceAtLeast(0)

    private fun collect(): Config = Config.load().copy(
        lang = langs[pos(spLang)],
        username = etEmail.text.toString().trim(),
        password = etPassword.text.toString(),
        pin = etPin.text.toString().trim(),
        displayName = etName.text.toString().trim(),
        tankL = etTank.text.toString().replace(',', '.').trim().toDoubleOrNull()?.takeIf { it in 20.0..150.0 } ?: 52.0,
        mfaSms = rbSms.isChecked,
        warmTemp = temps[pos(spWarmTemp)],
        coolTemp = temps[pos(spCoolTemp)],
        warmFrontDefrost = cbWarmFront.isChecked,
        warmRearDefrost = cbWarmRear.isChecked,
        warmSteering = cbWarmSteering.isChecked,
        warmSeat = pos(spWarmSeat).coerceIn(0, 3),
        coolSeat = pos(spCoolSeat).coerceIn(0, 3),
        seatsAll = cbSeatsAll.isChecked,
        durationMin = durations[pos(spDuration)],
        schedEnabled = swSched.isChecked,
        schedHour = schedHour,
        schedMinute = schedMinute,
        schedDays = cbDays.foldIndexed(0) { i, acc, cb -> if (cb.isChecked) acc or (1 shl i) else acc },
        schedMode = schedModes[pos(spSchedMode)],
        schedWarmBelow = warmBelow[pos(spWarmBelow)],
        schedCoolAbove = coolAbove[pos(spCoolAbove)],
        parkEnabled = swPark.isChecked,
        alertUnlocked = swAlertUnlocked.isChecked,
        alertUnlockedMin = unlockedMins[pos(spUnlockedMin)],
        alertOpen = swAlertOpen.isChecked,
        alert12v = swAlert12v.isChecked,
        alert12vBelow = twelveLevels[pos(sp12v)],
        imageTrim = CarImages.TRIMS.keys.elementAt(pos(spTrim)),
        imageColor = CarImages.COLORS.keys.elementAt(pos(spColor)),
        walkEnabled = swWalk.isChecked,
        walkDeviceAddress = deviceAddress,
        walkDeviceName = deviceName,
        walkDelaySec = delays[pos(spWalkDelay)],
        walkConfirm = cbWalkConfirm.isChecked,
        walkNotifyAlreadyLocked = cbWalkNotify.isChecked,
    )

    // ------------------------------------------------------------ Enregistrer

    private fun toast(fr: String, en: String) = Toast.makeText(this, tr(fr, en), Toast.LENGTH_LONG).show()

    private fun save() {
        val old = Config.load()
        val cfg = collect()
        if (!cfg.hasCredentials()) {
            toast("Courriel, mot de passe et NIP sont requis.", "Email, password and PIN are required.")
            return
        }
        if (cfg.pin.length != 4 || !cfg.pin.all { it.isDigit() }) {
            toast("Le NIP Bluelink compte 4 chiffres.", "The Bluelink PIN has 4 digits.")
            return
        }
        if (cfg.walkEnabled && cfg.walkDeviceAddress.isBlank()) {
            toast("Choisis le Bluetooth de ton véhicule pour le verrouillage auto.", "Choose your vehicle's Bluetooth for auto-lock.")
            return
        }
        if (cfg.parkEnabled && cfg.walkDeviceAddress.isBlank()) {
            toast(
                "Choisis le Bluetooth de ton véhicule (section Verrouillage auto) pour mémoriser où tu te gares.",
                "Choose your vehicle's Bluetooth (Auto-lock section) to remember where you park."
            )
            return
        }
        if (cfg.schedEnabled && cfg.schedDays and 0x7F == 0) {
            toast("Choisis au moins un jour pour le démarrage programmé.", "Choose at least one day for the scheduled start.")
            return
        }
        cfg.save()
        Scheduler.reschedule(this)
        val langChanged = old.lang != cfg.lang
        if (langChanged) Notifs.ensureChannels(this)
        val imageChanged = old.imageTrim != cfg.imageTrim || old.imageColor != cfg.imageColor
        val needLogin = old.username != cfg.username || old.password != cfg.password || Car.info() == null

        if (!needLogin) {
            if (imageChanged) Car.executor.execute { try { Car.ensureImage(force = true) } catch (_: Exception) {} }
            Widgets.updateAll(this)
            Toast.makeText(this, tr("Réglages enregistrés", "Settings saved"), Toast.LENGTH_SHORT).show()
            done(langChanged)
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle(tr("Connexion à MyHyundai…", "Signing in to MyHyundai…"))
            .setView(ProgressBar(this).apply { isIndeterminate = true; setPadding(0, 40, 0, 40) })
            .setCancelable(false)
            .show()
        val mfa = UiMfa(this)
        Car.executor.execute {
            val err = try {
                CrashLog.step("Réglages : connexion")
                val info = Car.login(mfa)
                CrashLog.step("Réglages : connecté")
                try { Car.refresh(false, mfa) } catch (_: Exception) {}
                try { Car.ensureImage(force = true) } catch (_: Exception) {}
                main.post { Toast.makeText(this, tr("Connecté : ", "Signed in: ") + info.displayName, Toast.LENGTH_LONG).show() }
                null
            } catch (e: Exception) {
                e
            }
            CrashLog.step("Réglages : fin (${err?.javaClass?.simpleName ?: "ok"})")
            main.post {
                if (isDestroyed) return@post
                progress.dismiss()
                if (err == null) {
                    StatusRefreshJob.schedule(this)
                    Widgets.updateAll(this)
                    done(langChanged)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(tr("Connexion impossible", "Sign-in failed"))
                        .setMessage(err.message ?: err.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /** Ferme les réglages ; si la langue a changé, relance l'écran principal dans la nouvelle langue. */
    private fun done(langChanged: Boolean) {
        if (langChanged) {
            Widgets.updateAll(this)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(tr("Tout effacer ?", "Erase everything?"))
            .setMessage(
                tr(
                    "Supprime tes identifiants, jetons, réglages, trajets et données du véhicule de ce téléphone.",
                    "Deletes your credentials, tokens, settings, trips and vehicle data from this phone."
                )
            )
            .setPositiveButton(tr("Effacer", "Erase")) { _, _ ->
                val app = applicationContext
                Car.executor.execute { Car.reset(); Scheduler.reschedule(app) }
                Toast.makeText(this, tr("Données effacées", "Data erased"), Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(tr("Annuler", "Cancel"), null)
            .show()
    }

    // ------------------------------------------------------------ Localisation (stationnement)

    private fun askLocation() {
        if (!Parking.hasForeground(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
        } else if (!Parking.hasBackground(this)) {
            askBackgroundLocation()
        }
    }

    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        AlertDialog.Builder(this)
            .setTitle(tr("Localisation « Toujours »", "Location “All the time”"))
            .setMessage(
                tr(
                    "Pour enregistrer où tu te gares même quand l'app est fermée, Android doit permettre la localisation « Toujours ». " +
                        "À l'écran suivant, choisis « Toujours autoriser ».",
                    "To save where you park even when the app is closed, Android must allow location “All the time”. " +
                        "On the next screen, choose “Allow all the time”."
                )
            )
            .setPositiveButton(tr("Continuer", "Continue")) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOC_BG)
            }
            .setNegativeButton(tr("Annuler", "Cancel"), null)
            .show()
    }

    // ------------------------------------------------------------ Verrouillage auto

    private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensureWalkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_NOTIF)
    }

    private fun pickDevice() {
        if (!hasBtPermission()) {
            pendingDevicePick = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        showDevicePicker()
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("Bluetooth indisponible", "Bluetooth unavailable")
            return
        }
        val devices = try {
            adapter.bondedDevices.toList().sortedBy { (it.name ?: "").lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            toast(
                "Aucun appareil Bluetooth jumelé. Jumelle d'abord ton téléphone au véhicule.",
                "No paired Bluetooth devices. Pair your phone with the vehicle first."
            )
            return
        }
        val labels = devices.map { d ->
            val n = d.name ?: tr("Appareil sans nom", "Unnamed device")
            val hint = if (n.lowercase().let { it.contains("hyundai") || it.contains("tucson") || it.contains("car") }) "  🚗" else ""
            "$n$hint"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(tr("Bluetooth de ton véhicule", "Your vehicle's Bluetooth"))
            .setItems(labels) { _, which ->
                val d = devices[which]
                deviceAddress = d.address
                deviceName = d.name ?: d.address
                showDevice()
                if (!swWalk.isChecked) swWalk.isChecked = true
            }
            .setNegativeButton(tr("Annuler", "Cancel"), null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_BT -> if (pendingDevicePick) {
                pendingDevicePick = false
                if (hasBtPermission()) showDevicePicker()
                else toast(
                    "Permission « Appareils à proximité » requise pour détecter le véhicule.",
                    "“Nearby devices” permission is required to detect the vehicle."
                )
            }
            REQ_LOC -> if (Parking.hasForeground(this)) {
                if (!Parking.hasBackground(this)) askBackgroundLocation()
            } else {
                swPark.isChecked = false
                toast("Localisation refusée : le stationnement ne peut pas être mémorisé.", "Location denied: parking can't be remembered.")
            }
            REQ_LOC_BG -> if (!Parking.hasBackground(this)) {
                toast(
                    "Sans « Toujours autoriser », la position ne sera enregistrée que si l'app est ouverte.",
                    "Without “Allow all the time”, the position is only saved while the app is open."
                )
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun testWalkAway() {
        val cfg = collect()
        if (!cfg.hasCredentials() || Car.info() == null) {
            toast("Enregistre d'abord ton compte.", "Save your account first.")
            return
        }
        cfg.save()
        WalkAwayService.start(this, dryRun = true)
        toast(
            "Test lancé : regarde la notification. Aucun verrouillage ne sera envoyé.",
            "Test started: watch the notification. No lock command will be sent."
        )
    }
}
