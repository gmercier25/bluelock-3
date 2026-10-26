package ca.gmercier.bluelock

import org.json.JSONObject

/** Erreur lisible par l'utilisateur. */
open class BlException(msg: String) : Exception(msg)

/** La commande a été refusée avant d'être transmise au véhicule. */
class RejectedException(msg: String) : BlException(msg)

/** Une reconnexion interactive (code de vérification) est nécessaire : ouvrir l'app. */
class NeedLoginException(msg: String) : BlException(msg)

/** Demande le code de vérification (MFA) envoyé par Hyundai. Appelée hors du fil principal. */
fun interface MfaHandler {
    fun askCode(destination: String): String?
}

// ---------- Petits utilitaires JSON tolérants ----------

fun JSONObject?.o(k: String): JSONObject? = this?.optJSONObject(k)

fun JSONObject?.v(k: String): Any? {
    val x = this?.opt(k) ?: return null
    return if (x == JSONObject.NULL) null else x
}

fun num(x: Any?): Double? = when (x) {
    is Number -> x.toDouble()
    is String -> x.trim().toDoubleOrNull()
    else -> null
}

fun bool(x: Any?): Boolean? = when (x) {
    is Boolean -> x
    is Number -> x.toInt() != 0
    is String -> when (x.trim().lowercase()) {
        "true", "1", "y", "yes" -> true
        "false", "0", "n", "no" -> false
        else -> null
    }
    else -> null
}

private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

// ---------- Réglages ----------

data class Config(
    val username: String = "",
    val password: String = "",
    val pin: String = "",
    val mfaSms: Boolean = false,
    val displayName: String = "",
    val warmTemp: Double = 21.5,
    val coolTemp: Double = 19.0,
    val warmFrontDefrost: Boolean = true,
    val warmRearDefrost: Boolean = true,
    val warmSteering: Boolean = true,
    val warmSeat: Int = 0,          // 0 Arrêt, 1 Bas, 2 Moyen, 3 Élevé
    val coolSeat: Int = 0,          // ventilation 0..3
    val seatsAll: Boolean = false,  // false = avant seulement
    val durationMin: Int = 15,
    val imageTrim: String = "auto",
    val imageColor: String = "auto",
    val walkEnabled: Boolean = false,
    val walkDeviceAddress: String = "",
    val walkDeviceName: String = "",
    val walkDelaySec: Int = 30,
    val walkConfirm: Boolean = false,
    val walkNotifyAlreadyLocked: Boolean = true,
) {
    fun hasCredentials() = username.isNotBlank() && password.isNotBlank() && pin.isNotBlank()

    fun toJson(): String = JSONObject()
        .put("username", username).put("password", password).put("pin", pin)
        .put("mfaSms", mfaSms).put("displayName", displayName)
        .put("warmTemp", warmTemp).put("coolTemp", coolTemp)
        .put("warmFrontDefrost", warmFrontDefrost).put("warmRearDefrost", warmRearDefrost)
        .put("warmSteering", warmSteering).put("warmSeat", warmSeat).put("coolSeat", coolSeat)
        .put("seatsAll", seatsAll).put("durationMin", durationMin)
        .put("imageTrim", imageTrim).put("imageColor", imageColor)
        .put("walkEnabled", walkEnabled).put("walkDeviceAddress", walkDeviceAddress)
        .put("walkDeviceName", walkDeviceName).put("walkDelaySec", walkDelaySec)
        .put("walkConfirm", walkConfirm).put("walkNotifyAlreadyLocked", walkNotifyAlreadyLocked)
        .toString()

    fun save() = Store.put(KEY, toJson())

    companion object {
        private const val KEY = "config"

        fun load(): Config {
            val raw = Store.get(KEY) ?: return Config()
            return try {
                val j = JSONObject(raw)
                val d = Config()
                Config(
                    username = j.optString("username", d.username),
                    password = j.optString("password", d.password),
                    pin = j.optString("pin", d.pin),
                    mfaSms = j.optBoolean("mfaSms", d.mfaSms),
                    displayName = j.optString("displayName", d.displayName),
                    warmTemp = j.optDouble("warmTemp", d.warmTemp),
                    coolTemp = j.optDouble("coolTemp", d.coolTemp),
                    warmFrontDefrost = j.optBoolean("warmFrontDefrost", d.warmFrontDefrost),
                    warmRearDefrost = j.optBoolean("warmRearDefrost", d.warmRearDefrost),
                    warmSteering = j.optBoolean("warmSteering", d.warmSteering),
                    warmSeat = j.optInt("warmSeat", d.warmSeat),
                    coolSeat = j.optInt("coolSeat", d.coolSeat),
                    seatsAll = j.optBoolean("seatsAll", d.seatsAll),
                    durationMin = j.optInt("durationMin", d.durationMin),
                    imageTrim = j.optString("imageTrim", d.imageTrim),
                    imageColor = j.optString("imageColor", d.imageColor),
                    walkEnabled = j.optBoolean("walkEnabled", d.walkEnabled),
                    walkDeviceAddress = j.optString("walkDeviceAddress", d.walkDeviceAddress),
                    walkDeviceName = j.optString("walkDeviceName", d.walkDeviceName),
                    walkDelaySec = j.optInt("walkDelaySec", d.walkDelaySec),
                    walkConfirm = j.optBoolean("walkConfirm", d.walkConfirm),
                    walkNotifyAlreadyLocked = j.optBoolean("walkNotifyAlreadyLocked", d.walkNotifyAlreadyLocked),
                )
            } catch (e: Exception) {
                Config()
            }
        }
    }
}

