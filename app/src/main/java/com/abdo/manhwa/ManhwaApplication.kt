package com.abdo.manhwa

import android.app.Application
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.repository.MangaRepository

/**
 * فئة التطبيق: تهيّئ المستودع (Repository) ومخزن الإعدادات مرة واحدة فقط
 * وتُبقيهما متاحين لكل الشاشات (نمط بسيط لحقن الاعتماديات يدوياً بدون مكتبة خارجية).
 */
class ManhwaApplication : Application() {

    lateinit var repository: MangaRepository
        private set

    lateinit var settingsDataStore: SettingsDataStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MangaRepository(this)
        settingsDataStore = SettingsDataStore(this)
    }
}
