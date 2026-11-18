package com.abdo.manhwa.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * مسؤول عن نسخ صورة الغلاف وملفات الفصول (HTML) من مساحة المستخدم (عبر SAF)
 * إلى مساحة التطبيق الداخلية الخاصة، حتى تبقى متاحة دائماً دون الحاجة لأذونات تخزين إضافية.
 */
class FileStorageManager(private val context: Context) {

    private fun mangaRoot(): File {
        val dir = File(context.filesDir, "manhwa")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun mangaDir(mangaFolderName: String): File {
        val dir = File(mangaRoot(), mangaFolderName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** ينشئ اسم مجلد فريد للمانهوا بالاعتماد على معرّفها في قاعدة البيانات */
    fun folderNameFor(mangaId: Long): String = "manga_$mangaId"

    /**
     * ينسخ صورة الغلاف من Uri المختار إلى مجلد المانهوا الداخلي.
     * يُعيد المسار المطلق للملف المحفوظ أو null في حال الفشل.
     */
    fun copyCoverImage(mangaFolderName: String, sourceUri: Uri): String? {
        return try {
            val dir = mangaDir(mangaFolderName)
            val extension = guessExtension(sourceUri) ?: "jpg"
            val destFile = File(dir, "cover.$extension")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            destFile.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    /**
     * ينسخ مجموعة ملفات فصول HTML (قد تتضمن أيضاً صوراً مجاورة داخل نفس مجلد الفصل)
     * إلى مجلد "chapters" داخل مجلد المانهوا. يُعيد قائمة أزواج (اسم الملف الأصلي، المسار الجديد).
     */
    fun copyChapterFile(mangaFolderName: String, sourceUri: Uri, displayName: String): String? {
        return try {
            val chaptersDir = File(mangaDir(mangaFolderName), "chapters")
            if (!chaptersDir.exists()) chaptersDir.mkdirs()
            val safeName = sanitizeFileName(displayName)
            val destFile = File(chaptersDir, safeName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            destFile.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    fun deleteMangaFolder(mangaFolderName: String) {
        mangaDir(mangaFolderName).deleteRecursively()
    }

    fun fileExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return File(path).exists()
    }

    private fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/')
        return base.replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
    }

    private fun guessExtension(uri: Uri): String? {
        val type = context.contentResolver.getType(uri) ?: return null
        return when {
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            type.contains("jpeg") || type.contains("jpg") -> "jpg"
            else -> null
        }
    }
}
