package com.abdo.manhwa.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abdo.manhwa.data.local.entity.ChapterEntity
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.BackgroundDark
import com.abdo.manhwa.ui.theme.SurfaceVariantDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    mangaId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onEdit: () -> Unit,
    onAddChapters: () -> Unit
) {
    LaunchedEffect(mangaId) { viewModel.init(mangaId) }
    val state by viewModel.uiState.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    val manga = state.manga

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(manga?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "تعديل")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        if (manga == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (!state.isLoading) Text("تعذر العثور على المانهوا", color = Color.White) else CircularProgressIndicator(color = AccentPrimary)
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Row {
                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceVariantDark)
                    ) {
                        if (manga.coverPath != null) {
                            AsyncImage(
                                model = manga.coverPath,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(manga.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${state.chapters.size} فصل",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val readCount = state.chapters.count { it.isRead }
                        Text(
                            "تمت قراءة $readCount من ${state.chapters.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (manga.description.isNotBlank()) {
                    Text(manga.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val lastChapter = state.chapters.find { it.id == manga.lastReadChapterId }
                Button(
                    onClick = {
                        val target = lastChapter?.id ?: state.chapters.firstOrNull()?.id
                        if (target != null) onOpenChapter(target)
                    },
                    enabled = state.chapters.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (lastChapter != null) "مواصلة – ${lastChapter.title}" else "بداية القراءة")
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onAddChapters, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إضافة فصول جديدة")
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text("الفصول", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(state.chapters, key = { it.id }) { chapter ->
                ChapterRow(chapter = chapter, onClick = { onOpenChapter(chapter.id) })
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف \"${manga?.title}\"؟") },
            text = { Text("سيتم حذف المانهوا وكل فصولها المحفوظة نهائياً.") },
            confirmButton = {
                TextButton(onClick = {
                    manga?.let { viewModel.deleteManga(it) }
                    confirmDelete = false
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceVariantDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(chapter.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (chapter.isRead) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "مقروء", tint = AccentPrimary)
        } else if (chapter.scrollPercent > 0f) {
            Text("${(chapter.scrollPercent * 100).toInt()}%", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
