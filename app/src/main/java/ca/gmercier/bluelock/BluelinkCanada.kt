package ca.gmercier.bluelock

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Client de l'API Hyundai Bluelink Canada (mybluelink.ca), porté de Bluelocke
 * (src/lib/bluelink-regions/canada.ts). Communique uniquement avec Hyundai.
 */
class BluelinkCanada {

    private val host = "mybluelink.ca"
    private val base = "https://$host/tods/api/"

    data class Tokens(val access: String, val refresh: String, val expiry: Long, val cookie: String)

    class Resp(val code: Int, val json: JSONObject, private val headers: Map<String, List<String>>) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]?.firstOrNull()
        fun headers(name: String): List<String> = headers[name.lowercase(Locale.US)] ?: emptyList()
    }

    class CommandResult(val confirmed: Boolean, val vehicle: JSONObject?)

    // ------------------------------------------------------------------ HTTP

    private fun tzOffset(): String {
        val mins = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val h = abs(mins) / 60
        return (if (mins >= 0) "+" else "-") + h.toString().padStart(2, '0')
    }

    private fun deviceId(): String {
        Store.get(K_DEVICE)?.let { return it }
        val id = UUID.randomUUID().toString().uppercase(Locale.US)
        Store.put(K_DEVICE, id)
        return id
    }

    private fun regenerateDeviceId() {
        Store.put(K_DEVICE, UUID.randomUUID().toString().uppercase(Locale.US))
    }

    private fun baseHeaders(): MutableMap<String, String> = linkedMapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "deviceid" to deviceId(),
        "from" to "SPA",
        "client_id" to "HATAHSPACA0232141ED9722C67715A0B",
        "client_secret" to "CLISCR01AHSPA",
        "language" to "0",
        "offset" to tzOffset(),
        "User-Agent" to "MyHyundai/2.0.25 (iPhone; iOS 18.3; Scale/3.00)",
    )

    private fun http(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        readTimeoutMs: Int = 30_000,
    ): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null && !headers.containsKey("Content-Type")) {
                conn.setRequestProperty("Content-Type", "application/json")
            }
            if (method == "POST") {
                val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: JSONException) {
                JSONObject().put("_raw", text.take(300)).put("_http", code)
            }
            val hdrs = HashMap<String, List<String>>()
            for (entry in conn.headerFields.entries) {
                val k: String? = entry.key
                if (k != null) hdrs[k.lowercase(Locale.US)] = entry.value
            }
            return Resp(code, json, hdrs)
        } catch (e: IOException) {
            throw BlException("Erreur réseau : ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun valid(j: JSONObject): Boolean =
        j.optJSONObject("responseHeader")?.optInt("responseCode", -1) == 0

    private fun needsReauth(j: JSONObject): Boolean {
        if (j.optJSONObject("responseHeader")?.optInt("responseCode", -1) != 1) return false
        val desc = j.optJSONObject("error")?.optString("errorDesc", "")?.lowercase(Locale.US) ?: ""
        return desc.contains("expired") || desc.contains("deleted") || desc.contains("ip validation")
    }

    private fun errorText(j: JSONObject): String {
        val e = j.optJSONObject("error")
        val desc = e?.optString("errorDesc").orEmpty()
        val code = e?.optString("errorCode").orEmpty()
        return when {
            desc.isNotBlank() && code.isNotBlank() -> "$desc ($code)"
            desc.isNotBlank() -> desc
            j.has("_raw") -> "Réponse inattendue (HTTP ${j.optInt("_http")})"
            else -> "Réponse inattendue du serveur Hyundai"
        }
    }

    // ------------------------------------------------------------------ Jetons

    fun loadTokens(): Tokens? {
        val raw = Store.get(K_TOKENS) ?: return null
        return try {
            val j = JSONObject(raw)
            Tokens(j.getString("access"), j.optString("refresh"), j.optLong("expiry"), j.optString("cookie"))
        } catch (e: Exception) {
            null
        }
    }

    private fun saveTokens(tok: JSONObject, cookie: String): Tokens {
        val access = tok.optString("accessToken")
        if (access.isBlank()) throw BlException("Jeton d'accès manquant dans la réponse Hyundai")
        val t = Tokens(
            access = access,
            refresh = tok.optString("refreshToken"),
            expiry = System.currentTimeMillis() / 1000 + tok.optLong("expireIn", 1800),
            cookie = cookie,
        )
        Store.put(
            K_TOKENS,
            JSONObject().put("access", t.access).put("refresh", t.refresh)
                .put("expiry", t.expiry).put("cookie", t.cookie).toString()
        )
        return t
    }

    fun clearTokens() = Store.put(K_TOKENS, null)

    private fun ensureTokens(mfa: MfaHandler?): Tokens {
        val t = loadTokens()
        if (t != null && t.expiry - 30 > System.currentTimeMillis() / 1000) return t
        return login(mfa)
    }

    // ------------------------------------------------------------------ Connexion

    private fun cfCookie(): String = try {
        val r = http("https://$host/login", "GET", null, baseHeaders())
        r.headers("set-cookie").firstOrNull { it.trim().startsWith("__cf_bm=", ignoreCase = true) }
            ?.substringBefore(';')?.trim() ?: ""
    } catch (e: BlException) {
        ""
    }

    private fun noAuthPost(path: String, body: JSONObject, extra: Map<String, String> = emptyMap()): Resp {
        val h = baseHeaders()
        h.putAll(extra)
        return http(base + path, "POST", body.toString(), h)
    }

    /** Connexion par courriel / mot de passe, avec vérification en deux étapes si demandée. */
    fun login(mfa: MfaHandler?): Tokens {
        CrashLog.step("API : connexion")
        val cfg = Config.load()
        if (!cfg.hasCredentials()) throw NeedLoginException("Configure ton compte MyHyundai dans les réglages.")
        val cookie = cfCookie()
        val r = noAuthPost(
            "v2/login",
            JSONObject().put("loginId", cfg.username).put("password", cfg.password),
            if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()
        )
        if (valid(r.json)) {
            val tok = r.json.o("result").o("token") ?: throw BlException("Réponse de connexion incomplète")
            return saveTokens(tok, cookie)
        }
        val errCode = r.json.optJSONObject("error")?.optString("errorCode")
        CrashLog.step("API : réponse connexion code=${r.code} erreur=$errCode")
        if (errCode == "7110") {
            if (mfa == null) {
                throw NeedLoginException("Hyundai demande un code de vérification. Ouvre BlueLock pour te reconnecter.")
            }
            return mfaFlow(cfg, cookie, mfa)
        }
        regenerateDeviceId()
        throw BlException("Connexion refusée : ${errorText(r.json)}")
    }

    private fun mfaFlow(cfg: Config, cookie: String, mfa: MfaHandler): Tokens {
        val api = "0107"
        val sel = noAuthPost(
            "mfa/selverifmeth",
            JSONObject().put("mfaApiCode", api).put("userAccount", cfg.username)
        )
        if (!valid(sel.json)) throw BlException("Vérification impossible : ${errorText(sel.json)}")
        val res = sel.json.o("result") ?: JSONObject()
        val email = res.optJSONArray("emailList")?.optString(0)?.ifBlank { null } ?: cfg.username
        val phone = res.optString("userPhone", "").ifBlank { null }
        val uuid = res.optString("userInfoUuid")
        val sms = cfg.mfaSms && phone != null

        val send = noAuthPost(
            "mfa/sendotp",
            JSONObject()
                .put("otpMethod", if (sms) "M" else "E")
                .put("mfaApiCode", api)
                .put("userAccount", email)
                .put("userPhone", if (sms) phone else "")
                .put("userInfoUuid", uuid)
        )
        val otpKey = send.json.o("result")?.optString("otpKey").orEmpty()
        if (!valid(send.json) || otpKey.isBlank()) {
            regenerateDeviceId()
            throw BlException("Envoi du code impossible : ${errorText(send.json)}")
        }

        CrashLog.step("API : code envoyé (sms=$sms)")
        val code = mfa.askCode(if (sms) phone!! else email)?.trim()
        if (code.isNullOrBlank()) throw BlException("Vérification annulée.")

        val v = noAuthPost(
            "mfa/validateotp",
            JSONObject().put("otpNo", code).put("userAccount", cfg.username)
                .put("otpKey", otpKey).put("mfaApiCode", api)
        )
        val vr = v.json.o("result")
        val validationKey = vr?.optString("otpValidationKey").orEmpty()
        if (!valid(v.json) || validationKey.isBlank() || bool(vr.v("verifiedOtp")) != true) {
            regenerateDeviceId()
            throw BlException("Code de vérification invalide.")
        }

        CrashLog.step("API : code validé")
        val g = noAuthPost(
            "mfa/genmfatkn",
            JSONObject().put("userAccount", cfg.username).put("otpEmail", email)
                .put("mfaApiCode", api).put("otpValidationKey", validationKey).put("mfaYn", "Y")
        )
        val tok = g.json.o("result").o("token")
        if (!valid(g.json) || tok == null) {
            regenerateDeviceId()
            throw BlException("Échec de la création du jeton : ${errorText(g.json)}")
        }
        CrashLog.step("API : jeton MFA obtenu")
        return saveTokens(tok, cookie)
    }

    // ------------------------------------------------------------------ Requêtes authentifiées

    private fun authed(
        path: String,
        body: JSONObject?,
        extra: Map<String, String>,
        mfa: MfaHandler?,
        timeoutMs: Int = 30_000,
        retry: Boolean = true,
    ): Resp {
        val t = ensureTokens(mfa)
        val h = baseHeaders()
        h["Accesstoken"] = t.access
        if (t.cookie.isNotEmpty()) h["Cookie"] = t.cookie
        h.putAll(extra)
        val r = http(base + path, "POST", body?.toString(), h, timeoutMs)
        if (!valid(r.json) && retry && needsReauth(r.json)) {
            clearTokens()
            login(mfa)
            return authed(path, body, extra, mfa, timeoutMs, retry = false)
        }
        return r
    }

    // ------------------------------------------------------------------ Véhicule

    fun vehicle(preferredVin: String?, mfa: MfaHandler?): CarInfo {
        CrashLog.step("API : liste des véhicules")
        val r = authed("vhcllst", null, emptyMap(), mfa)
        if (!valid(r.json)) throw BlException("Impossible d'obtenir la liste des véhicules : ${errorText(r.json)}")
        val list: JSONArray = r.json.o("result")?.optJSONArray("vehicles")
            ?: throw BlException("Aucun véhicule sur ce compte.")
        if (list.length() == 0) throw BlException("Aucun véhicule sur ce compte.")
        var v = list.getJSONObject(0)
        if (!preferredVin.isNullOrBlank()) {
            for (i in 0 until list.length()) {
                val x = list.getJSONObject(i)
                if (x.optString("vin") == preferredVin) {
                    v = x; break
                }
            }
        }
        val info = CarInfo(
            id = v.optString("vehicleId"),
            vin = v.optString("vin"),
            nickName = v.optString("nickName"),
            modelName = v.optString("modelName"),
            modelYear = v.optString("modelYear"),
            color = v.optString("exteriorColor"),
            trim = v.optString("trim"),
        )
        // Sélectionne le véhicule côté serveur (comme l'app officielle).
        val s = authed("vhcllst", JSONObject().put("vehicleId", info.id), emptyMap(), mfa)
        if (!valid(s.json)) throw BlException("Impossible de sélectionner le véhicule : ${errorText(s.json)}")
        return info
    }

    /**
     * Statut du véhicule.
     * force = false : dernier statut connu du serveur Hyundai (rapide, ne réveille pas l'auto).
     * force = true  : interroge le véhicule en temps réel (plus lent).
     */
    fun status(id: String, force: Boolean, prev: CarStatus?, mfa: MfaHandler?): CarStatus {
        CrashLog.step("API : statut (force=$force)")
        val r = if (force) {
            authed("rltmvhclsts", null, mapOf("Vehicleid" to id), mfa, timeoutMs = 120_000)
        } else {
            authed("sltvhcl", JSONObject().put("vehicleId", id), mapOf("Vehicleid" to id), mfa)
        }
        if (!valid(r.json)) throw BlException("Statut indisponible : ${errorText(r.json)}")
        val res = r.json.o("result") ?: throw BlException("Statut vide")
        val s = res.o("status") ?: JSONObject()
        val odo = if (force) num(s.v("odometer")) else num(res.o("vehicle").v("odometer"))
        CrashLog.step("API : analyse du statut")
        var st = StatusParser.parse(s, odo, prev, force)
        if (force && st.supportsCharge) {
            try {
                val (ac, dc) = chargeLimit(id, mfa)
                if (ac > 0) st = st.copy(chargeAc = ac, chargeDc = dc)
            } catch (e: BlException) { /* facultatif */ }
        }
        return st
    }

    private fun pAuth(mfa: MfaHandler?): String {
        val pin = Config.load().pin
        val r = authed("vrfypin", JSONObject().put("pin", pin), emptyMap(), mfa)
        val p = r.json.o("result")?.optString("pAuth").orEmpty()
        if (!valid(r.json) || p.isBlank()) throw BlException("NIP refusé : ${errorText(r.json)}")
        return p
    }

    private fun command(id: String, path: String, payload: JSONObject, mfa: MfaHandler?): CommandResult {
        val pauth = pAuth(mfa)
        payload.put("pin", Config.load().pin)
        val r = authed(path, payload, mapOf("Vehicleid" to id, "Pauth" to pauth), mfa)
        if (!valid(r.json)) throw RejectedException("Commande refusée : ${errorText(r.json)}")
        val tx = r.header("transactionId") ?: return CommandResult(false, null)
        return poll(id, pauth, tx, mfa)
    }

    private fun poll(id: String, pauth: String, tx: String, mfa: MfaHandler?): CommandResult {
        repeat(MAX_POLLS + 1) {
            val r = authed(
                "rmtsts", null,
                mapOf("Vehicleid" to id, "Pauth" to pauth, "TransactionId" to tx), mfa
            )
            if (!valid(r.json)) throw BlException("Suivi de la commande impossible : ${errorText(r.json)}")
            val res = r.json.o("result")
            if (res.o("transaction")?.optString("apiResult") == "C") {
                return CommandResult(true, res.o("vehicle"))
            }
            Thread.sleep(2000)
        }
        return CommandResult(false, null)
    }

    fun lock(id: String, mfa: MfaHandler?) = command(id, "drlck", JSONObject(), mfa)
    fun unlock(id: String, mfa: MfaHandler?) = command(id, "drulck", JSONObject(), mfa)
    fun climateOff(id: String, mfa: MfaHandler?) = command(id, "evc/rfoff", JSONObject(), mfa)
    fun startCharge(id: String, mfa: MfaHandler?) = command(id, "evc/rcstrt", JSONObject(), mfa)
    fun stopCharge(id: String, mfa: MfaHandler?) = command(id, "evc/rcstp", JSONObject(), mfa)

    fun climateOn(id: String, req: ClimateRequest, mfa: MfaHandler?): CommandResult {
        return try {
            command(id, "evc/rfon", climatePayload(req, "hvacInfo"), mfa)
        } catch (e: RejectedException) {
            // Véhicules récents : autre nom de champ (comme dans Bluelocke).
            command(id, "evc/rfon", climatePayload(req, "remoteControl"), mfa)
        }
    }

    private fun climatePayload(req: ClimateRequest, key: String): JSONObject {
        val hvac = JSONObject()
            .put("airCtrl", 1)
            .put("defrost", req.frontDefrost)
            .put(
                "airTemp",
                JSONObject().put("value", tempHex(req.temp)).put("unit", 0).put("hvacTempType", 1)
            )
            .put("igniOnDuration", req.durationMin)
            .put("heating1", heatingValue(req.rearDefrost, req.steering))
        if (req.seatDriver + req.seatPassenger + req.seatRearLeft + req.seatRearRight > 0) {
            hvac.put(
                "seatHeaterVentCMD",
                JSONObject().put("drvSeatOptCmd", req.seatDriver).put("astSeatOptCmd", req.seatPassenger)
                    .put("rlSeatOptCmd", req.seatRearLeft).put("rrSeatOptCmd", req.seatRearRight)
            )
        }
        return JSONObject().put(key, hvac)
    }

    fun locate(id: String, mfa: MfaHandler?): Pair<Double, Double> {
        val pauth = pAuth(mfa)
        val r = authed(
            "fndmcr", JSONObject().put("pin", Config.load().pin),
            mapOf("Vehicleid" to id, "Pauth" to pauth), mfa, timeoutMs = 90_000
        )
        val c = r.json.o("result").o("coord")
        val lat = num(c.v("lat"))
        val lon = num(c.v("lon"))
        if (!valid(r.json) || lat == null || lon == null) {
            throw BlException("Position indisponible : ${errorText(r.json)}")
        }
        return lat to lon
    }

    fun chargeLimit(id: String, mfa: MfaHandler?): Pair<Int, Int> {
        val r = authed("evc/selsoc", null, mapOf("Vehicleid" to id), mfa)
        var ac = 0
        var dc = 0
        val arr = r.json.optJSONArray("result")
        if (valid(r.json) && arr != null) {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                when (x.optInt("plugType", -1)) {
                    0 -> dc = x.optInt("level")
                    1 -> ac = x.optInt("level")
                }
            }
        }
        return ac to dc
    }

    fun setChargeLimit(id: String, ac: Int, dc: Int, mfa: MfaHandler?): CommandResult {
        val tsoc = JSONArray()
            .put(JSONObject().put("plugType", 0).put("level", dc))
            .put(JSONObject().put("plugType", 1).put("level", ac))
        return command(id, "evc/setsoc", JSONObject().put("tsoc", tsoc), mfa)
    }

    companion object {
        private const val K_TOKENS = "tokens"
        private const val K_DEVICE = "deviceId"
        private const val MAX_POLLS = 20

        /** 17 °C → "06H", 17,5 °C → "07H", … 27 °C → "1AH" (table de Bluelocke). */
        fun tempHex(tempC: Double): String {
            val idx = ((tempC.coerceIn(17.0, 27.0) - 17.0) / 0.5).roundToInt()
            return Integer.toHexString(6 + idx).uppercase(Locale.US).padStart(2, '0') + "H"
        }

        fun heatingValue(rearDefrost: Boolean, steering: Boolean): Int = when {
            rearDefrost && steering -> 4
            steering -> 3
            rearDefrost -> 2
            else -> 0
        }
    }
}

