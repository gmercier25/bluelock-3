package ca.gmercier.bluelock

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage local chiffré.
 * Toutes les valeurs (identifiants, NIP, jetons, statut, réglages) sont chiffrées en
 * AES-256-GCM avec une clé qui ne quitte jamais l'Android Keystore du téléphone.
 * Rien n'est sauvegardé dans le nuage (allowBackup=false).
 */
object Store {
    private const val ALIAS = "bluelock_master_key"
    private const val IV_LEN = 12

    private lateinit var prefs: SharedPreferences
    @Volatile private var cachedKey: SecretKey? = null

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        synchronized(this) {
            if (!::prefs.isInitialized) {
                prefs = ctx.applicationContext.getSharedPreferences("bluelock_secure", Context.MODE_PRIVATE)
            }
        }
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(ALIAS, null) as? SecretKey
            val k = existing ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
            cachedKey = k
            return k
        }
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = try {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun get(k: String): String? = prefs.getString(k, null)?.let { decrypt(it) }

    @Synchronized
    fun put(k: String, v: String?) {
        val e = prefs.edit()
        if (v == null) e.remove(k) else e.putString(k, encrypt(v))
        e.commit()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().commit()
    }
}