// ---------- Véhicule ----------

data class CarInfo(
    val id: String,
    val vin: String,
    val nickName: String,
    val modelName: String,
    val modelYear: String,
    val color: String,
    val trim: String,
) {
    val displayName: String
        get() = nickName.ifBlank { listOf(modelYear, modelName).filter { it.isNotBlank() }.joinToString(" ") }
            .ifBlank { "Mon véhicule" }

    fun toJson(): String = JSONObject()
        .put("id", id).put("vin", vin).put("nickName", nickName).put("modelName", modelName)
        .put("modelYear", modelYear).put("color", color).put("trim", trim).toString()

    companion object {
        fun fromJson(s: String): CarInfo? = try {
            val j = JSONObject(s)
            CarInfo(
                j.getString("id"), j.optString("vin"), j.optString("nickName"), j.optString("modelName"),
                j.optString("modelYear"), j.optString("color"), j.optString("trim")
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class CarStatus(
    val lastCheck: Long,
    val lastRemote: Long,
    val locked: Boolean,
    val climate: Boolean,
    val engine: Boolean,
    val fuel: Int?,
    val soc: Int?,
    val range: Int?,
    val odometer: Double?,
    val twelve: Int?,
    val charging: Boolean,
    val plugged: Boolean,
    val chargePowerKw: Double,
    val remainMin: Int,
    val supportsCharge: Boolean,
    val chargeAc: Int?,
    val chargeDc: Int?,
    val lat: Double?,
    val lon: Double?,
    val doorsOpen: Boolean,
    val trunkOpen: Boolean,
    val hoodOpen: Boolean,
) {
    fun toJson(): String = JSONObject()
        .put("lastCheck", lastCheck).put("lastRemote", lastRemote)
        .put("locked", locked).put("climate", climate).put("engine", engine)
        .put("fuel", fuel ?: JSONObject.NULL).put("soc", soc ?: JSONObject.NULL)
        .put("range", range ?: JSONObject.NULL).put("odometer", odometer ?: JSONObject.NULL)
        .put("twelve", twelve ?: JSONObject.NULL)
        .put("charging", charging).put("plugged", plugged).put("chargePowerKw", chargePowerKw)
        .put("remainMin", remainMin).put("supportsCharge", supportsCharge)
        .put("chargeAc", chargeAc ?: JSONObject.NULL).put("chargeDc", chargeDc ?: JSONObject.NULL)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("doorsOpen", doorsOpen).put("trunkOpen", trunkOpen).put("hoodOpen", hoodOpen)
        .toString()

    companion object {
        fun fromJson(s: String): CarStatus? = try {
            val j = JSONObject(s)
            CarStatus(
                lastCheck = j.optLong("lastCheck"),
                lastRemote = j.optLong("lastRemote"),
                locked = j.optBoolean("locked"),
                climate = j.optBoolean("climate"),
                engine = j.optBoolean("engine"),
                fuel = j.optIntOrNull("fuel"),
                soc = j.optIntOrNull("soc"),
                range = j.optIntOrNull("range"),
                odometer = j.optDoubleOrNull("odometer"),
                twelve = j.optIntOrNull("twelve"),
                charging = j.optBoolean("charging"),
                plugged = j.optBoolean("plugged"),
                chargePowerKw = j.optDouble("chargePowerKw", 0.0),
                remainMin = j.optInt("remainMin"),
                supportsCharge = j.optBoolean("supportsCharge"),
                chargeAc = j.optIntOrNull("chargeAc"),
                chargeDc = j.optIntOrNull("chargeDc"),
                lat = j.optDoubleOrNull("lat"),
                lon = j.optDoubleOrNull("lon"),
                doorsOpen = j.optBoolean("doorsOpen"),
                trunkOpen = j.optBoolean("trunkOpen"),
                hoodOpen = j.optBoolean("hoodOpen"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Demande de climatisation à distance. */
data class ClimateRequest(
    val temp: Double,
    val frontDefrost: Boolean,
    val rearDefrost: Boolean,
    val steering: Boolean,
    val durationMin: Int,
    val seatDriver: Int = 0,
    val seatPassenger: Int = 0,
    val seatRearLeft: Int = 0,
    val seatRearRight: Int = 0,
) {
    companion object {
        private val WARM_SEAT = intArrayOf(0, 6, 7, 8)   // chauffage bas / moyen / élevé
        private val COOL_SEAT = intArrayOf(0, 3, 4, 5)   // ventilation bas / moyen / élevé

        fun warm(c: Config): ClimateRequest {
            val s = WARM_SEAT[c.warmSeat.coerceIn(0, 3)]
            return ClimateRequest(
                c.warmTemp, c.warmFrontDefrost, c.warmRearDefrost, c.warmSteering, c.durationMin,
                s, s, if (c.seatsAll) s else 0, if (c.seatsAll) s else 0
            )
        }

        fun cool(c: Config): ClimateRequest {
            val s = COOL_SEAT[c.coolSeat.coerceIn(0, 3)]
            return ClimateRequest(
                c.coolTemp, false, false, false, c.durationMin,
                s, s, if (c.seatsAll) s else 0, if (c.seatsAll) s else 0
            )
        }
    }
}
