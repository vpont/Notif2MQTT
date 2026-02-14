package com.notif2mqtt

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.ConfigurationV0

class PrefsCrypto(context: Context) {
    private val appContext = context.applicationContext

    init {
        AeadConfig.register()
    }

    private val aead: Aead by lazy {
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(ConfigurationV0.get(), Aead::class.java)
    }

    fun encrypt(plainText: String): String {
        val cipherText = aead.encrypt(plainText.toByteArray(Charsets.UTF_8), EMPTY_ASSOCIATED_DATA)
        return VERSION_PREFIX + Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        require(payload.startsWith(VERSION_PREFIX)) { "Missing encryption prefix" }
        val data = payload.removePrefix(VERSION_PREFIX)
        val cipherText = Base64.decode(data, Base64.NO_WRAP)
        val plainText = aead.decrypt(cipherText, EMPTY_ASSOCIATED_DATA)
        return plainText.toString(Charsets.UTF_8)
    }

    fun isEncrypted(payload: String): Boolean {
        return payload.startsWith(VERSION_PREFIX)
    }

    companion object {
        private const val KEYSET_PREFS = "notif2mqtt_tink_prefs"
        private const val KEYSET_NAME = "notif2mqtt_tink_keyset"
        private const val MASTER_KEY_URI = "android-keystore://notif2mqtt_master_key"
        private const val VERSION_PREFIX = "v1:"
        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
