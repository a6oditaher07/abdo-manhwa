package com.abdo.manhwa.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.repository.MangaRepository

/** مصنع عام بسيط لإنشاء كل ViewModels مع تمرير المستودع والإعدادات دون مكتبة حقن اعتماديات */
class AppViewModelFactory(
    private val repository: MangaRepository,
    private val settingsDataStore: SettingsDataStore,
    private val creator: (MangaRepository, SettingsDataStore) -> ViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return creator(repository, settingsDataStore) as T
    }
}
