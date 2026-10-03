package com.wing.folderplayer.data.source

import androidx.core.content.edit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secret storage keyed by [SourceConfig.effectiveCredentialRef]. Secrets never appear in source JSON, URIs or logs. */
interface CredentialStore {
    fun get(ref: String): String?
    fun put(ref: String, secret: String)
    fun remove(ref: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(ref: String) = map[ref]
    override fun put(ref: String, secret: String) { map[ref] = secret }
    override fun remove(ref: String) { map.remove(ref) }
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Ciphertext is kept in its own SharedPreferences file,
 * which is excluded from backup/device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml) because the
 * key cannot leave the device anyway.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun get(ref: String): String? {
        val stored = prefs.getString(ref, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "credential for a source could not be decrypted (key reset?)")
            null
        }
    }

    @Synchronized
    override fun put(ref: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + ct
        prefs.edit { putString(ref, Base64.encodeToString(out, Base64.NO_WRAP)) }
    }

    @Synchronized
    override fun remove(ref: String) {
        prefs.edit { remove(ref) }
    }

    companion object {
        const val PREFS = "fp_credentials"
        private const val ALIAS = "folderplayer_source_credentials"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG = "CredentialStore"
    }
}
