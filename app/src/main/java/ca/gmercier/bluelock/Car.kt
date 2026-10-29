package ca.gmercier.bluelock

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Résultat de la vérification « walk-away ». */
sealed class WalkResult {
    object AlreadyLocked : WalkResult()
    object Running : WalkResult()
    object WouldLock : WalkResult()        // simulation
    object NeedsConfirm : WalkResult()     // déverrouillé, arrêté : prêt à verrouiller
    object Locked : WalkResult()
    class Failed(val message: String) : WalkResult()
}

/** Dépôt central : cache local chiffré + appels à l'API (sérialisés). */
object Car {
    /** Un seul fil pour toutes les opérations réseau : jamais deux commandes en même temps. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val api = BluelinkCanada()
    private lateinit var appCtx: Context

    private const val K_INFO = "carInfo"
    private const val K_STATUS = "carStatus"
    private const val K_IMG = "carImageKey"

    fun init(ctx: Context) {
        Store.init(ctx)
        if (!::appCtx.isInitialized) appCtx = ctx.applicationContext
    }

    fun info(): CarInfo? = Store.get(K_INFO)?.let { CarInfo.fromJson(it) }
    fun status(): CarStatus? = Store.get(K_STATUS)?.let { CarStatus.fromJson(it) }

    fun displayName(): String {
        val cfg = Config.load()
        return cfg.displayName.ifBlank { info()?.displayName ?: tr("Mon véhicule", "My vehicle") }
    }

    private fun saveStatus(s: CarStatus) {
        CrashLog.step("Enregistrement du statut")
        Store.put(K_STATUS, s.toJson())
        if (::appCtx.isInitialized) Widgets.updateAll(appCtx)
    }

    /** Efface tout (déconnexion). */
    @Synchronized
    fun reset() {
        Store.clearAll()
        carImageFile().delete()
        if (::appCtx.isInitialized) Widgets.updateAll(appCtx)
    }

    // ------------------------------------------------------------ Connexion

    @Synchronized
    fun login(mfa: MfaHandler?): CarInfo {
        api.clearTokens()
        api.login(mfa)
        val info = api.vehicle(info()?.vin, mfa)
        Store.put(K_INFO, info.toJson())
        return info
    }

    @Synchronized
    private fun car(mfa: MfaHandler?): CarInfo {
        info()?.let { return it }
        val info = api.vehicle(null, mfa)
        Store.put(K_INFO, info.toJson())
        return info
    }

    // ------------------------------------------------------------ Statut

    @Synchronized
    fun refresh(force: Boolean, mfa: MfaHandler?): CarStatus {
        val c = car(mfa)
        val st = api.status(c.id, force, status(), mfa)
        saveStatus(st)
        return st
    }

    // ------------------------------------------------------------ Commandes

    private fun applyResult(r: BluelinkCanada.CommandResult, tweak: (CarStatus) -> CarStatus) {
        if (!r.confirmed) throw BlException(tr("Commande envoyée, mais le véhicule n'a pas confirmé à temps.", "Command sent, but the vehicle did not confirm in time."))
        val prev = status()
        val parsed = r.vehicle?.let { StatusParser.parse(it, null, prev, true) } ?: prev
        if (parsed != null) saveStatus(tweak(parsed.copy(lastCheck = System.currentTimeMillis())))
    }

    /**
     * Après un verrouillage / déverrouillage, vérifie l'état RÉEL des portes :
     * 1) l'état renvoyé avec la confirmation de la commande ;
     * 2) sinon, un statut interrogé directement au véhicule.
     * Lance une erreur si l'auto n'est pas dans l'état demandé.
     */
    private fun verifyLock(c: CarInfo, r: BluelinkCanada.CommandResult, wantLocked: Boolean, mfa: MfaHandler?) {
        if (!r.confirmed) throw BlException(tr("Commande envoyée, mais le véhicule n'a pas confirmé à temps.", "Command sent, but the vehicle did not confirm in time."))
        val prev = status()
        val reported = r.vehicle?.let { bool(it.v("doorLock")) }
        CrashLog.step("Verrouillage : état renvoyé par la commande = $reported")
        var st = r.vehicle?.let { StatusParser.parse(it, null, prev, true) } ?: prev
        if (reported == null || reported != wantLocked) {
            // Pas d'état fiable dans la réponse : on interroge le véhicule.
            Thread.sleep(3000)
            st = api.status(c.id, true, st, mfa)
            CrashLog.step("Verrouillage : état vérifié au véhicule = ${st.locked}")
        } else if (st != null) {
            st = st.copy(locked = reported)
        }
        if (st != null) saveStatus(st.copy(lastCheck = System.currentTimeMillis()))
        if (st == null || st.locked != wantLocked) {
            val reasons = mutableListOf<String>()
            if (st?.doorsOpen == true) reasons += tr("une portière est ouverte", "a door is open")
            if (st?.trunkOpen == true) reasons += tr("le coffre est ouvert", "the trunk is open")
            if (st?.hoodOpen == true) reasons += tr("le capot est ouvert", "the hood is open")
            if (st?.engine == true) reasons += tr("le moteur est en marche", "the engine is running")
            val why = if (reasons.isNotEmpty()) " (" + reasons.joinToString(", ") + ")"
            else tr(" (portière mal fermée ou clé détectée dans l'auto ?)", " (door not fully closed, or key detected inside?)")
            throw BlException(
                if (wantLocked) tr("Hyundai a accepté la commande, mais le véhicule est toujours déverrouillé", "Hyundai accepted the command, but the vehicle is still unlocked") + why + "."
                else tr("Hyundai a accepté la commande, mais le véhicule est toujours verrouillé.", "Hyundai accepted the command, but the vehicle is still locked.")
            )
        }
    }

