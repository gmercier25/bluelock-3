package ca.gmercier.bluelock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
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
        private const val REQ_BT = 10
        private const val REQ_NOTIF = 11
    }

    private val main = Handler(Looper.getMainLooper())

    private val temps = (0..20).map { 17.0 + it * 0.5 }
    private val seatLevels = listOf("Arrêt", "Bas", "Moyen", "Élevé")
    private val durations = listOf(5, 10, 15, 20, 25, 30)
    private val delays = listOf(0, 15, 30, 45, 60, 90, 120)

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPin: EditText
    private lateinit var etName: EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Car.init(this)
        setContentView(R.layout.activity_settings)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etPin = findViewById(R.id.etPin)
        etName = findViewById(R.id.etName)
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
        spTrim = findViewById(R.id.spTrim)
        spColor = findViewById(R.id.spColor)
        swWalk = findViewById(R.id.swWalk)
        tvWalkDevice = findViewById(R.id.tvWalkDevice)
        spWalkDelay = findViewById(R.id.spWalkDelay)
        cbWalkConfirm = findViewById(R.id.cbWalkConfirm)
        cbWalkNotify = findViewById(R.id.cbWalkNotify)
        tvBattery = findViewById(R.id.tvBattery)

        spWarmTemp.adapter = adapter(temps.map { fmtTemp(it) })
        spCoolTemp.adapter = adapter(temps.map { fmtTemp(it) })
        spWarmSeat.adapter = adapter(seatLevels)
        spCoolSeat.adapter = adapter(seatLevels)
        spDuration.adapter = adapter(durations.map { "$it min" })
        spTrim.adapter = adapter(CarImages.TRIMS.values.toList())
        spColor.adapter = adapter(CarImages.COLORS.values.toList())
        spWalkDelay.adapter = adapter(delays.map { if (it == 0) "Immédiat" else "$it s" })

        fill(Config.load())

        findViewById<Button>(R.id.btnWalkDevice).setOnClickListener { pickDevice() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.btnWalkTest).setOnClickListener { testWalkAway() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        swWalk.setOnCheckedChangeListener { _, on -> if (on) ensureWalkPermissions() }

        if (intent.getBooleanExtra(EXTRA_WALK, false)) {
            val scroll = findViewById<ScrollView>(R.id.scroll)
            val section = findViewById<View>(R.id.walkSection)
            scroll.post { scroll.smoothScrollTo(0, section.top) }
        }
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        tvBattery.text = if (exempt) "✓ Optimisation de batterie désactivée pour BlueLock"
        else "⚠ Recommandé : désactive l'optimisation de batterie pour que le verrouillage auto soit fiable."
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun fill(c: Config) {
        etEmail.setText(c.username)
        etPassword.setText(c.password)
        etPin.setText(c.pin)
        etName.setText(c.displayName)
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
        tvWalkDevice.text = if (deviceAddress.isBlank()) "Aucun appareil choisi"
        else "Bluetooth du véhicule : ${deviceName.ifBlank { deviceAddress }}"
    }

    private fun collect(): Config = Config.load().copy(
        username = etEmail.text.toString().trim(),
        password = etPassword.text.toString(),
        pin = etPin.text.toString().trim(),
        displayName = etName.text.toString().trim(),
        mfaSms = rbSms.isChecked,
        warmTemp = temps[spWarmTemp.selectedItemPosition.coerceAtLeast(0)],
        coolTemp = temps[spCoolTemp.selectedItemPosition.coerceAtLeast(0)],
        warmFrontDefrost = cbWarmFront.isChecked,
        warmRearDefrost = cbWarmRear.isChecked,
        warmSteering = cbWarmSteering.isChecked,
        warmSeat = spWarmSeat.selectedItemPosition.coerceIn(0, 3),
        coolSeat = spCoolSeat.selectedItemPosition.coerceIn(0, 3),
        seatsAll = cbSeatsAll.isChecked,
        durationMin = durations[spDuration.selectedItemPosition.coerceAtLeast(0)],
        imageTrim = CarImages.TRIMS.keys.elementAt(spTrim.selectedItemPosition.coerceAtLeast(0)),
        imageColor = CarImages.COLORS.keys.elementAt(spColor.selectedItemPosition.coerceAtLeast(0)),
        walkEnabled = swWalk.isChecked,
        walkDeviceAddress = deviceAddress,
        walkDeviceName = deviceName,
        walkDelaySec = delays[spWalkDelay.selectedItemPosition.coerceAtLeast(0)],
        walkConfirm = cbWalkConfirm.isChecked,
        walkNotifyAlreadyLocked = cbWalkNotify.isChecked,
    )

    // ------------------------------------------------------------ Enregistrer

    private fun save() {
        val old = Config.load()
        val cfg = collect()
        if (!cfg.hasCredentials()) {
            Toast.makeText(this, "Courriel, mot de passe et NIP sont requis.", Toast.LENGTH_LONG).show()
            return
        }
        if (cfg.pin.length != 4 || !cfg.pin.all { it.isDigit() }) {
            Toast.makeText(this, "Le NIP Bluelink compte 4 chiffres.", Toast.LENGTH_LONG).show()
            return
        }
        if (cfg.walkEnabled && cfg.walkDeviceAddress.isBlank()) {
            Toast.makeText(this, "Choisis le Bluetooth de ton véhicule pour le verrouillage auto.", Toast.LENGTH_LONG).show()
            return
        }
        cfg.save()
        val imageChanged = old.imageTrim != cfg.imageTrim || old.imageColor != cfg.imageColor
        val needLogin = old.username != cfg.username || old.password != cfg.password || Car.info() == null

        if (!needLogin) {
            if (imageChanged) Car.executor.execute { try { Car.ensureImage(force = true) } catch (_: Exception) {} }
            Widgets.updateAll(this)
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("Connexion à MyHyundai…")
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
                main.post { Toast.makeText(this, "Connecté : ${info.displayName}", Toast.LENGTH_LONG).show() }
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
                    finish()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Connexion impossible")
                        .setMessage(err.message ?: err.javaClass.simpleName)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Tout effacer ?")
            .setMessage("Supprime tes identifiants, jetons, réglages et données du véhicule de ce téléphone.")
            .setPositiveButton("Effacer") { _, _ ->
                Car.executor.execute { Car.reset() }
                Toast.makeText(this, "Données effacées", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Annuler", null)
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
            Toast.makeText(this, "Bluetooth indisponible", Toast.LENGTH_LONG).show()
            return
        }
        val devices = try {
            adapter.bondedDevices.toList().sortedBy { (it.name ?: "").lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            Toast.makeText(this, "Aucun appareil Bluetooth jumelé. Jumelle d'abord ton téléphone au véhicule.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { d ->
            val n = d.name ?: "Appareil sans nom"
            val hint = if (n.lowercase().let { it.contains("hyundai") || it.contains("tucson") || it.contains("car") }) "  🚗" else ""
            "$n$hint"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bluetooth de ton véhicule")
            .setItems(labels) { _, which ->
                val d = devices[which]
                deviceAddress = d.address
                deviceName = d.name ?: d.address
                showDevice()
                if (!swWalk.isChecked) swWalk.isChecked = true
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && pendingDevicePick) {
            pendingDevicePick = false
            if (hasBtPermission()) showDevicePicker()
            else Toast.makeText(this, "Permission « Appareils à proximité » requise pour détecter le véhicule.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Enregistre d'abord ton compte.", Toast.LENGTH_LONG).show()
            return
        }
        cfg.save()
        WalkAwayService.start(this, dryRun = true)
        Toast.makeText(
            this,
            "Test lancé : regarde la notification. Aucun verrouillage ne sera envoyé.",
            Toast.LENGTH_LONG
        ).show()
    }
}
