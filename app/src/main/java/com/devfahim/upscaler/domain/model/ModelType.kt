package com.devfahim.upscaler.domain.model

import androidx.annotation.StringRes
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.tiling.MemoryGuard

/**
 * The bundled neural models.
 *
 * Files live in `app/src/main/assets/models/<assetDir>/model.{param,bin}`.
 * The shipped families:
 *
 *  * SRVGGNetCompact exports (the compact architecture used by Real-ESRGAN's
 *    "general" / "animevideov3" fast models) - these run smoothly on any
 *    Android device and are the default choice.
 *  * RealESRGAN_x4plus - the full RRDBNet "high quality" model. It recovers
 *    noticeably more real texture than the compact models (which can look
 *    over-smooth), at the cost of much longer processing. It runs with
 *    smaller tiles so it stays inside the memory budget on low-RAM devices.
 *  * 4x-ultrasharp - Kim2091's RRDBNet texture machine (same architecture
 *    class as RealESRGAN_x4plus, official ncnn fp16 export from the author).
 *  * 4x-purephoto-realplksr - asterixcool's RealPLSKR photo-restoration
 *    network (converted from the official PyTorch weights, fp16 storage;
 *    the channel-repeat tail is expressed as a fixed 1x1 identity conv).
 *  * 4x-clearrealityv1 - Kim2091's lightweight SPAN model shipped as full
 *    FP32 weights (the ~0.43M-parameter fused-inference net; converted from
 *    the official PyTorch weights).
 *  * HDRNet - the Zero-DCE++ enhancement-curve network, run as an optional
 *    per-model "HDR" pass before upscaling (see [HDR_NET]).
 *
 * All bundled models speak the same ncnn contract: input blob `data`,
 * output blob `output`, RGB channel order, values in [0, 1].
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
    ),

    /**
     * 4x-UltraSharp - Kim2091's ESRGAN-family model (full RRDBNet, 64nf /
     * 23nb - the same architecture class as [HQ_PHOTO_X4]). Reconstructs
     * heavy fine texture and works especially well on JPEG-compressed
     * photos. Official ncnn fp16 export; uses the small HQ tiles because
     * the memory profile is identical to RealESRGAN_x4plus.
     */
    ULTRASHARP_X4(
        assetDir = "4x-ultrasharp",
        nativeScale = 4,
        displayNameRes = R.string.model_ultrasharp_x4_name,
        descriptionRes = R.string.model_ultrasharp_x4_desc,
        cpuTileSize = MemoryGuard.HQ_CPU_TILE_SIZE,
        gpuTileSize = MemoryGuard.HQ_GPU_TILE_SIZE,
    ),

    /**
     * 4x-PurePhoto-RealPLSKR - asterixcool's RealPLSKR restoration model
     * (scale 4). Skilled at denoising and de-compressing photos - natural
     * faces, hair and fur. Mid-weight network (7.4M params, fp16 storage),
     * so it runs with mid-size tiles: lighter than RRDBNet per tile but
     * heavier than the compact SRVGG models.
     */
    PUREPHOTO_X4(
        assetDir = "4x-purephoto-realplksr",
        nativeScale = 4,
        displayNameRes = R.string.model_purephoto_x4_name,
        descriptionRes = R.string.model_purephoto_x4_desc,
        cpuTileSize = MemoryGuard.PLKSR_CPU_TILE_SIZE,
        gpuTileSize = MemoryGuard.PLKSR_GPU_TILE_SIZE,
    ),

    /**
     * 4x-ClearRealityV1 - Kim2091's lightweight SPAN model (48 features,
     * 6 blocks; the bundled ncnn file holds the fused-inference FP32
     * weights, ~0.43M parameters). Aimed at realistic imagery with a soft,
     * natural look and few artifacts; comfortably the fastest of the
     * full-featured models, so it uses the default compact tile sizes.
     */
    CLEARREALITY_X4(
        assetDir = "4x-clearrealityv1",
        nativeScale = 4,
        displayNameRes = R.string.model_clearreality_x4_name,
        descriptionRes = R.string.model_clearreality_x4_desc,
    ),

    /**
     * HDRNet - the optional HDR enhancement pass (Zero-DCE++ curve
     * network, converted to ncnn). Not an upscaler and not
     * user-selectable in the model picker: it is reached through the
     * per-model "HDR" toggle in the photo options sheet, which runs this
     * network on a 1/12 downscaled copy of the photo and then applies the
     * 8-iteration enhancement curve at full resolution (see HdrCurve).
     *
     * nativeScale = 1 (identity resolution); the tile sizes are unused
     * because the network runs untiled on the small copy.
     */
    HDR_NET(
        assetDir = "hdrnet",
        nativeScale = 1,
        displayNameRes = R.string.model_hdrnet_name,
        descriptionRes = R.string.model_hdrnet_desc,
    );

    /** Tile body edge for this model on the given backend. */
    fun tileSizeFor(backendUsesGpu: Boolean): Int =
        if (backendUsesGpu) gpuTileSize else cpuTileSize

    companion object {
        /** Models offered when upscaling photos. */
        val photoModels: List<ModelType> = listOf(
            GENERAL_PHOTO_X4,
            HQ_PHOTO_X4,
            GENERAL_PHOTO_X2,
            ANIME_ILLUSTRATION_X4,
            ULTRASHARP_X4,
            PUREPHOTO_X4,
            CLEARREALITY_X4,
        )

        /**
         * Asset dir of the WDN companion weight for [GENERAL_PHOTO_X4]
         * (realesr-general-wdn-x4v3, same architecture). Not user-selectable
         * as its own model - it is reached through the denoise-strength
         * slider, which blends it with the base model at runtime.
         */
        const val WDN_ASSET_DIR: String = "realesr-general-wdn-x4v3"
    }
}

/** User-selectable output scale. X1 = HDR-only mode (no upscaling). */
enum class ScaleOption(val factor: Int) {
    /** HDR-only: run just the HDRNet enhancement pass, no model upscaling. */
    X1(1),
    X2(2),
    X4(4);

    companion object {
        /** Scales offered as a *default* in Settings (HDR-only is a per-run choice). */
        val upscaleDefaults: List<ScaleOption> = listOf(X2, X4)
    }
}
