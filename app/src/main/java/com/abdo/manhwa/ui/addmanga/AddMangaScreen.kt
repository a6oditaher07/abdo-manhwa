package com.abdo.manhwa.ui.addmanga

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abdo.manhwa.data.repository.PickedChapter
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.BackgroundDark
import com.abdo.manhwa.ui.theme.SurfaceVariantDark
import com.abdo.manhwa.util.resolveDisplayName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AddMangaScreen(
    viewModel: AddMangaViewModel,
    existingMangaId: Long?,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(existingMangaId) {
        if (existingMangaId != null) viewModel.initForExistingManga(existingMangaId)
    }

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) {
            snackbarHostState.showSnackbar("تم الحفظ بنجاح")
            onDone()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { scope.launch { snackbarHostState.showSnackbar(it) } }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onCoverPicked(it) }
    }

    val chaptersPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val named = uris.map { it to resolveDisplayName(context, it) }
            viewModel.onChaptersPicked(named)
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isAddingChaptersOnly) "تعديل / إضافة فصول" else "إضافة مانهوا جديدة") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = { Text("اسم المانهوا") },
                        enabled = !state.isAddingChaptersOnly,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChange,
                        label = { Text("نبذة عن المانهوا") },
                        enabled = !state.isAddingChaptersOnly,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (!state.isAddingChaptersOnly) {
                        Text("صورة الغلاف", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp, 110.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                if (state.coverUri != null || state.existingCoverPath != null) {
                                    AsyncImage(
                                        model = state.coverUri ?: state.existingCoverPath,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(SurfaceVariantDark, RoundedCornerShape(10.dp))
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedButton(onClick = { coverPicker.launch("image/*") }) {
                                Text("اختيار صورة المانهوا")
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Text("الفصول", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { chaptersPicker.launch(arrayOf("text/html")) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختيار الفصول (ملفات HTML)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "تم اختيار ${state.chapters.size} فصل" + if (state.chapters.isNotEmpty()) " — مرتّبة ترتيباً طبيعياً" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                itemsIndexed(state.chapters) { index, chapter ->
                    ChapterPickedRow(
                        index = index,
                        chapter = chapter,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.chapters.size - 1,
                        onMoveUp = { viewModel.moveChapter(index, index - 1) },
                        onMoveDown = { viewModel.moveChapter(index, index + 1) },
                        onRemove = { viewModel.removeChapter(chapter) },
                        onRename = { newTitle -> viewModel.renameChapter(index, newTitle) }
                    )
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(BackgroundDark)
                    .padding(16.dp)
            ) {
                if (state.isSaving) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        color = AccentPrimary
                    )
                    Text(
                        "جاري النسخ... ${(state.progress * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Button(
                    onClick = { viewModel.save() },
                    enabled = state.canSave && !state.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (state.isAddingChaptersOnly) "موافق" else "موافق — حفظ المانهوا")
                }
            }
        }
    }
}

@Composable
private fun ChapterPickedRow(
    index: Int,
    chapter: PickedChapter,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onRename: (String) -> Unit
) {
    var editing by remember(chapter) { mutableStateOf(false) }
    var editedTitle by remember(chapter) { mutableStateOf(chapter.title) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(SurfaceVariantDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${index + 1}", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(28.dp))

        if (editing) {
            OutlinedTextField(
                value = editedTitle,
                onValueChange = { editedTitle = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    IconButton(onClick = {
                        onRename(editedTitle)
                        editing = false
                    }) { Icon(Icons.Filled.Check, contentDescription = "حفظ") }
                }
            )
        } else {
            Column(modifier = Modifier.weight(1f).clickable { editing = true }) {
                Text(chapter.title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(chapter.displayName, color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
            }
        }

        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "تحريك لأعلى")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "تحريك لأسفل")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
        }
    }
}
