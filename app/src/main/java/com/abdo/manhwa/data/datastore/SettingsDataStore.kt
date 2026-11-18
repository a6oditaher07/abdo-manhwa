package com.abdo.manhwa.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "abdo_manhwa_settings")

/**
 * يحفظ تفضيلات القارئ: السطوع المفضّل، تفعيل وضع راحة العين،
 * إبقاء الشاشة مضاءة، وقفل اتجاه الشاشة.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val EYE_COMFORT = booleanPreferencesKey("eye_comfort_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ORIENTATION_LOCK = intPreferencesKey("orientation_lock") // 0=حر, 1=عمودي, 2=أفقي
    }

    val brightness: Flow<Float> = context.dataStore.data.map { it[Keys.BRIGHTNESS] ?: -1f }
    val eyeComfort: Flow<Boolean> = context.dataStore.data.map { it[Keys.EYE_COMFORT] ?: false }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_SCREEN_ON] ?: true }
    val orientationLock: Flow<Int> = context.dataStore.data.map { it[Keys.ORIENTATION_LOCK] ?: 0 }

    suspend fun setBrightness(value: Float) {
        context.dataStore.edit { it[Keys.BRIGHTNESS] = value }
    }

    suspend fun setEyeComfort(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EYE_COMFORT] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setOrientationLock(value: Int) {
        context.dataStore.edit { it[Keys.ORIENTATION_LOCK] = value }
    }
}
