package ca.gmercier.bluelock

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Langue de l'app : « auto » (langue du téléphone), « fr » ou « en ».
 * Textes dans le code : tr("français", "English").
 * Textes des écrans XML : res/values (français) et res/values-en (anglais).
 */
object Lang {
    @Volatile private var cached: Boolean? = null

    fun reset() {
        cached = null
    }

    private fun systemIsFrench(): Boolean = try {
        Resources.getSystem().configuration.locales[0].language == "fr"
    } catch (_: Throwable) {
        true
    }

    val isEn: Boolean
        get() {
            cached?.let { return it }
            val v = try {
                when (Config.load().lang) {
                    "en" -> true
                    "fr" -> false
                    else -> !systemIsFrench()
                }
            } catch (_: Throwable) {
                !systemIsFrench()
            }
            cached = v
            return v
        }

    fun locale(): Locale = if (isEn) Locale.CANADA else Locale.CANADA_FRENCH

    /** Contexte dont les ressources sont dans la langue choisie (pour attachBaseContext). */
    fun wrap(base: Context): Context = try {
        Store.init(base.applicationContext ?: base)
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(locale())
        base.createConfigurationContext(conf)
    } catch (_: Throwable) {
        base
    }
}

/** Texte bilingue. */
fun tr(fr: String, en: String): String = if (Lang.isEn) en else fr
