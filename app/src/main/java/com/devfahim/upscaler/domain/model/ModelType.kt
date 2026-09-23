package com.devfahim.upscaler.domain.model

import androidx.annotation.StringRes
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.tiling.MemoryGuard

/**
 * The bundled super-resolution models.
 *
 * Files live in `app/src/main/assets/models/<assetDir>/model.{param,bin}`.
 * Two families are shipped:
 *
 *  * SRVGGNetCompact exports (the compact architecture used by Real-ESRGAN's
 *    "general" / "animevideov3" fast models) - these run smoothly on any
 *    Android device and are the default choice.
 *  * RealESRGAN_x4plus - the full RRDBNet "high quality" model. It recovers
 *    noticeably more real texture than the compact models (which can look
 *    over-smooth), at the cost of much longer processing. It runs with
 *    smaller tiles so it stays inside the memory budget on low-RAM devices.
 *
 * `standInFor` documents where a listed use-case does not have a
 * purpose-trained public weight yet and the closest available compact model
 * is bundled instead. Swapping in a purpose-trained weight later only
 * requires dropping new files into the asset folder - see README.md.
 */
enum class ModelType(
    val assetDir: String,
    val nativeScale: Int,
    val displayNameRes: Int,
    val descriptionRes: Int,
    /**
     * True when a WDN companion weight (realesr-general-wdn-x4v3) exists for
     * this model's architecture, enabling runtime weight interpolation
     * between the detail-oriented base and the denoise-oriented WDN variant.
     */
    val supportsWdnInterpolation: Boolean = false,
    /** Tile body edge (input px) when running this model on the CPU backend. */
    val cpuTileSize: Int = MemoryGuard.CPU_TILE_SIZE,
    /** Tile body edge (input px) when running this model on the GPU backend. */
    val gpuTileSize: Int = MemoryGuard.GPU_TILE_SIZE,
    val standInFor: String? = null,
) {
    /**
     * Real-ESRGAN "general x4v3" - official compact model for real photos.
     * Pairs with [supportsWdnInterpolation] so the user can dial in extra
     * denoising (0% = most texture, 100% = smoothest).
     */
    GENERAL_PHOTO_X4(
        assetDir = "realesr-general-x4v3",
        nativeScale = 4,
        displayNameRes = R.string.model_general_photo_x4_name,
        descriptionRes = R.string.model_general_photo_x4_desc,
        supportsWdnInterpolation = true,
    ),

    /**
     * RealESRGAN_x4plus - the original full-size RRDBNet model. Optional
     * "High Quality" mode: recovers real texture and fine detail the compact
     * models smooth away, but is much slower. Small tiles keep the working
     * set (~200 MB peak on GPU, less on CPU) safe on low-RAM devices.
     */
    HQ_PHOTO_X4(
        assetDir = "RealESRGAN_x4plus",
        nativeScale = 4,
        displayNameRes = R.string.model_hq_photo_x4_name,
        descriptionRes = R.string.model_hq_photo_x4_desc,
        cpuTileSize = MemoryGuard.HQ_CPU_TILE_SIZE,
        gpuTileSize = MemoryGuard.HQ_GPU_TILE_SIZE,
    ),

    /**
     * STAND-IN: no dedicated "general photo x2" compact weight is published;
     * realesr-animevideov3-x2 is the closest fast 2x compact model.
     */
    GENERAL_PHOTO_X2(
        assetDir = "realesr-animevideov3-x2",
        nativeScale = 2,
        displayNameRes = R.string.model_general_photo_x2_name,
        descriptionRes = R.string.model_general_photo_x2_desc,
        standInFor = "realesr-general-x2v3 (not yet published)",
    ),

    /**
     * Real-ESRGAN animevideov3 x4 - trained for anime / illustration / line
     * art; also works well on flat-color graphics and logos.
     */
    ANIME_ILLUSTRATION_X4(
        assetDir = "realesr-animevideov3-x4",
        nativeScale = 4,
        displayNameRes = R.string.model_anime_x4_name,
        descriptionRes = R.string.model_anime_x4_desc,
    );

    /** Tile body edge for this model on the given backend. */
    fun tileSizeFor(backendUsesGpu: Boolean): Int =
        if (backendUsesGpu) gpuTileSize else cpuTileSize

    companion object {
        /** Models offered when upscaling photos. */
        val photoModels: List<ModelType> =
            listOf(GENERAL_PHOTO_X4, HQ_PHOTO_X4, GENERAL_PHOTO_X2, ANIME_ILLUSTRATION_X4)

        /**
         * Asset dir of the WDN companion weight for [GENERAL_PHOTO_X4]
         * (realesr-general-wdn-x4v3, same architecture). Not user-selectable
         * as its own model - it is reached through the denoise-strength
         * slider, which blends it with the base model at runtime.
         */
        const val WDN_ASSET_DIR: String = "realesr-general-wdn-x4v3"
    }
}

/** User-selectable output scale. */
enum class ScaleOption(val factor: Int) {
    X2(2),
    X4(4);
}
