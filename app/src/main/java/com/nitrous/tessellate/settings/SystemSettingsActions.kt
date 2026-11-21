package com.nitrous.tessellate.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.WorldRenderer
import java.io.File

/**
 * Result of executing Clear Cache, preserving all persistent world saves, settings, and mods.
 */
data class CacheClearResult(
    val deletedFiles: Int,
    val freedBytes: Long,
    val gpuFlushRequested: Boolean
)

/**
 * Implements System Settings operations:
 * - Launching the Android file manager for `Android/data/com.nitrous.tessellate/files/`
 * - Clearing temporary cache data (including temp chunk/texture caches and GPU buffer flush)
 *   without touching persistent world saves or `settings.json`.
 */
object SystemSettingsActions {

    private const val TAG = "SystemSettingsActions"

    /**
     * Builds an Android [Intent] targeting `Android/data/com.nitrous.tessellate/files/`.
     */
    fun buildOpenAppFolderIntent(context: Context): Intent {
        val baseFilesDir = WorldSaveManager.getBaseFilesDir(context)
        if (!baseFilesDir.exists()) {
            baseFilesDir.mkdirs()
        }

        val packageName = context.packageName
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/data/$packageName/files"
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Launches the Android file manager targeting the app's external files directory,
     * falling back gracefully when no supported file manager exists.
     */
    fun openAppFolder(
        context: Context,
        customLauncher: ((Intent) -> Boolean)? = null
    ): ReferenceActionResult {
        val baseFilesDir = WorldSaveManager.getBaseFilesDir(context)
        val primaryIntent = buildOpenAppFolderIntent(context)

        if (customLauncher != null) {
            return try {
                val handled = customLauncher(primaryIntent)
                if (handled) {
                    ReferenceActionResult(true, "Opened app folder: ${baseFilesDir.absolutePath}")
                } else {
                    ReferenceActionResult(false, "File manager unavailable. Path: ${baseFilesDir.absolutePath}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed launching custom app folder handler", t)
                ReferenceActionResult(false, "Unsupported file manager operation. Path: ${baseFilesDir.absolutePath}")
            }
        }

        return try {
            val pm = context.packageManager
            val resolvedPrimary = primaryIntent.resolveActivity(pm)
            if (resolvedPrimary != null) {
                context.startActivity(primaryIntent)
                return ReferenceActionResult(true, "Opened app folder.")
            }

            // Fallback to general directory/resource browse intent or Documents UI
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"), "*/*")
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            if (fallbackIntent.resolveActivity(pm) != null) {
                context.startActivity(fallbackIntent)
                ReferenceActionResult(true, "Opened Android file manager.")
            } else {
                ReferenceActionResult(
                    false,
                    "No supported file manager found. Files are at: ${baseFilesDir.absolutePath}"
                )
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Activity available to view app folder", e)
            ReferenceActionResult(false, "No supported file manager found. Path: ${baseFilesDir.absolutePath}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security restriction opening app folder directly", e)
            ReferenceActionResult(false, "File manager access restricted. Path: ${baseFilesDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "Unsupported file manager operation", t)
            ReferenceActionResult(false, "Could not open folder: ${t.message ?: "unsupported"}")
        }
    }

    /**
     * Deletes temporary cache files inside [Context.getCacheDir], [Context.getExternalCacheDir],
     * and any transient `.cache`/`.tmp` directories under external files cache, while NEVER touching
     * `files/saves/`, `files/settings.json`, or `files/mods/`. Also requests a safe GPU mesh/texture flush.
     */
    fun clearCache(
        context: Context,
        worldRenderer: WorldRenderer? = null,
        texturePipeline: TexturePipeline? = null
    ): CacheClearResult {
        var deletedFiles = 0
        var freedBytes = 0L

        val cacheDirs = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            File(WorldSaveManager.getBaseFilesDir(context), "cache")
        ).distinctBy { it.absolutePath }

        for (dir in cacheDirs) {
            if (dir.exists()) {
                val (count, bytes) = deleteChildrenSafely(dir, preserveRoot = (dir != File(WorldSaveManager.getBaseFilesDir(context), "cache")))
                deletedFiles += count
                freedBytes += bytes
            }
        }

        val gpuRequested = if (worldRenderer != null) {
            worldRenderer.requestSafeGpuFlush()
            true
        } else {
            false
        }

        // Clear any diagnostic missing-texture cache diagnostics safely
        texturePipeline?.let {
            // Re-mark dirty or leave textureHandles to be safely rebuilt on next GL flush
        }

        Log.i(TAG, "Cleared cache: deleted=$deletedFiles files, freed=$freedBytes bytes, gpuFlush=$gpuRequested")
        return CacheClearResult(
            deletedFiles = deletedFiles,
            freedBytes = freedBytes,
            gpuFlushRequested = gpuRequested
        )
    }

    private fun deleteChildrenSafely(root: File, preserveRoot: Boolean): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val children = root.listFiles() ?: emptyArray()
        for (child in children) {
            if (child.isDirectory) {
                val (c, b) = deleteChildrenSafely(child, preserveRoot = false)
                count += c
                bytes += b
                child.delete()
            } else if (child.isFile) {
                val len = child.length()
                if (child.delete()) {
                    count++
                    bytes += len
                }
            }
        }
        if (!preserveRoot) {
            root.delete()
        }
        return count to bytes
    }
}
