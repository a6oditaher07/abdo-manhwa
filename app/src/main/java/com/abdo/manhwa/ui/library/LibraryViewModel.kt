package com.abdo.manhwa.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.MangaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** يمثل بطاقة مانهوا جاهزة للعرض في شبكة المكتبة مع حالة القراءة المحسوبة */
data class LibraryItemUi(
    val manga: MangaEntity,
    val totalChapters: Int,
    val readChapters: Int,
    val continueChapterTitle: String?,
    val hasStartedReading: Boolean
)

class LibraryViewModel(private val repository: MangaRepository) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    private val libraryItems: Flow<List<LibraryItemUi>> = repository.observeLibrary().map { list ->
        list.map { manga ->
            val (total, read) = repository.chapterCounts(manga.id)
            val continueTitle = manga.lastReadChapterId?.let { repository.getChapter(it)?.title }
            LibraryItemUi(
                manga = manga,
                totalChapters = total,
                readChapters = read,
                continueChapterTitle = continueTitle,
                hasStartedReading = manga.lastReadChapterId != null
            )
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(libraryItems, searchQuery) { items, query ->
        val filtered = if (query.isBlank()) items else items.filter {
            it.manga.title.contains(query, ignoreCase = true)
        }
        LibraryUiState(items = filtered, isLoading = false, query = query, isEmpty = items.isEmpty())
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun deleteManga(manga: MangaEntity) {
        viewModelScope.launch { repository.deleteManga(manga) }
    }
}

data class LibraryUiState(
    val items: List<LibraryItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val query: String = "",
    val isEmpty: Boolean = false
)
