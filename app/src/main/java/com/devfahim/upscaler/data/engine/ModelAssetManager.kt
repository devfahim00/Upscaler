package com.devfahim.upscaler.data.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the ncnn models bundled in assets/models into filesDir on first
 * launch (ncnn needs filesystem paths, not asset streams) and hands out
 * per-model directories.
 *
 * Re-extraction is triggered by bumping [VERSION] - e.g. after replacing a
 * model weight with a better one. See README.md "Adding / replacing models".
 */
@Singleton
class ModelAssetManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val root: File
        get() = File(context.filesDir, "models")

    /** Asset folder that contains one directory per model. */
    private val assetRoot = "models"

    @Volatile
    private var prepared = false

    @Synchronized
    fun prepareIfNeeded() {
        if (prepared) return
        val marker = File(root, "v$VERSION.ok")
        if (marker.exists()) {
            prepared = true
            return
        }
        root.mkdirs()
        val listing = context.assets.list(assetRoot).orEmpty()
        for (modelDirName in listing) {
            val targetDir = File(root, modelDirName)
            val param = File(targetDir, "model.param")
            val bin = File(targetDir, "model.bin")
            if (param.exists() && bin.exists()) continue

            targetDir.mkdirs()
            context.assets.open("$assetRoot/$modelDirName/model.param").use { input ->
                param.outputStream().use { input.copyTo(it) }
            }
            context.assets.open("$assetRoot/$modelDirName/model.bin").use { input ->
                bin.outputStream().use { input.copyTo(it) }
            }
        }
        marker.writeText("models extracted at v$VERSION")
        prepared = true
    }

    /** Filesystem directory for a model (call after [prepareIfNeeded]). */
    fun modelDir(assetDir: String): File {
        prepareIfNeeded()
        val dir = File(root, assetDir)
        check(dir.resolve("model.param").exists() && dir.resolve("model.bin").exists()) {
            "Model '$assetDir' is not bundled in assets - see README.md"
        }
        return dir
    }

    companion object {
        /**
         * Bump when shipping new/updated weight files. Also used by
         * [WdnInterpolator] to key its interpolated-weight cache.
         */
        const val VERSION = 2
    }
}
