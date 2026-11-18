package com.abdo.manhwa.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.ui.common.AbdoBottomNavBar
import com.abdo.manhwa.ui.common.BottomTab
import com.abdo.manhwa.ui.library.components.EmptyLibraryState
import com.abdo.manhwa.ui.library.components.MangaCard
import com.abdo.manhwa.ui.theme.BackgroundDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDetail: (Long) -> Unit,
    onContinueReading: (Long, Long) -> Unit,
    onAddManga: () -> Unit,
    onEditManga: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var longPressedItem by remember { mutableStateOf<LibraryItemUi?>(null) }
    var confirmDeleteItem by remember { mutableStateOf<MangaEntity?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            AbdoBottomNavBar(selected = BottomTab.Library, onSelect = { tab ->
                if (tab == BottomTab.AddManga) onAddManga()
            })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            TopAppBar(
                title = { Text("مكتبتي", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("ابحث عن مانهوا...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.isEmpty -> EmptyLibraryState(onAddManga = onAddManga, modifier = Modifier.weight(1f))
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(uiState.items, key = { it.manga.id }) { item ->
                            MangaCard(
                                item = item,
                                onClick = { onOpenDetail(item.manga.id) },
                                onContinueClick = {
                                    val chapterId = item.manga.lastReadChapterId
                                    if (chapterId != null) {
                                        onContinueReading(item.manga.id, chapterId)
                                    } else {
                                        onOpenDetail(item.manga.id)
                                    }
                                },
                                onLongPress = { longPressedItem = item }
                            )
                        }
                    }
                }
            }
        }
    }

    longPressedItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { longPressedItem = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    item.manga.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                ListItem(
                    headlineContent = { Text("تعديل") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        onEditManga(item.manga.id)
                        longPressedItem = null
                    }
                )
                ListItem(
                    headlineContent = { Text("إضافة فصول") },
                    leadingContent = { Icon(Icons.Filled.PostAdd, contentDescription = null) },
                    modifier = Modifier.clickableRow {
                        onEditManga(item.manga.id)
                        longPressedItem = null
                    }
                )
                ListItem(
                    headlineContent = { Text("حذف المانهوا", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickableRow {
                        confirmDeleteItem = item.manga
                        longPressedItem = null
                    }
                )
            }
        }
    }

    confirmDeleteItem?.let { manga ->
        AlertDialog(
            onDismissRequest = { confirmDeleteItem = null },
            title = { Text("حذف \"${manga.title}\"؟") },
            text = { Text("سيتم حذف المانهوا وكل فصولها المحفوظة نهائياً.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteManga(manga)
                    confirmDeleteItem = null
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteItem = null }) { Text("إلغاء") }
            }
        )
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

