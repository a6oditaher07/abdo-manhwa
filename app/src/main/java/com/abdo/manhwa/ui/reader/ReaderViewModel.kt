package com.abdo.manhwa.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.MangaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** يمثل الفاصل الصغير الذي يظهر بين فصلين أثناء الانتقال التلقائي */
data class ChapterTransition(
    val elapsedSeconds: Long,
    val isLastChapter: Boolean
)

data class ReaderUiState(
    val manga: MangaEntity? = null,
    val chapter: ChapterEntity? = null,
    val isLoading: Boolean = true,
    val fileMissing: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val transition: ChapterTransition? = null,
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

    // وقت بداية قراءة الفصل الحالي، يُستخدم لحساب الوقت المعروض في الفاصل بين الفصول
    private var chapterStartTimeMs: Long = System.currentTimeMillis()

    fun loadChapter(mangaId: Long, chapterId: Long) {
        this.mangaId = mangaId
        chapterStartTimeMs = System.currentTimeMillis()
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, transition = null) }
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
                    hasPrevious = prev != null,
                    transition = null
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

    /** يُستدعى عند وصول القارئ لنهاية الفصل: يعلّمه كمقروء ويحسب الوقت المستغرق لعرضه في الفاصل */
    fun onReachedEnd() {
        val chapter = _state.value.chapter ?: return
        if (_state.value.transition != null) return // تجنّب التكرار
        val elapsedSeconds = ((System.currentTimeMillis() - chapterStartTimeMs) / 1000L).coerceAtLeast(1)
        viewModelScope.launch {
            repository.markChapterRead(chapter)
            _state.update {
                it.copy(transition = ChapterTransition(elapsedSeconds = elapsedSeconds, isLastChapter = !it.hasNext))
            }
        }
    }

    fun clearTransition() {
        _state.update { it.copy(transition = null) }
    }

    /** يبدّل حالة المفضّلة لهذه المانهوا (تحديث فوري في الواجهة + حفظ في قاعدة البيانات) */
    fun toggleFavorite() {
        val manga = _state.value.manga ?: return
        _state.update { it.copy(manga = manga.copy(isFavorite = !manga.isFavorite)) }
        viewModelScope.launch { repository.toggleFavorite(manga) }
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
