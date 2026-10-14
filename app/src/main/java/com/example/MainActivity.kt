package com.example

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.data.AppTheme
import com.example.data.SettingsRepository
import com.example.service.RecordingState
import com.example.service.ScreenRecordingService
import com.example.ui.AppNavigation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // MediaProjection screen recording capture authorization launcher
    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenRecordingService.startService(this, result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permissão de gravação de tela negada", Toast.LENGTH_SHORT).show()
            ScreenRecordingService.startOverlayService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsRepository = SettingsRepository(this)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            val appThemeSetting by settingsRepository.themeFlow.collectAsState(initial = AppTheme.SYSTEM)
            
            // Determine active light/dark theme selection
            val useDarkTheme = when (appThemeSetting) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            // Collect recording state to defer moveTaskToBack
            val recordingState by ScreenRecordingService.recordingState.collectAsState()

            // Safe, Android 14+ compatible behavior: move activity to background ONLY after recording successfully starts
            androidx.compose.runtime.LaunchedEffect(recordingState) {
                if (recordingState == RecordingState.RECORDING) {
                    // Give a comfortable delay for the MediaProjection / VirtualDisplay session to fully establish on Android 14+
                    kotlinx.coroutines.delay(1500)
                    moveTaskToBack(true)
                }
            }

            MyApplicationTheme(darkTheme = useDarkTheme, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(settingsRepository = settingsRepository)
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.example.action.START_RECORD_FROM_OVERLAY") {
            requestScreenRecording()
        }
    }

    fun requestScreenRecording() {
        try {
            val captureIntent = if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14+
                // Force the permission dialog to offer and default to entire screen capture,
                // bypassing the "Single App" recording option which fails when the app is backgrounded.
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                mediaProjectionManager.createScreenCaptureIntent(config)
            } else {
                mediaProjectionManager.createScreenCaptureIntent()
            }
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar captura: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

