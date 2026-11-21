package com.dailypush.news.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences

/**
 * 敏感信息（API Key）存储。
 *
 * 用 androidx.security 的加密存储（1.0.0 用 masterKeyAlias 的写法）；
 * 个别机型/系统上加密库初始化失败时退回普通 SharedPreferences，
 * 至少保证 App 不会因为这个原因打不开。
 */
class SecretStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        createEncrypted() ?: appContext.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    private fun createEncrypted(): SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            ENCRYPTED_NAME,
            MASTER_KEY_ALIAS,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    companion object {
        private const val KEY_API_KEY = "ai_api_key"
        private const val ENCRYPTED_NAME = "daily_news_encrypted"
        private const val MASTER_KEY_ALIAS = "daily_news_master_key"
        private const val PLAIN_NAME = "daily_news_secret"
    }
}
