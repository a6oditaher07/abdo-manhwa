package com.abdo.manhwa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.MangaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DetailUiState(
    val manga: MangaEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false
)

class DetailViewModel(private val repository: MangaRepository) : ViewModel() {

    private val mangaIdFlow = MutableStateFlow(-1L)
    private val isDeleted = MutableStateFlow(false)

    val uiState: StateFlow<DetailUiState> = mangaIdFlow.flatMapLatest { id ->
        if (id <= 0) {
            flowOf(DetailUiState(isLoading = true))
        } else {
            combine(
                repository.observeManga(id),
                repository.observeChapters(id),
                isDeleted
            ) { manga, chapters, deleted ->
                DetailUiState(manga = manga, chapters = chapters, isLoading = false, isDeleted = deleted)
            }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), DetailUiState())

    fun init(id: Long) {
        if (mangaIdFlow.value != id) mangaIdFlow.value = id
    }

    fun deleteManga(manga: MangaEntity) {
        viewModelScope.launch {
            repository.deleteManga(manga)
            isDeleted.value = true
        }
    }

    fun markChapterRead(chapter: ChapterEntity) {
        viewModelScope.launch { repository.markChapterRead(chapter) }
    }
}
