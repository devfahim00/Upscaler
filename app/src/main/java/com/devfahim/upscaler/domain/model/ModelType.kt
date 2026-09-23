package com.devfahim.upscaler.domain.model

import androidx.annotation.StringRes
import com.devfahim.upscaler.R

/**
 * The bundled super-resolution models.
 *
 * All of them are SRVGGNetCompact-family networks (the compact architecture
 * used by Real-ESRGAN's "general" / "animevideov3" fast models) exported to
 * ncnn `.param` + `.bin` format. Files live in
 * `app/src/main/assets/models/<assetDir>/model.{param,bin}`.
 *
 * `standInFor` documents where a listed use-case does not have a purpose
 * trained public weight yet and the closest available compact model is
 * bundled instead. Swapping in a purpose-trained weight later only requires
 * dropping new files into the asset folder - see README.md.
 */
enum class ModelType(
    val assetDir: String,
    val nativeScale: Int,
    val isVideoOptimized: Boolean,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val standInFor: String? = null,
) {
    /**
     * Real-ESRGAN "general x4v3" - official compact model for real photos.
     */
    GENERAL_PHOTO_X4(
        assetDir = "realesr-general-x4v3",
        nativeScale = 4,
        isVideoOptimized = false,
        displayNameRes = R.string.model_general_photo_x4_name,
        descriptionRes = R.string.model_general_photo_x4_desc,
    ),

    /**
     * STAND-IN: no dedicated "general photo x2" compact weight is published;
     * realesr-animevideov3-x2 is the closest fast 2x compact model.
     */
    GENERAL_PHOTO_X2(
        assetDir = "realesr-animevideov3-x2",
        nativeScale = 2,
        isVideoOptimized = false,
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
        isVideoOptimized = false,
        displayNameRes = R.string.model_anime_x4_name,
        descriptionRes = R.string.model_anime_x4_desc,
    ),

    /**
     * STAND-IN: a dedicated "denoise" compact weight is not published as an
     * ncnn export; the official general x4v3 model already removes mild JPEG
     * artifacts and is used here until a stronger denoise weight is trained
     * and converted.
     */
    DENOISE_X4(
        assetDir = "realesr-general-x4v3",
        nativeScale = 4,
        isVideoOptimized = false,
        displayNameRes = R.string.model_denoise_x4_name,
        descriptionRes = R.string.model_denoise_x4_desc,
        standInFor = "realesr-denoise-x4 (not yet published)",
    ),

    /**
     * Real-ESRGAN animevideov3 x2 - trained for per-frame video upscaling.
     */
    VIDEO_X2(
        assetDir = "realesr-animevideov3-x2",
        nativeScale = 2,
        isVideoOptimized = true,
        displayNameRes = R.string.model_video_x2_name,
        descriptionRes = R.string.model_video_x2_desc,
    ),

    /**
     * Real-ESRGAN animevideov3 x4 - trained for per-frame video upscaling.
     */
    VIDEO_X4(
        assetDir = "realesr-animevideov3-x4",
        nativeScale = 4,
        isVideoOptimized = true,
        displayNameRes = R.string.model_video_x4_name,
        descriptionRes = R.string.model_video_x4_desc,
    );

    companion object {
        /** Models offered when upscaling photos. */
        val photoModels: List<ModelType> =
            listOf(GENERAL_PHOTO_X4, GENERAL_PHOTO_X2, ANIME_ILLUSTRATION_X4, DENOISE_X4)

        /** Models offered when upscaling videos. */
        val videoModels: List<ModelType> = listOf(VIDEO_X2, VIDEO_X4)
    }
}

/** User-selectable output scale. */
enum class ScaleOption(val factor: Int) {
    X2(2),
    X4(4);
}
