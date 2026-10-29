package ca.gmercier.bluelock

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Point d'entrée invisible pour les raccourcis (appui long sur l'icône),
 * la tuile des réglages rapides et Google Assistant / Routines.
 * Le déverrouillage n'est volontairement PAS ici : il passe par l'app avec confirmation.
 */
class ShortcutActivity : Activity() {

    companion object {
        const val ACTION_LOCK = "ca.gmercier.bluelock.SHORTCUT_LOCK"
        const val ACTION_WARM = "ca.gmercier.bluelock.SHORTCUT_WARM"
        const val ACTION_COOL = "ca.gmercier.bluelock.SHORTCUT_COOL"
        const val ACTION_CLIMATE_OFF = "ca.gmercier.bluelock.SHORTCUT_CLIMATE_OFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Car.init(this)
        val (cmd, label) = when (intent?.action) {
            ACTION_LOCK -> CarCommandService.LOCK to tr("Verrouillage envoyé…", "Lock sent…")
            ACTION_WARM -> CarCommandService.WARM to tr("Chauffage envoyé…", "Heat sent…")
            ACTION_COOL -> CarCommandService.COOL to tr("Climatisation envoyée…", "A/C sent…")
            ACTION_CLIMATE_OFF -> CarCommandService.CLIMATE_OFF to tr("Arrêt de la climatisation envoyé…", "Climate stop sent…")
            else -> null to null
        }
        if (cmd != null) {
            if (!Config.load().hasCredentials()) {
                Toast.makeText(this, tr("Configure d'abord ton compte dans BlueLock", "Set up your account in BlueLock first"), Toast.LENGTH_LONG).show()
            } else {
                try {
                    CarCommandService.start(this, cmd)
                    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, tr("Impossible : ", "Failed: ") + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }
}
