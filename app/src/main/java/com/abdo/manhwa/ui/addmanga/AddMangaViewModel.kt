package com.abdo.manhwa.ui.addmanga

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.repository.MangaRepository
import com.abdo.manhwa.data.repository.PickedChapter
import com.abdo.manhwa.util.NaturalOrderComparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddMangaUiState(
    val existingMangaId: Long? = null,
    val title: String = "",
    val description: String = "",
    val coverUri: Uri? = null,
    val existingCoverPath: String? = null,
    val chapters: List<PickedChapter> = emptyList(),
    val isSaving: Boolean = false,
    val progress: Float = 0f,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
) {
    val isAddingChaptersOnly: Boolean get() = existingMangaId != null
    val canSave: Boolean get() = title.isNotBlank() && chapters.isNotEmpty()
}

class AddMangaViewModel(private val repository: MangaRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddMangaUiState())
    val state: StateFlow<AddMangaUiState> = _state.asStateFlow()

    fun initForExistingManga(mangaId: Long) {
        viewModelScope.launch {
            val manga = repository.getManga(mangaId) ?: return@launch
            _state.update {
                it.copy(
                    existingMangaId = mangaId,
                    title = manga.title,
                    description = manga.description,
                    existingCoverPath = manga.coverPath
                )
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, errorMessage = null) }
    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }
    fun onCoverPicked(uri: Uri) = _state.update { it.copy(coverUri = uri) }

    /** يضيف الفصول المختارة حديثاً، يرتبها ترتيباً طبيعياً، ويدمجها مع ما هو موجود بدون تكرار */
    fun onChaptersPicked(newChapters: List<Pair<Uri, String>>) {
        val picked = newChapters.map { (uri, name) ->
            val number = NaturalOrderComparator.extractNumber(name)
            val title = if (number != null) "الفصل $number" else name.substringBeforeLast('.')
            PickedChapter(uri = uri, displayName = name, title = title)
        }
        val merged = (_state.value.chapters + picked)
            .distinctBy { it.displayName }
            .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
        _state.update { it.copy(chapters = merged, errorMessage = null) }
    }

    fun removeChapter(chapter: PickedChapter) {
        _state.update { it.copy(chapters = it.chapters - chapter) }
    }

    fun moveChapter(fromIndex: Int, toIndex: Int) {
        val list = _state.value.chapters.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _state.update { it.copy(chapters = list) }
    }

    fun renameChapter(index: Int, newTitle: String) {
        val list = _state.value.chapters.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(title = newTitle)
        _state.update { it.copy(chapters = list) }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) {
            _state.update { it.copy(errorMessage = "الرجاء إدخال اسم المانهوا واختيار فصل واحد على الأقل") }
            return
        }
        _state.update { it.copy(isSaving = true, progress = 0f, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (current.isAddingChaptersOnly && current.existingMangaId != null) {
                    repository.addChaptersToExisting(current.existingMangaId, current.chapters) { p ->
                        _state.update { it.copy(progress = p) }
                    }
                } else {
                    repository.createManga(
                        title = current.title.trim(),
                        description = current.description.trim(),
                        coverUri = current.coverUri,
                        chapters = current.chapters
                    ) { p -> _state.update { it.copy(progress = p) } }
                }
                _state.update { it.copy(isSaving = false, isSuccess = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, errorMessage = "حدث خطأ أثناء الحفظ: ${e.localizedMessage}") }
            }
        }
    }

    fun resetSuccess() = _state.update { it.copy(isSuccess = false) }
}
