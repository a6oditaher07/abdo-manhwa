package com.abdo.manhwa.ui.reader

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdo.manhwa.data.datastore.SettingsDataStore
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.MangaRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReaderUiState(
    val manga: MangaEntity? = null,
    /** الفصل الظاهر فعلياً في منتصف الشاشة (يتغيّر أثناء التمرير المتصل) */
    val chapter: ChapterEntity? = null,
    /** الفصل الذي حُمّلت صفحته في الـ WebView (بداية السلسلة) */
    val startChapter: ChapterEntity? = null,
    /** يزيد عند كل تحميل جديد كامل (قفزة إلى فصل غير موجود في السلسلة الحالية) */
    val loadToken: Int = 0,
    val isLoading: Boolean = true,
    val fileMissing: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
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

    /**
     * نسخة محلية مرتبة من كل فصول المانهوا. تُحدَّث فوراً (قبل الكتابة في قاعدة البيانات)
     * حتى لا تُكتب نسخة قديمة من الفصل فوق نسخة أحدث عند التمرير السريع بين الفصول.
     */
    @Volatile
    private var chapterList: List<ChapterEntity> = emptyList()

    // آخر تقدّم لم يُحفظ بعد، وتوقيت آخر حفظ
    private var pendingProgress: Pair<Long, Float>? = null
    private var lastSaveMs: Long = 0L

    // يضمن تنفيذ عمليات الحفظ بنفس ترتيب إطلاقها
    private val saveMutex = Mutex()

    /** تحميل كامل: بداية القراءة، أو قفزة إلى فصل غير موجود في السلسلة المتصلة الحالية */
    fun loadChapter(mangaId: Long, chapterId: Long) {
        this.mangaId = mangaId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, fileMissing = false) }
            // انتظر انتهاء أي حفظ جارٍ قبل إعادة قراءة القائمة من قاعدة البيانات
            flushProgress()
            saveMutex.withLock { }

            val manga = repository.getManga(mangaId)
            val list = repository.getChapters(mangaId)
            chapterList = list
            val chapter = list.firstOrNull { it.id == chapterId } ?: repository.getChapter(chapterId)
            if (chapter == null) {
                _state.update { it.copy(isLoading = false, fileMissing = true) }
                return@launch
            }
            val exists = repository.chapterFileExists(chapter.filePath)
            _state.update {
                it.copy(
                    manga = manga,
                    chapter = chapter,
                    startChapter = if (exists) chapter else null,
                    loadToken = it.loadToken + 1,
                    isLoading = false,
                    fileMissing = !exists,
                    hasNext = nextOf(chapter.id) != null,
                    hasPrevious = prevOf(chapter.id) != null
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

    // ───────────────────────── القراءة المتصلة ─────────────────────────

    /** تغيّر الفصل الظاهر أثناء التمرير: نحدّث العنوان والأسهم من دون إعادة تحميل أي شيء */
    fun onChapterVisible(chapterId: Long) {
        val chapter = chapterById(chapterId) ?: return
        if (_state.value.chapter?.id == chapterId) return
        flushProgress() // احفظ تقدّم الفصل السابق قبل التبديل
        _state.update {
            it.copy(
                chapter = chapter,
                hasNext = nextOf(chapterId) != null,
                hasPrevious = prevOf(chapterId) != null
            )
        }
    }

    /** وصل القارئ إلى فاصل نهاية الفصل: يُعلَّم كمقروء */
    fun onChapterFinished(chapterId: Long) {
        val chapter = chapterById(chapterId) ?: return
        replaceInCache(chapter.copy(isRead = true, scrollPercent = 1f))
        launchSave { repository.markChapterRead(chapter) }
    }

    /** تقدّم القراءة داخل فصل معيّن (يصل باستمرار من الصفحة)، ويُحفظ كل بضع ثوانٍ */
    fun reportProgress(chapterId: Long, percent: Float) {
        pendingProgress = chapterId to percent
        if (SystemClock.elapsedRealtime() - lastSaveMs >= SAVE_INTERVAL_MS) flushProgress()
    }

    /** يحفظ آخر تقدّم معلّق فوراً (عند تغيّر الفصل، وعند الخروج، وبشكل دوري) */
    fun flushProgress() {
        val (id, percent) = pendingProgress ?: return
        pendingProgress = null
        lastSaveMs = SystemClock.elapsedRealtime()
        val chapter = chapterById(id) ?: return
        replaceInCache(
            chapter.copy(scrollPercent = percent, isRead = chapter.isRead || percent >= READ_THRESHOLD)
        )
        val mid = mangaId
        launchSave { repository.saveReadingProgress(mid, chapter, percent) }
    }

    /** أول فصل بعد [afterId] ملفه موجود فعلاً (يتخطى الفصول ذات الملفات المفقودة) */
    fun nextExistingChapter(afterId: Long): ChapterEntity? {
        val list = chapterList
        val index = list.indexOfFirst { it.id == afterId }
        if (index < 0) return null
        for (i in index + 1 until list.size) {
            if (repository.chapterFileExists(list[i].filePath)) return list[i]
        }
        return null
    }

    fun chapterPath(chapterId: Long): String? = chapterById(chapterId)?.filePath

    fun nextChapterId(): Long? {
        val current = _state.value.chapter ?: return null
        return nextOf(current.id)?.id
    }

    fun previousChapterId(): Long? {
        val current = _state.value.chapter ?: return null
        return prevOf(current.id)?.id
    }

    fun chaptersForQuickJump(): List<ChapterEntity> = chapterList

    // ───────────────────────── المفضّلة ─────────────────────────

    /** يبدّل حالة المفضّلة: تحديث فوري في الواجهة ثم حفظ في قاعدة البيانات */
    fun toggleFavorite() {
        val current = _state.value.manga ?: return
        val newValue = !current.isFavorite
        _state.update { it.copy(manga = current.copy(isFavorite = newValue)) }
        launchSave {
            // نقرأ أحدث نسخة من قاعدة البيانات حتى لا نكتب فوق "آخر قراءة" بقيم قديمة محفوظة في الواجهة
            val fresh = repository.getManga(current.id) ?: current
            // repository.toggleFavorite يعكس القيمة، فنمرّر عكس المطلوب ليصير الناتج newValue
            repository.toggleFavorite(fresh.copy(isFavorite = !newValue))
        }
    }

    // ───────────────────────── الإعدادات ─────────────────────────

    fun setBrightness(value: Float) = viewModelScope.launch { settingsDataStore.setBrightness(value) }
    fun setEyeComfort(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setEyeComfort(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settingsDataStore.setKeepScreenOn(enabled) }
    fun setOrientationLock(value: Int) = viewModelScope.launch { settingsDataStore.setOrientationLock(value) }

    // ───────────────────────── أدوات داخلية ─────────────────────────

    private fun chapterById(id: Long): ChapterEntity? = chapterList.firstOrNull { it.id == id }

    private fun nextOf(id: Long): ChapterEntity? {
        val list = chapterList
        val i = list.indexOfFirst { it.id == id }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }

    private fun prevOf(id: Long): ChapterEntity? {
        val list = chapterList
        val i = list.indexOfFirst { it.id == id }
        return if (i > 0) list[i - 1] else null
    }

    private fun replaceInCache(updated: ChapterEntity) {
        chapterList = chapterList.map { if (it.id == updated.id) updated else it }
    }

    /**
     * يطلق عملية حفظ متسلسلة. NonCancellable حتى لا يُلغى الحفظ الأخير
     * إذا أُغلقت الشاشة (وتم تدمير الـ ViewModel) في نفس اللحظة.
     */
    private fun launchSave(block: suspend () -> Unit) {
        viewModelScope.launch(NonCancellable) {
            saveMutex.withLock { block() }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 4000L
        const val READ_THRESHOLD = 0.97f
    }
}
