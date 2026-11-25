package com.nitrous.tessellate.settings

import android.content.Context
import android.util.Log
import com.nitrous.tessellate.save.WorldSaveManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Manages asynchronous persistent JSON storage of [GameSettings] in user-accessible external app storage
 * (`files/settings.json`), applying per-field defaults cleanly when individual values are missing or invalid.
 */
class SettingsManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    val settingsFile: File = resolveSettingsFile(context)

    private val ioExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Async settings operation failed safely", throwable)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + ioExceptionHandler)
    private val fileLock = Any()
    private val stateLock = Any()

    @Volatile
    var lastSaveJob: Job? = null
        private set

    @Volatile
    private var saveVersion: Int = 0

    @Volatile
    private var lastWrittenVersion: Int = 0

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
     * [settings] and [settingsFlow] on [Dispatchers.Main], falling back per-field to defaults
     * when missing or invalid.
     */
    fun loadSettings(onLoaded: ((GameSettings) -> Unit)? = null): Job {
        val loadStartVersion = synchronized(stateLock) { saveVersion }
        return scope.launch(ioDispatcher) {
            val loaded = readSettingsFromDisk()
            withContext(mainDispatcher) {
                val applied = synchronized(stateLock) {
                    if (saveVersion == loadStartVersion) {
                        settings = loaded
                        _settingsFlow.value = loaded
                        true
                    } else {
                        false
                    }
                }
                if (applied) {
                    onLoaded?.invoke(loaded)
                }
            }
        }
    }

    /**
     * Reads [GameSettings] synchronously from disk (useful on initial startup or tests).
     */
    fun loadSettingsBlocking(): GameSettings {
        val loaded = readSettingsFromDisk()
        synchronized(stateLock) {
            settings = loaded
            _settingsFlow.value = loaded
        }
        return loaded
    }

    /**
     * Writes [newSettings] to disk asynchronously via [ioDispatcher].
     */
    fun saveSettings(
        newSettings: GameSettings = settings,
        onComplete: ((Boolean) -> Unit)? = null
    ): Job {
        val sanitized = sanitize(newSettings)
        synchronized(stateLock) {
            saveVersion++
            settings = sanitized
            _settingsFlow.value = sanitized
        }
        val job = scope.launch(ioDispatcher) {
            val success = writeLatestSettingsToDisk()
            if (onComplete != null) {
                withContext(mainDispatcher) {
                    onComplete(success)
                }
            }
        }
        lastSaveJob = job
        return job
    }

    private fun writeLatestSettingsToDisk(): Boolean = synchronized(fileLock) {
        val (targetVersion, snapshot) = synchronized(stateLock) {
            saveVersion to settings
        }
        if (targetVersion <= lastWrittenVersion) {
            return true
        }
        val ok = writeSettingsToDisk(snapshot)
        if (ok) {
            synchronized(stateLock) {
                if (targetVersion > lastWrittenVersion) {
                    lastWrittenVersion = targetVersion
                }
            }
        }
        return ok
    }

    /**
     * Restores all settings to default values and persists immediately.
     */
    fun resetToDefaults(onComplete: ((Boolean) -> Unit)? = null): Job {
        return saveSettings(GameSettings(), onComplete)
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
            parseLenientPerField(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse settings.json; falling back to defaults.", e)
            GameSettings()
        }
    }

    /**
     * Parses each setting field independently so one bad/corrupted field falls back to its
     * own default without wiping other valid user settings.
     */
    private fun parseLenientPerField(json: String): GameSettings {
        val defaults = GameSettings()
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed JSON in settings.json; falling back to defaults.", e)
            return defaults
        }

        fun readInt(key: String, defaultVal: Int): Int {
            if (!obj.has(key) || obj.isNull(key)) return defaultVal
            val raw = obj.opt(key)
            return when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: defaultVal
                else -> defaultVal
            }
        }

        fun readFloat(key: String, defaultVal: Float): Float {
            if (!obj.has(key) || obj.isNull(key)) return defaultVal
            val raw = obj.opt(key)
            return when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull() ?: defaultVal
                else -> defaultVal
            }
        }

        fun readBoolean(key: String, defaultVal: Boolean): Boolean {
            if (!obj.has(key) || obj.isNull(key)) return defaultVal
            val raw = obj.opt(key)
            return when (raw) {
                is Boolean -> raw
                is String -> when (raw.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> defaultVal
                }
                else -> defaultVal
            }
        }

        val modMap = LinkedHashMap<String, String>()
        val modObj = obj.optJSONObject("modValues")
        if (modObj != null) {
            val keys = modObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!modObj.isNull(key)) {
                    modMap[key] = modObj.opt(key)?.toString() ?: ""
                }
            }
        }

        return sanitize(
            GameSettings(
                renderDistance = readInt("renderDistance", defaults.renderDistance),
                sensitivity = readFloat("sensitivity", defaults.sensitivity),
                fov = readFloat("fov", defaults.fov),
                soundVolume = readFloat("soundVolume", defaults.soundVolume),
                maxFps = readInt("maxFps", defaults.maxFps),
                viewBobbing = readBoolean("viewBobbing", defaults.viewBobbing),
                mipmapping = readBoolean("mipmapping", defaults.mipmapping),
                anisotropicFiltering = readBoolean("anisotropicFiltering", defaults.anisotropicFiltering),
                uiScale = readFloat("uiScale", defaults.uiScale),
                invertYAxis = readBoolean("invertYAxis", defaults.invertYAxis),
                autoJump = readBoolean("autoJump", defaults.autoJump),
                showCoordinates = readBoolean("showCoordinates", defaults.showCoordinates),
                enableDebugOptions = readBoolean("enableDebugOptions", defaults.enableDebugOptions),
                showDebugHud = readBoolean("showDebugHud", defaults.showDebugHud),
                showHitboxes = readBoolean("showHitboxes", defaults.showHitboxes),
                enableNanoTimeProfiler = readBoolean("enableNanoTimeProfiler", defaults.enableNanoTimeProfiler),
                useNativeMemoryPooling = readBoolean("useNativeMemoryPooling", defaults.useNativeMemoryPooling),
                parallelChunkMeshing = readBoolean("parallelChunkMeshing", defaults.parallelChunkMeshing),
                asyncMeshPrebuild = readBoolean("asyncMeshPrebuild", defaults.asyncMeshPrebuild),
                modValues = modMap
            )
        )
    }

    private fun writeSettingsToDisk(sanitized: GameSettings): Boolean = synchronized(fileLock) {
        return try {
            val parent = settingsFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val json = adapter.toJson(sanitized)
            val tmpFile = File(parent ?: settingsFile.absoluteFile.parentFile, "${settingsFile.name}.tmp")
            tmpFile.writeText(json)
            if (settingsFile.exists()) {
                if (!tmpFile.renameTo(settingsFile)) {
                    settingsFile.writeText(json)
                    tmpFile.delete()
                }
            } else if (!tmpFile.renameTo(settingsFile)) {
                settingsFile.writeText(json)
                tmpFile.delete()
            }
            Log.i(TAG, "Settings saved successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings.json.", e)
            false
        }
    }

    fun sanitize(candidate: GameSettings): GameSettings {
        val defaults = GameSettings()
        val safeRenderDistance = if (candidate.renderDistance in 1..16) {
            candidate.renderDistance
        } else {
            defaults.renderDistance
        }
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
        val safeMaxFps = if (candidate.maxFps in GameSettings.VALID_MAX_FPS) {
            candidate.maxFps
        } else {
            defaults.maxFps
        }
        val safeUiScale = if (candidate.uiScale.isFinite()) {
            candidate.uiScale.coerceIn(0.75f, 1.25f)
        } else {
            defaults.uiScale
        }
        return GameSettings(
            renderDistance = safeRenderDistance,
            sensitivity = safeSensitivity,
            fov = safeFov,
            soundVolume = safeVolume,
            maxFps = safeMaxFps,
            viewBobbing = candidate.viewBobbing,
            mipmapping = candidate.mipmapping,
            anisotropicFiltering = candidate.anisotropicFiltering,
            uiScale = safeUiScale,
            invertYAxis = candidate.invertYAxis,
            autoJump = candidate.autoJump,
            showCoordinates = candidate.showCoordinates,
            enableDebugOptions = candidate.enableDebugOptions,
            showDebugHud = candidate.showDebugHud,
            showHitboxes = candidate.showHitboxes,
            enableNanoTimeProfiler = candidate.enableNanoTimeProfiler,
            useNativeMemoryPooling = candidate.useNativeMemoryPooling,
            parallelChunkMeshing = candidate.parallelChunkMeshing,
            asyncMeshPrebuild = candidate.asyncMeshPrebuild,
            modValues = candidate.modValues.toMap()
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
