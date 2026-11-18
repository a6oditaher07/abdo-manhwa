package com.abdo.manhwa.ui.library.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.abdo.manhwa.ui.library.LibraryItemUi
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.DividerDark
import com.abdo.manhwa.ui.theme.SurfaceVariantDark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaCard(
    item: LibraryItemUi,
    onClick: () -> Unit,
    onContinueClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (item.totalChapters > 0) item.readChapters / item.totalChapters.toFloat() else 0f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceVariantDark)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2A2338))
        ) {
            if (item.manga.coverPath != null) {
                AsyncImage(
                    model = item.manga.coverPath,
                    contentDescription = item.manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AutoStories,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.align(Alignment.Center).size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.manga.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(6.dp))

        val statusText = if (item.hasStartedReading) {
            "مواصلة" + (item.continueChapterTitle?.let { " – $it" } ?: "")
        } else {
            "بداية القراءة"
        }

        Button(
            onClick = onContinueClick,
            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
            contentPadding = PaddingValues(vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(statusText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = AccentPrimary,
            trackColor = DividerDark
        )
    }
}
