package com.example.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import com.example.util.DebugLogger
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Service
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

import com.example.ui.theme.MyApplicationTheme

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

class ScreenRecordingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "ScreenRecordingService"
        private const val CHANNEL_ID = "screen_recorder_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.example.action.START"
        private const val ACTION_STOP = "com.example.action.STOP"
        private const val ACTION_PAUSE = "com.example.action.PAUSE"
        private const val ACTION_RESUME = "com.example.action.RESUME"
        private const val ACTION_SHOW_OVERLAY = "com.example.action.SHOW_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.example.action.HIDE_OVERLAY"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA_INTENT = "extra_data_intent"

        private val _recordingState = MutableStateFlow(RecordingState.IDLE)
        val recordingState: StateFlow<RecordingState> = _recordingState

        private val _durationSeconds = MutableStateFlow(0)
        val durationSeconds: StateFlow<Int> = _durationSeconds

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pauseRecording(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeRecording(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun startOverlayService(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
            }
            context.startService(intent)
        }

        fun stopOverlayService(context: Context) {
            val intent = Intent(context, ScreenRecordingService::class.java).apply {
                action = ACTION_HIDE_OVERLAY
            }
            context.startService(intent)
        }
    }

    // SavedStateRegistryOwner implementation
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val serviceViewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private lateinit var settingsRepository: SettingsRepository
    private var tempVideoFile: File? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private var timerHandler: Handler? = null
    private var startTime = 0L
    private var elapsedBeforePause = 0L
    private var isTimerRunning = false

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        settingsRepository = SettingsRepository(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)
                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                // Start Foreground immediately with correct types on Android 10+ / 14+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    var fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasMicPermission) {
                            fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                    }
                    startForeground(NOTIFICATION_ID, createNotification(0), fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, createNotification(0))
                }
                
                startRecording(resultCode, dataIntent)
            }
            ACTION_STOP -> {
                stopRecording()
            }
            ACTION_PAUSE -> {
                pauseRecording()
            }
            ACTION_RESUME -> {
                resumeRecording()
            }
            ACTION_SHOW_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    showFloatingOverlay()
                }
            }
            ACTION_HIDE_OVERLAY -> {
                removeFloatingOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupMediaRecorder(width: Int, height: Int, fps: Int, bitrate: Int, hasAudio: Boolean) {
        val recorder = mediaRecorder ?: return
        
        if (hasAudio) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(fps)
        recorder.setVideoEncodingBitRate(bitrate)
        
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (hasAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
        }
        
        recorder.setOutputFile(tempVideoFile!!.absolutePath)

        // Register runtime error listener to gracefully handle encoder or writing failures
        recorder.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
            if (_recordingState.value != RecordingState.IDLE) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@ScreenRecordingService, "Gravação interrompida por erro do sistema", Toast.LENGTH_SHORT).show()
                    stopRecording()
                }
            }
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (_recordingState.value != RecordingState.IDLE) return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // IMPORTANT: On Android 14+ (UPSIDE_DOWN_CAKE), starting a foreground service is asynchronous.
                // We MUST give the system some time (1000ms is highly safe) to fully register the service as FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // BEFORE we make our single call to getMediaProjection. Calling it too early results in a SecurityException,
                // and calling it multiple times in a retry loop is extremely dangerous because the capture permission Intent
                // is a ONE-TIME token. Once consumed or failed, any subsequent projection session created from it will be
                // immediately stopped or invalid.
                DebugLogger.d(TAG, "Aguardando 1000ms para o sistema registrar o Foreground Service...")
                kotlinx.coroutines.delay(1000)

                try {
                    DebugLogger.d(TAG, "Obtendo MediaProjection do sistema...")
                    mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Erro ao obter MediaProjection do sistema", e)
                }

                if (mediaProjection == null) {
                    DebugLogger.e(TAG, "MediaProjection é nulo")
                    Toast.makeText(this@ScreenRecordingService, "Falha ao obter projeção de tela do sistema. Tente novamente.", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return@launch
                }

                // Register callback before starting capture (mandatory on Android 14+ / API level 34)
                // Store strong reference to the callback to prevent premature garbage collection stops
                mediaProjectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.d(TAG, "MediaProjection stopped from callback")
                        if (_recordingState.value != RecordingState.IDLE) {
                            stopRecording()
                        }
                    }
                }
                mediaProjection?.registerCallback(mediaProjectionCallback!!, Handler(Looper.getMainLooper()))

                // Fetch Settings in parallel to minimize any delays
                val resolutionDeferred = async { settingsRepository.resolutionFlow.first() }
                val fpsDeferred = async { settingsRepository.fpsFlow.first().value }
                val bitrateConfigDeferred = async { settingsRepository.bitrateFlow.first() }
                val audioSourceDeferred = async { settingsRepository.audioSourceFlow.first() }
                val orientationDeferred = async { settingsRepository.orientationFlow.first() }

                val resolution = resolutionDeferred.await()
                val fps = fpsDeferred.await()
                val bitrateConfig = bitrateConfigDeferred.await()
                val audioSource = audioSourceDeferred.await()
                val orientation = orientationDeferred.await()

                // Calculate screen dimensions
                val metrics = resources.displayMetrics
                screenDensity = metrics.densityDpi

                // Use device's screen size as baseline
                var rawWidth = metrics.widthPixels
                var rawHeight = metrics.heightPixels

                // Ensure dimensions are even numbers (MediaCodec requirement)
                if (rawWidth % 2 != 0) rawWidth--
                if (rawHeight % 2 != 0) rawHeight--

                if (resolution != VideoResolution.AUTO) {
                    // Match aspect ratio but enforce max dimensions of settings
                    val targetWidth = resolution.width
                    val targetHeight = resolution.height

                    val isDevicePortrait = rawHeight >= rawWidth
                    val isTargetPortrait = targetHeight >= targetWidth

                    val widthToUse: Int
                    val heightToUse: Int

                    if (isDevicePortrait == isTargetPortrait) {
                        widthToUse = targetWidth
                        heightToUse = targetHeight
                    } else {
                        widthToUse = targetHeight
                        heightToUse = targetWidth
                    }

                    // Scaled resolution keeping aspect ratio
                    val deviceAspectRatio = rawWidth.toFloat() / rawHeight.toFloat()
                    if (isDevicePortrait) {
                        screenWidth = widthToUse
                        screenHeight = (widthToUse / deviceAspectRatio).toInt()
                    } else {
                        screenHeight = heightToUse
                        screenWidth = (heightToUse * deviceAspectRatio).toInt()
                    }
                } else {
                    screenWidth = rawWidth
                    screenHeight = rawHeight
                }

                // Force orientation if configured
                if (orientation == VideoOrientation.PORTRAIT && screenWidth > screenHeight) {
                    val temp = screenWidth
                    screenWidth = screenHeight
                    screenHeight = temp
                } else if (orientation == VideoOrientation.LANDSCAPE && screenWidth < screenHeight) {
                    val temp = screenWidth
                    screenWidth = screenHeight
                    screenHeight = temp
                }

                // Ensure divisible by 16 for best compatibility/compression
                screenWidth = (screenWidth / 16) * 16
                screenHeight = (screenHeight / 16) * 16

                val bitrate = if (bitrateConfig > 0) {
                    bitrateConfig
                } else {
                    // Auto Bitrate calculation
                    val pixelCount = screenWidth * screenHeight
                    when {
                        pixelCount >= 3840 * 2160 -> 15_000_000 // 4K -> 15 Mbps
                        pixelCount >= 2560 * 1440 -> 10_000_000 // 1440p -> 10 Mbps
                        pixelCount >= 1920 * 1080 -> 6_000_000  // 1080p -> 6 Mbps
                        pixelCount >= 1280 * 720 -> 3_500_000   // 720p -> 3.5 Mbps
                        else -> 1_500_000                       // Auto low resolution
                    }
                }

                // 1. Prepare temporary storage
                tempVideoFile = File(cacheDir, "temp_recording_${System.currentTimeMillis()}.mp4")

                // 2. Setup MediaRecorder with strict error-handling and fallback to safe parameters
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@ScreenRecordingService)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                // Verify microphone permission actually granted before attempting audio recording
                val hasPermissionForAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@ScreenRecordingService,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val hasAudio = audioSource != AudioSource.NONE && hasPermissionForAudio

                try {
                    // Attempt configuring and preparing with chosen settings
                    setupMediaRecorder(screenWidth, screenHeight, fps, bitrate, hasAudio)
                    mediaRecorder?.prepare()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to prepare MediaRecorder with selected settings. Falling back to Safe Mode...", e)
                    
                    // Release previous recorder and recreate
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                    mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(this@ScreenRecordingService)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }

                    // Define Safe Mode parameters: 720p (or lower depending on screen), 30fps, 2.5Mbps, optionally silent if audio setup fails
                    val safeMetrics = resources.displayMetrics
                    val safeDeviceAspectRatio = safeMetrics.widthPixels.toFloat() / safeMetrics.heightPixels.toFloat()
                    var safeWidth = minOf(720, safeMetrics.widthPixels)
                    var safeHeight = (safeWidth / safeDeviceAspectRatio).toInt()
                    safeWidth = (safeWidth / 16) * 16
                    safeHeight = (safeHeight / 16) * 16

                    try {
                        setupMediaRecorder(safeWidth, safeHeight, 30, 2_500_000, hasAudio)
                        mediaRecorder?.prepare()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Safe Mode with audio failed. Retrying silent 720p Safe Mode...", e2)
                        
                        try {
                            mediaRecorder?.reset()
                            mediaRecorder?.release()
                            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                MediaRecorder(this@ScreenRecordingService)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaRecorder()
                            }
                            
                            setupMediaRecorder(safeWidth, safeHeight, 30, 2_000_000, false)
                            mediaRecorder?.prepare()
                        } catch (e3: Exception) {
                            Log.e(TAG, "720p silent Safe Mode failed. Retrying absolute lowest 480p Safe Mode...", e3)
                            
                            mediaRecorder?.reset()
                            mediaRecorder?.release()
                            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                MediaRecorder(this@ScreenRecordingService)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaRecorder()
                            }
                            
                            var ultraSafeWidth = minOf(480, safeMetrics.widthPixels)
                            var ultraSafeHeight = (ultraSafeWidth / safeDeviceAspectRatio).toInt()
                            ultraSafeWidth = (ultraSafeWidth / 16) * 16
                            ultraSafeHeight = (ultraSafeHeight / 16) * 16
                            
                            setupMediaRecorder(ultraSafeWidth, ultraSafeHeight, 24, 1_000_000, false)
                            mediaRecorder?.prepare()
                        }
                    }
                }

                // 4. Create VirtualDisplay using the prepared surface
                val surface = mediaRecorder?.surface
                DebugLogger.d(TAG, "Criando VirtualDisplay ($screenWidth x $screenHeight, density $screenDensity)...")
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenRecorder",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                DebugLogger.d(TAG, "VirtualDisplay criado com sucesso: $virtualDisplay")

                // 5. Start MediaRecorder
                try {
                    DebugLogger.d(TAG, "Iniciando MediaRecorder...")
                    mediaRecorder?.start()
                    DebugLogger.i(TAG, "MediaRecorder iniciado com sucesso!")
                } catch (startEx: Exception) {
                    Log.e(TAG, "Failed to start MediaRecorder: ${startEx.message}", startEx)
                    DebugLogger.e(TAG, "Erro ao iniciar MediaRecorder", startEx)
                    throw RuntimeException("Falha na inicialização do encoder do sistema: ${startEx.localizedMessage}")
                }

                // Update States
                _recordingState.value = RecordingState.RECORDING
                startTime = System.currentTimeMillis()
                elapsedBeforePause = 0L
                _durationSeconds.value = 0
                startTimer()

                // Show floating overlay if permitted
                if (Settings.canDrawOverlays(this@ScreenRecordingService)) {
                    showFloatingOverlay()
                }

                updateNotification(0)
                Toast.makeText(this@ScreenRecordingService, "Gravação iniciada!", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording: ${e.message}", e)
                DebugLogger.e(TAG, "Falha crítica ao iniciar gravação de tela", e)
                Toast.makeText(this@ScreenRecordingService, "Erro ao iniciar gravação: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                
                // Complete cleanup of resources
                try {
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                } catch (ex: Exception) { Log.e(TAG, "Error cleaning up recorder: ${ex.message}") }
                mediaRecorder = null
                
                try {
                    virtualDisplay?.release()
                } catch (ex: Exception) { Log.e(TAG, "Error releasing display: ${ex.message}") }
                virtualDisplay = null
                
                mediaProjectionCallback?.let {
                    try {
                        mediaProjection?.unregisterCallback(it)
                    } catch (ex: Exception) { Log.e(TAG, "Error unregistering callback: ${ex.message}") }
                    mediaProjectionCallback = null
                }
                
                try {
                    mediaProjection?.stop()
                } catch (ex: Exception) { Log.e(TAG, "Error stopping projection: ${ex.message}") }
                mediaProjection = null
                
                _recordingState.value = RecordingState.IDLE
                stopSelf()
            }
        }
    }

    private fun pauseRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                _recordingState.value = RecordingState.PAUSED
                pauseTimer()
                updateNotification(_durationSeconds.value)
                Toast.makeText(this, "Gravação pausada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing recording: ${e.message}")
            }
        } else {
            Toast.makeText(this, "Pausa não suportada neste dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeRecording() {
        if (_recordingState.value != RecordingState.PAUSED) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                _recordingState.value = RecordingState.RECORDING
                resumeTimer()
                updateNotification(_durationSeconds.value)
                Toast.makeText(this, "Gravação retomada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming recording: ${e.message}")
            }
        }
    }

    private fun stopRecording() {
        if (_recordingState.value == RecordingState.IDLE) {
            stopSelf()
            return
        }

        stopTimer()
        removeFloatingOverlay()

        val saveFile = tempVideoFile
        val recordingDurationSec = _durationSeconds.value

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder: ${e.message}")
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        }

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjectionCallback?.let {
            try {
                mediaProjection?.unregisterCallback(it)
            } catch (ex: Exception) { Log.e(TAG, "Error unregistering callback: ${ex.message}") }
            mediaProjectionCallback = null
        }

        mediaProjection?.stop()
        mediaProjection = null

        _recordingState.value = RecordingState.IDLE
        _durationSeconds.value = 0

        if (saveFile != null && saveFile.exists() && saveFile.length() > 0) {
            // Save output to Public Movies folder via MediaStore
            CoroutineScope(Dispatchers.IO).launch {
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val timestampStr = dateFormat.format(Date())
                val displayName = "Gravacao_$timestampStr"
                
                val uri = saveVideoToGallery(this@ScreenRecordingService, saveFile, displayName)
                saveFile.delete() // Clean cache

                launch(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(this@ScreenRecordingService, "Vídeo salvo com sucesso!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@ScreenRecordingService, "Erro ao salvar vídeo na galeria", Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                }
            }
        } else {
            Toast.makeText(this, "Gravação muito curta ou vazia", Toast.LENGTH_SHORT).show()
            saveFile?.delete()
            stopSelf()
        }
    }

    private fun saveVideoToGallery(context: Context, cacheFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GravadorDeTela")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoUri = resolver.insert(collectionUri, contentValues) ?: return null

        try {
            resolver.openOutputStream(videoUri)?.use { outStream ->
                cacheFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
            }
            return videoUri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video file to MediaStore: ${e.message}", e)
            resolver.delete(videoUri, null, null)
            return null
        }
    }

    // --- Timer Handling ---
    private fun startTimer() {
        startTime = System.currentTimeMillis()
        elapsedBeforePause = 0L
        isTimerRunning = true
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable.run()
    }

    private fun pauseTimer() {
        isTimerRunning = false
        elapsedBeforePause += System.currentTimeMillis() - startTime
        timerHandler?.removeCallbacks(timerRunnable)
    }

    private fun resumeTimer() {
        startTime = System.currentTimeMillis()
        isTimerRunning = true
        timerRunnable.run()
    }

    private fun stopTimer() {
        isTimerRunning = false
        timerHandler?.removeCallbacks(timerRunnable)
        timerHandler = null
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isTimerRunning) return
            val elapsedMs = elapsedBeforePause + (System.currentTimeMillis() - startTime)
            val elapsedSec = (elapsedMs / 1000).toInt()
            _durationSeconds.value = elapsedSec
            updateNotification(elapsedSec)
            timerHandler?.postDelayed(this, 1000)
        }
    }

    // --- Foreground Notification ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serviço de Gravação de Tela",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações do Gravador de Tela durante a gravação"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun createNotification(seconds: Int): Notification {
        val formattedTime = formatTime(seconds)
        
        val titleText = when (_recordingState.value) {
            RecordingState.RECORDING -> {
                "Gravador de Tela • $formattedTime"
            }
            RecordingState.PAUSED -> {
                "Gravador de Tela • Pausado ($formattedTime)"
            }
            else -> {
                "Gravador de Tela"
            }
        }

        // Action intents
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Custom Notification RemoteViews
        val customLayout = android.widget.RemoteViews(packageName, com.example.R.layout.custom_notification_recording)
        customLayout.setTextViewText(com.example.R.id.notification_title, titleText)
        customLayout.setImageViewResource(com.example.R.id.notification_logo, com.example.R.drawable.ic_notification_camera)

        // Bind Stop Action
        customLayout.setOnClickPendingIntent(com.example.R.id.btn_notification_stop, stopIntent)

        // Bind Pause / Resume Action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (_recordingState.value == RecordingState.RECORDING) {
                val pauseIntent = PendingIntent.getService(
                    this, 2,
                    Intent(this, ScreenRecordingService::class.java).apply { action = ACTION_PAUSE },
                    PendingIntent.FLAG_IMMUTABLE
                )
                customLayout.setImageViewResource(com.example.R.id.iv_notification_pause_icon, com.example.R.drawable.ic_notification_pause)
                customLayout.setTextViewText(com.example.R.id.tv_notification_pause_text, "Pausa")
                customLayout.setOnClickPendingIntent(com.example.R.id.btn_notification_pause, pauseIntent)
                customLayout.setViewVisibility(com.example.R.id.btn_notification_pause, android.view.View.VISIBLE)
            } else if (_recordingState.value == RecordingState.PAUSED) {
                val resumeIntent = PendingIntent.getService(
                    this, 3,
                    Intent(this, ScreenRecordingService::class.java).apply { action = ACTION_RESUME },
                    PendingIntent.FLAG_IMMUTABLE
                )
                customLayout.setImageViewResource(com.example.R.id.iv_notification_pause_icon, com.example.R.drawable.ic_notification_play)
                customLayout.setTextViewText(com.example.R.id.tv_notification_pause_text, "Retomar")
                customLayout.setOnClickPendingIntent(com.example.R.id.btn_notification_pause, resumeIntent)
                customLayout.setViewVisibility(com.example.R.id.btn_notification_pause, android.view.View.VISIBLE)
            } else {
                customLayout.setViewVisibility(com.example.R.id.btn_notification_pause, android.view.View.GONE)
            }
        } else {
            customLayout.setViewVisibility(com.example.R.id.btn_notification_pause, android.view.View.GONE)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_notification_video)
            .setColor(0xFF0061A4.toInt()) // Matches BluePrimaryLight
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCustomContentView(customLayout)
            .setCustomBigContentView(customLayout)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())

        return builder.build()
    }

    private fun updateNotification(seconds: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(seconds))
    }

    // --- Floating Overlay Window Manager ---
    private fun showFloatingOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            
            // Set required tree owners for Compose rendering in Service
            setViewTreeLifecycleOwner(this@ScreenRecordingService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = this@ScreenRecordingService.serviceViewModelStore
            })
            setViewTreeSavedStateRegistryOwner(this@ScreenRecordingService)

            setContent {
                MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                    val recordingStateVal by _recordingState.collectAsState()
                    val durationSecVal by _durationSeconds.collectAsState()
                    
                    var isExpanded by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .wrapContentSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    params.x += dragAmount.x.toInt()
                                    params.y += dragAmount.y.toInt()
                                    windowManager?.updateViewLayout(this@apply, params)
                                }
                            }
                    ) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xDD212121) // Semi-transparent dark background
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                // Recording dot & timer
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { isExpanded = !isExpanded }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(if (recordingStateVal == RecordingState.RECORDING) Color.Red else Color.Gray)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatTime(durationSecVal),
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = fadeIn() + expandHorizontally(),
                                    exit = fadeOut() + shrinkHorizontally()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        if (recordingStateVal == RecordingState.IDLE) {
                                            // Record button for IDLE state
                                            IconButtonOverlay(icon = Icons.Filled.RadioButtonChecked, description = "Gravar", tint = Color.Red) {
                                                triggerRecordingFromOverlay()
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            // Close button to close overlay
                                            IconButtonOverlay(icon = Icons.Filled.Close, description = "Fechar", tint = Color.LightGray) {
                                                removeFloatingOverlay()
                                                stopSelf()
                                            }
                                        } else {
                                            // Pause / Resume button
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                if (recordingStateVal == RecordingState.RECORDING) {
                                                    IconButtonOverlay(icon = Icons.Filled.Pause, description = "Pausar") {
                                                        pauseRecording()
                                                    }
                                                } else if (recordingStateVal == RecordingState.PAUSED) {
                                                    IconButtonOverlay(icon = Icons.Filled.PlayArrow, description = "Retomar") {
                                                        resumeRecording()
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            // Stop button
                                            IconButtonOverlay(icon = Icons.Filled.Stop, description = "Parar", tint = Color.Red) {
                                                stopRecording()
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Open App button
                                        IconButtonOverlay(icon = Icons.Filled.Launch, description = "Abrir Aplicativo") {
                                            openApplication()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view: ${e.message}")
        }
    }

    private fun triggerRecordingFromOverlay() {
        // Remove overlay temporarily to prevent tapjacking blocks on the permission dialog
        removeFloatingOverlay()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.example.action.START_RECORD_FROM_OVERLAY"
        }
        startActivity(intent)
    }

    private fun openApplication() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        
        // Collapse overlay after launching
        removeFloatingOverlay()
        if (Settings.canDrawOverlays(this)) {
            showFloatingOverlay()
        }
    }

    private fun removeFloatingOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}")
            }
            overlayView = null
        }
    }

    override fun onDestroy() {
        removeFloatingOverlay()
        serviceViewModelStore.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}

@Composable
fun IconButtonOverlay(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable { onClick() }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