    @Synchronized
    fun lock(mfa: MfaHandler?) {
        val c = car(mfa)
        verifyLock(c, api.lock(c.id, mfa), true, mfa)
        if (::appCtx.isInitialized) Alerts.cancelUnlocked(appCtx)
    }

    @Synchronized
    fun unlock(mfa: MfaHandler?) {
        val c = car(mfa)
        verifyLock(c, api.unlock(c.id, mfa), false, mfa)
    }

    @Synchronized
    fun climateOn(req: ClimateRequest, mfa: MfaHandler?) {
        val c = car(mfa)
        applyResult(api.climateOn(c.id, req, c.isElectrified, mfa)) { it.copy(climate = true) }
    }

    @Synchronized
    fun climateOff(mfa: MfaHandler?) {
        val c = car(mfa)
        applyResult(api.climateOff(c.id, c.isElectrified, mfa)) { it.copy(climate = false) }
    }

    @Synchronized
    fun startCharge(mfa: MfaHandler?) {
        val c = car(mfa)
        applyResult(api.startCharge(c.id, mfa)) { it.copy(charging = true) }
    }

    @Synchronized
    fun stopCharge(mfa: MfaHandler?) {
        val c = car(mfa)
        applyResult(api.stopCharge(c.id, mfa)) { it.copy(charging = false) }
    }

    @Synchronized
    fun setChargeLimit(ac: Int, dc: Int, mfa: MfaHandler?) {
        val c = car(mfa)
        applyResult(api.setChargeLimit(c.id, ac, dc, mfa)) { it.copy(chargeAc = ac, chargeDc = dc) }
    }

    @Synchronized
    fun locate(mfa: MfaHandler?): Pair<Double, Double> {
        val c = car(mfa)
        val (lat, lon) = api.locate(c.id, mfa)
        status()?.let { saveStatus(it.copy(lat = lat, lon = lon)) }
        return lat to lon
    }

    // ------------------------------------------------------------ Walk-away

    /**
     * Logique de Bluelocke (autoLock / autoLockChecker) :
     * statut serveur → déjà verrouillé ? rien. Climatisation ou moteur en marche ? rien.
     * Sinon verrouille (ou signale seulement, selon [mode]).
     */
    enum class WalkMode { LOCK, CHECK_ONLY, DRY_RUN }

