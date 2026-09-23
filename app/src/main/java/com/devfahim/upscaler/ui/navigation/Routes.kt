package com.devfahim.upscaler.ui.navigation

/** Route table - single source of truth for navigation. */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    const val PROCESSING_ARG = "jobId"
    const val PROCESSING = "processing/{$PROCESSING_ARG}"
    fun processing(jobId: String) = "processing/$jobId"

    const val RESULT_ARG = "jobId"
    const val RESULT = "result/{$RESULT_ARG}"
    fun result(jobId: String) = "result/$jobId"

    const val VIDEO_OPTIONS_ARG = "uri"
    const val VIDEO_OPTIONS = "videoOptions/{$VIDEO_OPTIONS_ARG}"
    fun videoOptions(encodedUri: String) = "videoOptions/$encodedUri"

    /** Bottom bar destinations (order matters). */
    val bottomBar = listOf(HOME, LIBRARY, SETTINGS)
}