/** Conversion de la réponse "status" en [CarStatus] (logique de returnCarStatus / defaultNoEVStatus). */
object StatusParser {
    private val dateFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank() || s.length < 14) return null
        return try {
            dateFmt.get()!!.parse(s.substring(0, 14))?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun pct(x: Double?): Int? {
        if (x == null || x.isNaN()) return null
        val v = if (x > 0 && x <= 1) x * 100 else x
        return if (v < 0 || v > 100) null else v.roundToInt()
    }

    private fun fuelLevel(s: JSONObject): Int? {
        val candidates = listOf(
            s.v("fuelLevel"), s.v("FuelLevel"), s.v("fuelLevelPercent"), s.v("fuelLevelPrcnt"),
            s.o("fuel").v("level"), s.o("fuelInfo").v("level"), s.o("engineStatus").v("fuelLevel"),
        )
        for (c in candidates) pct(num(c))?.let { return it }
        return null
    }

    private fun anyOpen(o: JSONObject?): Boolean {
        if (o == null) return false
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val x = o.opt(k)
            if (x is JSONObject) {
                if (anyOpen(x)) return true
            } else if (bool(x) == true) return true
        }
        return false
    }

    fun parse(s: JSONObject, odometer: Double?, prev: CarStatus?, force: Boolean): CarStatus {
        val now = System.currentTimeMillis()
        val ev = s.o("evStatus")
        val hasEv = num(ev.v("batteryStatus")) != null

        val rangeByFuel = ev?.optJSONArray("drvDistance")?.optJSONObject(0).o("rangeByFuel")
        var range = num(rangeByFuel.o("totalAvailableRange").v("value"))
            ?: num(rangeByFuel.o("evModeRange").v("value"))
        if (range == null || range <= 0) range = num(s.o("dte").v("value"))
        val rangeInt = range?.takeIf { it > 0 }?.roundToInt() ?: prev?.range

        val charging = hasEv && bool(ev.v("batteryCharge")) == true
        val plugged = hasEv && (num(ev.v("batteryPlugin")) ?: 0.0) > 0
        val power = ev.o("batteryPower")
        val fast = num(power.v("batteryFstChrgPower")) ?: 0.0
        val std = num(power.v("batteryStndChrgPower")) ?: 0.0
        val remain = num(ev.o("remainTime2").o("atc").v("value"))
        val supportsCharge = (hasEv && (remain != null || plugged || charging)) || (prev?.supportsCharge == true)

        val climate = bool(s.v("airCtrlOn")) ?: bool(s.o("climate").v("airCtrl")) ?: prev?.climate ?: false

        return CarStatus(
            lastCheck = now,
            lastRemote = if (force) now else (parseDate(s.optString("lastStatusDate")) ?: now),
            locked = bool(s.v("doorLock")) ?: prev?.locked ?: false,
            climate = climate,
            engine = bool(s.v("engine")) ?: false,
            fuel = fuelLevel(s) ?: prev?.fuel,
            soc = if (hasEv) num(ev.v("batteryStatus"))?.roundToInt() else prev?.soc,
            range = rangeInt,
            odometer = odometer?.takeIf { it > 0 } ?: prev?.odometer,
            twelve = num(s.o("battery").v("batSoc"))?.roundToInt()?.takeIf { it > 0 } ?: prev?.twelve,
            charging = charging,
            plugged = plugged,
            chargePowerKw = if (charging) (if (fast > 0) fast else std) else 0.0,
            remainMin = remain?.roundToInt() ?: (prev?.remainMin ?: 0),
            supportsCharge = supportsCharge,
            chargeAc = prev?.chargeAc,
            chargeDc = prev?.chargeDc,
            lat = prev?.lat,
            lon = prev?.lon,
            doorsOpen = anyOpen(s.o("doorOpen")),
            trunkOpen = bool(s.v("trunkOpen")) == true,
            hoodOpen = bool(s.v("hoodOpen")) == true,
        )
    }
}
