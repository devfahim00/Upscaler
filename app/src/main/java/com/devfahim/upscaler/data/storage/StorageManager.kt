package com.devfahim.upscaler.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.devfahim.upscaler.domain.model.OutputFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scoped-storage compliant saving + the app-private result file layout.
 *
 * Results are first written to `filesDir/results/<jobId>.<ext>` (crash-safe,
 * works without any permission, feeds the Library grid) and only copied
 * into MediaStore when the user taps "Save to Gallery".
 */
@Singleton
class StorageManager @Inject constructor(
    private val context: Context,
) {

    fun resultsDir(): File = File(context.filesDir, "results").apply { mkdirs() }
    fun thumbsDir(): File = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun resultFile(jobId: String, format: OutputFormat): File =
        File(resultsDir(), "$jobId.${format.fileExtension}")

    fun thumbFile(jobId: String): File = File(thumbsDir(), "$jobId.jpg")

    /** Recursively computed size of all app-private caches (display + clear). */
    fun cacheSizeBytes(): Long = dirSize(context.cacheDir)

    fun clearCache() {
        deleteContents(context.cacheDir)
        // Coil's disk cache lives under cache_dir/image_cache by default.
        deleteContents(File(context.cacheDir, "image_cache"))
    }

    /** Copies a finished result into MediaStore (Gallery). */
    fun saveToGallery(
        file: File,
        format: OutputFormat,
        displayName: String,
    ): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relativePath = "${MediaStore.Images.Media.RELATIVE_PATH}/Upscaler"
        val mime = format.mimeType

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    fun queryDisplayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "output"

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    private fun deleteContents(dir: File?) {
        dir?.listFiles()?.forEach { f ->
            if (f.isDirectory) deleteContents(f) else runCatching { f.delete() }
        }
    }
}
