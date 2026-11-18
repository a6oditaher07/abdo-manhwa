package com.abdo.manhwa.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.MangaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ReaderUiState(
    val manga: MangaEntity? = null,
    val chapter: ChapterEntity? = null,
    val isLoading: Boolean = true,
    val fileMissing: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val showNextChapterCard: Boolean = false,
    val controlsVisible: Boolean = true
)

class ReaderViewModel(
    private val repository: MangaRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val brightness = settingsDataStore.brightness
    val eyeComfort = settingsDataStore.eyeComfort
    val keepScreenOn = settingsDataStore.keepScreenOn
    val orientationLock = settingsDataStore.orientationLock

    private var mangaId: Long = -1

    fun loadChapter(mangaId: Long, chapterId: Long) {
        this.mangaId = mangaId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, showNextChapterCard = false) }
            val manga = repository.getManga(mangaId)
            val chapter = repository.getChapter(chapterId)
            if (chapter == null) {
                _state.update { it.copy(isLoading = false, fileMissing = true) }
                return@launch
            }
            val exists = repository.chapterFileExists(chapter.filePath)
            val next = repository.getNextChapter(mangaId, chapter.orderIndex)
            val prev = repository.getPreviousChapter(mangaId, chapter.orderIndex)
            _state.update {
                it.copy(
                    manga = manga,
                    chapter = chapter,
                    isLoading = false,
                    fileMissing = !exists,
                    hasNext = next != null,
                    hasPrevious = prev != null
                )
            }
        }
    }

    fun toggleControls() {
        _state.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun setControlsVisible(visible: Boolean) {
        _state.update { it.copy(controlsVisible = visible) }
    }

    fun onReachedEnd() {
        val chapter = _state.value.chapter ?: return
        viewModelScope.launch {
            repository.markChapterRead(chapter)
            _state.update { it.copy(showNextChapterCard = true) }
        }
    }

    fun saveProgress(scrollPercent: Float) {
        val chapter = _state.value.chapter ?: return
        if (mangaId <= 0) return
        viewModelScope.launch {
            repository.saveReadingProgress(mangaId, chapter, scrollPercent)
        }
    }

    suspend fun nextChapterId(): Long? {
        val chapter = _state.value.chapter ?: return null
        return repository.getNextChapter(mangaId, chapter.orderIndex)?.id
    }

    suspend fun previousChapterId(): Long? {
        val chapter = _state.value.chapter ?: return null
        return repository.getPreviousChapter(mangaId, chapter.orderIndex)?.id
    }

    suspend fun chaptersForQuickJump(): List<ChapterEntity> = repository.getChapters(mangaId)

    fun setBrightness(value: Float) = viewModelScope.launch { settingsDataStore.setBrightness(value) }
    fun setEyeComfort(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setEyeComfort(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setKeepScreenOn(enabled) }
    fun setOrientationLock(value: Int) = viewModelScope.launch { settingsDataStore.setOrientationLock(value) }
}
