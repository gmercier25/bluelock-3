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
            ACTION_LOCK -> CarCommandService.LOCK to "Verrouillage envoyé…"
            ACTION_WARM -> CarCommandService.WARM to "Chauffage envoyé…"
            ACTION_COOL -> CarCommandService.COOL to "Climatisation envoyée…"
            ACTION_CLIMATE_OFF -> CarCommandService.CLIMATE_OFF to "Arrêt de la climatisation envoyé…"
            else -> null to null
        }
        if (cmd != null) {
            if (!Config.load().hasCredentials()) {
                Toast.makeText(this, "Configure d'abord ton compte dans BlueLock", Toast.LENGTH_LONG).show()
            } else {
                try {
                    CarCommandService.start(this, cmd)
                    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Impossible : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }
}
