package com.devfahim.upscaler.data.engine

import android.content.Context
import com.devfahim.upscaler.domain.model.ModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the weight file for a WDN-interpolated session of
 * [ModelType.GENERAL_PHOTO_X4].
 *
 * Both the detail-oriented base weight (realesr-general-x4v3) and its WDN
 * companion (realesr-general-wdn-x4v3) share the same SRVGGNetCompact
 * architecture, so their weights can be blended linearly:
 *
 * ```
 * out = (1 - alpha) * general + alpha * wdn
 * ```
 *
 * Higher alpha removes more noise but recovers less real texture. The
 * blended files (~2.3 MB each) are cached per (asset version, alpha step)
 * under filesDir; only the newest alpha is kept to avoid hoarding disk.
 *
 * This mirrors the official Real-ESRGAN "trade-off" advice of interpolating
 * between realesr-general-x4v3 and realesr-general-wdn-x4v3.
 */
@Singleton
class WdnInterpolator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelAssets: ModelAssetManager,
) {

    /**
     * Weight file for the given alpha (already snapped to a cache step by
     * [WdnBlend.quantizeAlpha] by callers, or coarse here).
     *
     * @param alpha in [0,1]; 0 returns the base model's file, 1 the WDN
     *        model's file, values in between a cached blended file.
     */
    @Synchronized
    fun binFor(alpha: Float): File {
        val a = WdnBlend.quantizeAlpha(alpha)
        val generalDir = modelAssets.modelDir(ModelType.GENERAL_PHOTO_X4.assetDir)
        if (a <= 0f) return File(generalDir, "model.bin")
        val wdnDir = modelAssets.modelDir(ModelType.WDN_ASSET_DIR)
        if (a >= 1f) return File(wdnDir, "model.bin")

        val step = (a * 10).toInt() // 1..9
        val cacheDir = versionedCacheDir()
        val target = File(cacheDir, "model_wdn_$step.bin")
        if (target.isFile && target.length() > 0) return target

        cacheDir.mkdirs()
        // Prune stale alpha caches (and caches from older asset versions).
        context.filesDir.listFiles { f -> f.name.startsWith(CACHE_ROOT) }
            ?.filter { it != cacheDir }
            ?.forEach { dir -> dir.deleteRecursively() }
        cacheDir.listFiles()?.forEach { if (it != target) it.delete() }

        val specs = WdnBlend.parseBlobSpecs(File(generalDir, "model.param").readText())
        val blended = WdnBlend.blend(
            generalBin = File(generalDir, "model.bin").readBytes(),
            wdnBin = File(wdnDir, "model.bin").readBytes(),
            specs = specs,
            alpha = a,
        )
        val tmp = File(cacheDir, "model_wdn_$step.tmp")
        tmp.writeBytes(blended)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("could not persist the interpolated WDN model")
        }
        return target
    }

    private fun versionedCacheDir(): File =
        File(context.filesDir, "$CACHE_ROOT-v${ModelAssetManager.VERSION}")

    private companion object {
        const val CACHE_ROOT = "wdn-interp"
    }
}
