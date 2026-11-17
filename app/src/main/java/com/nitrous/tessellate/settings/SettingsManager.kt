package com.nitrous.tessellate.settings

import android.content.Context
import android.util.Log
import com.nitrous.tessellate.save.WorldSaveManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages asynchronous persistent JSON storage of [GameSettings] in user-accessible external app storage
 * (`files/settings.json`) using Kotlin Coroutines (Dispatchers.IO for disk I/O, Dispatchers.Main for state updates).
 */
class SettingsManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    val settingsFile: File = resolveSettingsFile(context)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val fileLock = Any()

    @Volatile
    var lastSaveJob: Job? = null
        private set

    @Volatile
    private var saveVersion: Int = 0

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(GameSettings::class.java).indent("  ")

    private val _settingsFlow = MutableStateFlow(GameSettings())
    val settingsFlow: StateFlow<GameSettings> = _settingsFlow.asStateFlow()

    @Volatile
    var settings: GameSettings = GameSettings()
        private set

    /**
     * Fetches [GameSettings] from disk asynchronously on [Dispatchers.IO] and updates
     * [settings] and [settingsFlow] on [Dispatchers.Main], falling back to defaults if
     * missing or corrupted.
     */
    fun loadSettings(onLoaded: ((GameSettings) -> Unit)? = null): Job {
        val loadStartVersion = saveVersion
        return scope.launch(ioDispatcher) {
            val loaded = readSettingsFromDisk()
            withContext(mainDispatcher) {
                if (saveVersion == loadStartVersion) {
                    settings = loaded
                    _settingsFlow.value = loaded
                    onLoaded?.invoke(loaded)
                }
            }
        }
    }

    /**
     * Writes [newSettings] to disk asynchronously via `CoroutineScope(Dispatchers.IO).launch`.
     */
    fun saveSettings(
        newSettings: GameSettings = settings,
        onComplete: ((Boolean) -> Unit)? = null
    ): Job {
        val sanitized = sanitize(newSettings)
        saveVersion++
        settings = sanitized
        _settingsFlow.value = sanitized
        val job = scope.launch(ioDispatcher) {
            val success = writeSettingsToDisk(sanitized)
            if (onComplete != null) {
                withContext(mainDispatcher) {
                    onComplete(success)
                }
            }
        }
        lastSaveJob = job
        return job
    }

    private fun readSettingsFromDisk(): GameSettings = synchronized(fileLock) {
        if (!settingsFile.exists() || !settingsFile.isFile) {
            return GameSettings()
        }

        return try {
            val json = settingsFile.readText()
            if (json.isBlank()) {
                return GameSettings()
            }
            val parsed = adapter.fromJson(json)
            if (parsed != null) sanitize(parsed) else GameSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse settings.json; falling back to defaults.", e)
            GameSettings()
        }
    }

    private fun writeSettingsToDisk(sanitized: GameSettings): Boolean = synchronized(fileLock) {
        return try {
            settingsFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val json = adapter.toJson(sanitized)
            settingsFile.writeText(json)
            Log.i(TAG, "Settings saved successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings.json.", e)
            false
        }
    }

    private fun sanitize(candidate: GameSettings): GameSettings {
        val defaults = GameSettings()
        val safeRenderDistance = candidate.renderDistance.coerceIn(1, 16)
        val safeSensitivity = if (candidate.sensitivity.isFinite()) {
            candidate.sensitivity.coerceIn(0.05f, 2.0f)
        } else {
            defaults.sensitivity
        }
        val safeFov = if (candidate.fov.isFinite()) {
            candidate.fov.coerceIn(30.0f, 110.0f)
        } else {
            defaults.fov
        }
        val safeVolume = if (candidate.soundVolume.isFinite()) {
            candidate.soundVolume.coerceIn(0.0f, 1.0f)
        } else {
            defaults.soundVolume
        }
        return GameSettings(
            renderDistance = safeRenderDistance,
            sensitivity = safeSensitivity,
            fov = safeFov,
            soundVolume = safeVolume
        )
    }

    companion object {
        const val TAG = "SettingsManager"
        const val SETTINGS_FILE_NAME = "settings.json"

        fun resolveSettingsFile(context: Context): File {
            return File(WorldSaveManager.getBaseFilesDir(context), SETTINGS_FILE_NAME)
        }
    }
}