    @Synchronized
    fun walkAway(mode: WalkMode): WalkResult {
        return try {
            val c = car(null)
            var st = api.status(c.id, false, status(), null)
            // Normalement l'auto envoie son statut à l'arrêt du moteur. S'il date de plus de
            // 5 minutes, on interroge le véhicule directement pour ne pas se fier à un vieux statut.
            if (System.currentTimeMillis() - st.lastRemote > 5 * 60 * 1000L) {
                st = api.status(c.id, true, st, null)
            }
            saveStatus(st)
            when {
                st.locked -> WalkResult.AlreadyLocked
                st.climate || st.engine -> WalkResult.Running
                st.doorsOpen || st.trunkOpen || st.hoodOpen -> WalkResult.Failed(
                    tr("Le véhicule est déverrouillé, mais ", "The vehicle is unlocked, but ") + listOfNotNull(
                        tr("une portière", "a door").takeIf { st.doorsOpen },
                        tr("le coffre", "the trunk").takeIf { st.trunkOpen },
                        tr("le capot", "the hood").takeIf { st.hoodOpen },
                    ).joinToString(", ") + tr(" semble ouvert : impossible de verrouiller.", " seems open: cannot lock.")
                )
                mode == WalkMode.DRY_RUN -> WalkResult.WouldLock
                mode == WalkMode.CHECK_ONLY -> WalkResult.NeedsConfirm
                else -> {
                    try {
                        verifyLock(c, api.lock(c.id, null), true, null)
                    } catch (first: BlException) {
                        // Une 2e tentative après 20 s (ex. portière qui finissait de se fermer).
                        CrashLog.step("Verrouillage auto : 1re tentative échouée, nouvel essai")
                        Thread.sleep(20_000)
                        verifyLock(c, api.lock(c.id, null), true, null)
                    }
                    WalkResult.Locked
                }
            }
        } catch (e: Exception) {
            WalkResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------ Image du véhicule

    fun carImageFile(): File = File(appCtx.filesDir, "car.png")

    /** Télécharge l'image (GitHub de Bluelocke, aucune donnée personnelle envoyée). */
    fun ensureImage(force: Boolean = false): Boolean {
        CrashLog.step("Image du véhicule")
        val cfg = Config.load()
        val candidates = CarImages.candidates(cfg, info())
        if (candidates.isEmpty()) {
            carImageFile().delete()
            Store.put(K_IMG, null)
            return false
        }
        val wanted = candidates.first()
        val file = carImageFile()
        if (!force && file.exists() && Store.get(K_IMG) == wanted) return true
        for (url in candidates) {
            if (download(url, file)) {
                Store.put(K_IMG, wanted)
                if (::appCtx.isInitialized) Widgets.updateAll(appCtx)
                return true
            }
        }
        return file.exists()
    }

    private fun download(url: String, dest: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) return false
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

/** Images de Tucson fournies par le dépôt Bluelocke (MIT). */
object CarImages {
    private const val BASE =
        "https://raw.githubusercontent.com/devindxdev/bluelocke/refs/heads/main/assets/vehicle-images"

    val TRIMS = linkedMapOf(
        "auto" to tr("Automatique", "Automatic"),
        "preferred" to "Preferred",
        "xrt" to "XRT",
        "nline" to "N Line",
        "hybrid-ultimate" to tr("Hybride Ultimate", "Hybrid Ultimate"),
    )

    val COLORS = linkedMapOf(
        "auto" to tr("Automatique", "Automatic"),
        "white" to tr("Blanc", "White"),
        "black" to tr("Noir", "Black"),
        "grey" to tr("Gris", "Grey"),
        "silver" to tr("Argent", "Silver"),
        "blue" to tr("Bleu", "Blue"),
        "green" to tr("Vert", "Green"),
        "red" to tr("Rouge", "Red"),
        "bronze" to "Bronze",
    )

    private val AVAILABLE = mapOf(
        "preferred" to listOf("white", "black", "grey", "silver", "blue", "green"),
        "xrt" to listOf("white", "black", "grey", "silver", "blue", "green"),
        "nline" to listOf("white", "black", "grey", "red"),
        "hybrid-ultimate" to listOf("white", "black", "grey", "silver", "blue", "bronze"),
    )

    private fun autoTrim(info: CarInfo?): String {
        val t = ((info?.trim ?: "") + " " + (info?.modelName ?: "")).lowercase(Locale.ROOT)
        return when {
            t.contains("n line") || t.contains("nline") || t.contains("n-line") -> "nline"
            t.contains("xrt") -> "xrt"
            t.contains("ultimate") && (t.contains("hybrid") || t.contains("hev") || t.contains("hybride")) -> "hybrid-ultimate"
            else -> "preferred"
        }
    }

    private fun autoColor(info: CarInfo?): String {
        val c = (info?.color ?: "").lowercase(Locale.ROOT)
        return when {
            listOf("black", "noir", "abyss", "phantom").any { c.contains(it) } -> "black"
            listOf("grey", "gray", "gris", "amazon", "titan", "ecotronic").any { c.contains(it) } -> "grey"
            listOf("silver", "argent", "shimmering").any { c.contains(it) } -> "silver"
            listOf("blue", "bleu", "intense", "ocean").any { c.contains(it) } -> "blue"
            listOf("green", "vert", "hampton", "evergreen").any { c.contains(it) } -> "green"
            listOf("red", "rouge").any { c.contains(it) } -> "red"
            listOf("bronze", "copper", "cuivre").any { c.contains(it) } -> "bronze"
            else -> "white"
        }
    }

    /** Liste d'URL à essayer, de la plus précise à la plus générique. */
    fun candidates(cfg: Config, info: CarInfo?): List<String> {
        val isTucson = (info?.modelName ?: "").lowercase(Locale.ROOT).contains("tucson")
        if (!isTucson && cfg.imageTrim == "auto") return emptyList()
        val trim = if (cfg.imageTrim == "auto") autoTrim(info) else cfg.imageTrim
        val colors = AVAILABLE[trim] ?: return emptyList()
        val wanted = if (cfg.imageColor == "auto") autoColor(info) else cfg.imageColor
        val color = if (wanted in colors) wanted else colors.first()
        val year = info?.modelYear?.takeIf { it == "2025" || it == "2026" } ?: "2026"
        val other = if (year == "2026") "2025" else "2026"
        return listOf(
            "$BASE/$year/tucson-$trim-$color.png",
            "$BASE/$other/tucson-$trim-$color.png",
            "$BASE/$year/tucson-$trim-${colors.first()}.png",
        ).distinct()
    }
}
