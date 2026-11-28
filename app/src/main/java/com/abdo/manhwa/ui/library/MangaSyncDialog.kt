package com.abdo.manhwa.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abdo.manhwa.data.local.entity.MangaEntity
import com.abdo.manhwa.data.repository.normalizeTitleKey
import com.abdo.manhwa.download.DlStatus
import com.abdo.manhwa.download.DownloadCenter
import com.abdo.manhwa.download.SourceStore
import com.abdo.manhwa.download.SyncInfo
import kotlinx.coroutines.CancellationException

private enum class SyncPhase { AskUrl, Checking, Result, Error }

/**
 * نافذة «تنزيل الفصول» للمانهوا من المكتبة:
 * تتبّع رابط الموقع المحفوظ، تفحص صفحة المانهوا، تعرض عدد الفصول (الكلي / المحمّلة / غير المحمّلة)،
 * وتحمّل غير المحمّلة بنفس الترتيب (من الأقدم إلى الأحدث).
 */
@Composable
fun MangaSyncDialog(manga: MangaEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var source by remember { mutableStateOf(SourceStore.get(context, manga.title)) }
    var input by remember { mutableStateOf(source.orEmpty()) }
    var phase by remember { mutableStateOf(if (source == null) SyncPhase.AskUrl else SyncPhase.Checking) }
    var info by remember { mutableStateOf<SyncInfo?>(null) }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    val tasks by DownloadCenter.tasks.collectAsState()
    val key = normalizeTitleKey(manga.title)
    val mine = tasks.filter { normalizeTitleKey(it.mangaTitle) == key }
    val activeNow = mine.count {
        it.status == DlStatus.QUEUED || it.status == DlStatus.EXTRACTING || it.status == DlStatus.DOWNLOADING
    }
    val doneNow = mine.count { it.status == DlStatus.DONE }
    val failedNow = mine.count { it.status == DlStatus.FAILED }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // الفحص يُلغى تلقائياً عند إغلاق النافذة (الصفحة المخفية تُنظَّف)
    LaunchedEffect(source, attempt) {
        val url = source ?: return@LaunchedEffect
        phase = SyncPhase.Checking
        try {
            val result = DownloadCenter.checkManga(manga.title, url)
            SourceStore.put(context, manga.title, url)
            info = result
            phase = SyncPhase.Result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "تعذّر فحص الموقع"
            phase = SyncPhase.Error
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(manga.title, maxLines = 2) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (phase) {
                    SyncPhase.AskUrl -> {
                        Text("لم أعثر على موقع هذه المانهوا. الصق رابط صفحة المانهوا نفسها (التي فيها قائمة الفصول) وسأتذكره.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            placeholder = { Text("https://…") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SyncPhase.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Text("جارٍ فحص الموقع وجمع الفصول… قد يستغرق دقيقة")
                        }
                        source?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }

                    SyncPhase.Error -> {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        source?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }

                    SyncPhase.Result -> {
                        val r = info
                        if (r != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                StatBox("الكلي", r.total)
                                StatBox("المحمّلة", r.downloaded)
                                StatBox("غير المحمّلة", r.missing.size)
                            }
                            if (activeNow > 0 || doneNow > 0 || failedNow > 0) {
                                Spacer(Modifier.height(14.dp))
                                val total = mine.size.coerceAtLeast(1)
                                LinearProgressIndicator(
                                    progress = { doneNow.toFloat() / total },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "اكتمل $doneNow من ${mine.size}" +
                                        (if (activeNow > 0) " · جارٍ التحميل" else "") +
                                        (if (failedNow > 0) " · فشل $failedNow" else ""),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (r.missing.isEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text("كل الفصول محمّلة ✓", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { phase = SyncPhase.AskUrl }) { Text("تغيير رابط المصدر") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                SyncPhase.AskUrl -> TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val t = input.trim()
                        source = if (t.contains("://")) t else "https://$t"
                        attempt++
                    }
                ) { Text("فحص") }

                SyncPhase.Error -> TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }

                SyncPhase.Result -> {
                    val r = info
                    if (r != null && r.missing.isNotEmpty() && activeNow == 0) {
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            DownloadCenter.downloadMissing(manga.title, r)
                            Toast.makeText(context, "بدأ تحميل ${r.missing.size} فصل", Toast.LENGTH_SHORT).show()
                        }) { Text("تحميل غير المحمّلة (${r.missing.size})") }
                    }
                }

                SyncPhase.Checking -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
private fun StatBox(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
