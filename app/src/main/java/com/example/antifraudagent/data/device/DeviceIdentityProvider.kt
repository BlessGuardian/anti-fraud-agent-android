package com.example.antifraudagent.data.device

import android.content.Context
import java.util.UUID

object DeviceIdentityProvider {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_USER_ID = "user_id"

    fun getUserId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }
}
