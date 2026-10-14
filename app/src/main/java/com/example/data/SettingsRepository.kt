package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "screen_recorder_settings")

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    AUTO("Automática", 0, 0),
    HD_720P("720p (1280x720)", 1280, 720),
    FHD_1080P("1080p (1920x1080)", 1920, 1080),
    QHD_1440P("1440p (2560x1440)", 2560, 1440),
    UHD_4K("4K (3840x2160)", 3840, 2160)
}

enum class VideoFps(val value: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_45(45),
    FPS_60(60),
    FPS_90(90),
    FPS_120(120)
}

enum class VideoOrientation(val label: String) {
    AUTO("Automático"),
    PORTRAIT("Retrato"),
    LANDSCAPE("Paisagem")
}

enum class AudioSource(val label: String) {
    MIC("Microfone"),
    INTERNAL("Áudio Interno"),
    MIC_AND_INTERNAL("Áudio Interno + Microfone"),
    NONE("Sem Áudio")
}

enum class AppTheme(val label: String) {
    SYSTEM("Tema do Sistema"),
    LIGHT("Tema Claro"),
    DARK("Tema Escuro")
}

class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_RESOLUTION = stringPreferencesKey("video_resolution")
        private val KEY_FPS = intPreferencesKey("video_fps")
        private val KEY_BITRATE = intPreferencesKey("video_bitrate") // in bps, 0 for auto
        private val KEY_ORIENTATION = stringPreferencesKey("video_orientation")
        
        private val KEY_AUDIO_SOURCE = stringPreferencesKey("audio_source")
        
        private val KEY_SAVE_FOLDER = stringPreferencesKey("save_folder")
        private val KEY_THEME = stringPreferencesKey("app_theme")
    }

    val resolutionFlow: Flow<VideoResolution> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_RESOLUTION] ?: VideoResolution.AUTO.name
        try { VideoResolution.valueOf(name) } catch (e: Exception) { VideoResolution.AUTO }
    }

    val fpsFlow: Flow<VideoFps> = context.dataStore.data.map { prefs ->
        val value = prefs[KEY_FPS] ?: VideoFps.FPS_30.value
        VideoFps.values().find { it.value == value } ?: VideoFps.FPS_30
    }

    val bitrateFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BITRATE] ?: 0 // 0 means Automatic quality
    }

    val orientationFlow: Flow<VideoOrientation> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_ORIENTATION] ?: VideoOrientation.AUTO.name
        try { VideoOrientation.valueOf(name) } catch (e: Exception) { VideoOrientation.AUTO }
    }

    val audioSourceFlow: Flow<AudioSource> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_AUDIO_SOURCE] ?: AudioSource.MIC.name
        try { AudioSource.valueOf(name) } catch (e: Exception) { AudioSource.MIC }
    }

    val saveFolderFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAVE_FOLDER] ?: "Movies/GravadorDeTela"
    }

    val themeFlow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME] ?: AppTheme.SYSTEM.name
        try { AppTheme.valueOf(name) } catch (e: Exception) { AppTheme.SYSTEM }
    }

    suspend fun setResolution(resolution: VideoResolution) {
        context.dataStore.edit { prefs -> prefs[KEY_RESOLUTION] = resolution.name }
    }

    suspend fun setFps(fps: VideoFps) {
        context.dataStore.edit { prefs -> prefs[KEY_FPS] = fps.value }
    }

    suspend fun setBitrate(bitrateBps: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_BITRATE] = bitrateBps }
    }

    suspend fun setOrientation(orientation: VideoOrientation) {
        context.dataStore.edit { prefs -> prefs[KEY_ORIENTATION] = orientation.name }
    }

    suspend fun setAudioSource(source: AudioSource) {
        context.dataStore.edit { prefs -> prefs[KEY_AUDIO_SOURCE] = source.name }
    }

    suspend fun setSaveFolder(folder: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SAVE_FOLDER] = folder }
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = theme.name }
    }
}
