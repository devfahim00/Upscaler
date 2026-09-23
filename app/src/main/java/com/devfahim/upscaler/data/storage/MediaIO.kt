package com.devfahim.upscaler.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.devfahim.upscaler.domain.repository.VideoInfo
import com.devfahim.upscaler.domain.repository.VideoMetadataReader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Decodes photos (EXIF-rotation applied) and reads video metadata.
 *
 * Decode strategy: copy the content stream to a private temp file, decode
 * from path (uniform across API 26+), apply EXIF rotation, delete the temp.
 * Large inputs are not downsampled below [MAX_INPUT_DIMENSION] - the tiling
 * planner keeps processing memory bounded regardless of input size.
 */
@Singleton
class MediaIO @Inject constructor(
    private val context: Context,
) : VideoMetadataReader {

    fun decodeBitmap(uri: Uri, maxDimension: Int = MAX_INPUT_DIMENSION): Bitmap? {
        val temp = File(context.cacheDir, "decode_${System.currentTimeMillis()}")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Bound only extreme inputs (e.g. 100 MP panoramas).
            var sample = 1
            while (
                max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension &&
                bounds.outWidth / (sample * 2) > 0 &&
                bounds.outHeight / (sample * 2) > 0
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(temp.absolutePath, opts) ?: return null
            applyExifRotation(temp.absolutePath, raw)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun applyExifRotation(path: String, bmp: Bitmap): Bitmap = try {
        val exif = ExifInterface(path)
        when (
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bmp, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bmp, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bmp, 270f)
            else -> bmp
        }
    } catch (t: Throwable) {
        bmp
    }

    private fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Writes a small JPEG thumbnail for the Library grid. */
    fun writeThumbnail(source: Bitmap, target: File, edge: Int = 320): Boolean = try {
        val scale = edge.toFloat() / max(source.width, source.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else source
        small.compress(Bitmap.CompressFormat.JPEG, 85, target.outputStream())
    } catch (t: Throwable) {
        false
    }

    fun videoThumbnail(uri: Uri, target: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = r.frameAtTime ?: return false
            writeThumbnail(frame, target)
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { r.release() }
        }
    }

    override fun read(uri: String): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(uri))
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ROTATION)?.toIntOrNull() ?: 0
            val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_FRAMERATE) ?: "30").toFloatOrNull()
                ?: 30f
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/avc"
            val (dispW, dispH) = if (rotation == 90 || rotation == 270) h to w else w to h
            VideoInfo(
                width = dispW,
                height = dispH,
                rotationDegrees = rotation,
                fps = fps,
                durationMs = duration,
                hasAudio = hasAudio,
                videoMime = mime,
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        /** Hard ceiling on the decoded input edge (bounds extreme panoramas). */
        const val MAX_INPUT_DIMENSION: Int = 12_000
    }
}
