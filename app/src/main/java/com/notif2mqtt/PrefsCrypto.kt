package com.notif2mqtt

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class PrefsCrypto(context: Context) {
    private val appContext = context.applicationContext

    private val aead: Aead by lazy {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(Aead::class.java)
    }

    fun encrypt(plainText: String): String {
        val cipherText = aead.encrypt(plainText.toByteArray(Charsets.UTF_8), EMPTY_ASSOCIATED_DATA)
        return VERSION_PREFIX + Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String? {
        if (!payload.startsWith(VERSION_PREFIX)) {
            return null
        }
        val data = payload.removePrefix(VERSION_PREFIX)
        val cipherText = Base64.decode(data, Base64.NO_WRAP)
        val plainText = aead.decrypt(cipherText, EMPTY_ASSOCIATED_DATA)
        return plainText.toString(Charsets.UTF_8)
    }

    companion object {
        private const val KEYSET_PREFS = "notif2mqtt_tink_prefs"
        private const val KEYSET_NAME = "notif2mqtt_tink_keyset"
        private const val MASTER_KEY_URI = "android-keystore://notif2mqtt_master_key"
        private const val VERSION_PREFIX = "v1:"
        private val EMPTY_ASSOCIATED_DATA = ByteArray(0)
    }
}
