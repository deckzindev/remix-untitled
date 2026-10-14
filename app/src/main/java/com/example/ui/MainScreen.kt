package com.example.ui

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.data.*
import com.example.service.RecordingState
import com.example.service.ScreenRecordingService
import com.example.util.DebugLogger
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? MainActivity

    // Collect Service State
    val recordingState by ScreenRecordingService.recordingState.collectAsState()
    val durationSeconds by ScreenRecordingService.durationSeconds.collectAsState()

    // Collect Settings for Info display
    val resolution by settingsRepository.resolutionFlow.collectAsState(initial = VideoResolution.HD_720P)
    val fps by settingsRepository.fpsFlow.collectAsState(initial = VideoFps.FPS_30)
    val audioSource by settingsRepository.audioSourceFlow.collectAsState(initial = AudioSource.MIC)

    // Permissions State
    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Android 9 (API 28) or lower
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions = permissionsToRequest)
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showDebugPanel by remember { mutableStateOf(false) }
    val logs by DebugLogger.logs.collectAsState(initial = emptyList())

    // Request permissions automatically when the screen is first loaded
    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    // Recheck overlay permission when screen is focused
    LaunchedEffect(recordingState) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Elegant, minimalist content in the center when IDLE
            if (recordingState == RecordingState.IDLE) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Pronto para Gravar",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Toque no botão abaixo para iniciar a gravação da tela.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Minimal tags row to display active configuration
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusChip(label = resolution.label.split(" ")[0])
                        StatusChip(label = "${fps.value} FPS")
                        StatusChip(label = audioSource.label)
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Center Primary Action Button
                    Button(
                        onClick = {
                            val needsAudio = audioSource != AudioSource.NONE
                            val hasAudioPermission = permissionState.permissions.any { it.permission == Manifest.permission.RECORD_AUDIO && it.status.isGranted }
                            val hasStoragePermission = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                permissionState.permissions.any { it.permission == Manifest.permission.WRITE_EXTERNAL_STORAGE && it.status.isGranted }
                            } else {
                                true
                            }
                            
                            val isReady = (!needsAudio || hasAudioPermission) && hasStoragePermission
                            
                            if (!isReady) {
                                permissionState.launchMultiplePermissionRequest()
                                Toast.makeText(context, "Por favor, conceda as permissões necessárias", Toast.LENGTH_SHORT).show()
                            } else {
                                activity?.requestScreenRecording()
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(56.dp)
                            .testTag("iniciar_gravacao_centro")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Iniciar Gravação", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    // Optional quick path for floating window overlays
                    if (!hasOverlayPermission) {
                        Spacer(modifier = Modifier.height(24.dp))
                        TextButton(
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Não foi possível abrir configurações de sobreposição", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Layers,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ativar Botão Flutuante (Opcional)", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Content in the center (only visible when not IDLE)
            if (recordingState != RecordingState.IDLE) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Giant beautiful timer
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recordingState == RecordingState.RECORDING) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = formatSeconds(durationSeconds),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (recordingState == RecordingState.RECORDING) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        ScreenRecordingService.pauseRecording(context)
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier
                                    .height(56.dp)
                                    .testTag("pausar_gravacao_direto")
                            ) {
                                Icon(Icons.Filled.Pause, contentDescription = "Pausar")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Pausar", fontWeight = FontWeight.Medium)
                            }
                        } else if (recordingState == RecordingState.PAUSED) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        ScreenRecordingService.resumeRecording(context)
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier
                                    .height(56.dp)
                                    .testTag("retomar_gravacao_direto")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Retomar")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retomar", fontWeight = FontWeight.Medium)
                            }
                        }

                        Button(
                            onClick = {
                                ScreenRecordingService.stopService(context)
                                Toast.makeText(context, "Gravação finalizada!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("parar_gravacao_direto")
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "Parar")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Parar", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Small record button in the top right corner
            if (recordingState == RecordingState.IDLE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.TopEnd
                ) {
                    FilledIconButton(
                        onClick = {
                            val needsAudio = audioSource != AudioSource.NONE
                            val hasAudioPermission = permissionState.permissions.any { it.permission == Manifest.permission.RECORD_AUDIO && it.status.isGranted }
                            val hasStoragePermission = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                permissionState.permissions.any { it.permission == Manifest.permission.WRITE_EXTERNAL_STORAGE && it.status.isGranted }
                            } else {
                                true
                            }
                            
                            val isReady = (!needsAudio || hasAudioPermission) && hasStoragePermission
                            
                            if (!isReady) {
                                permissionState.launchMultiplePermissionRequest()
                                Toast.makeText(context, "Por favor, conceda as permissões necessárias", Toast.LENGTH_SHORT).show()
                            } else {
                                activity?.requestScreenRecording()
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .testTag("iniciar_gravacao_direto")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = "Iniciar Gravação",
                            tint = Color.Red,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Diagnostics Drawer Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                if (showDebugPanel) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(16.dp),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Log de Diagnóstico",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { DebugLogger.clear() }) {
                                        Text("Limpar", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { showDebugPanel = false },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Fechar",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Nenhum log registrado ainda.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(logs) { entry ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "[${entry.level}] ${entry.tag}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = if (entry.level == "ERROR") Color.Red else MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = entry.timestamp,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                )
                                            }
                                            Text(
                                                text = entry.message,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            entry.exceptionMessage?.let { exc ->
                                                Text(
                                                    text = exc,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                    modifier = Modifier
                                                        .background(Color.Black.copy(alpha = 0.05f))
                                                        .padding(4.dp)
                                                        .fillMaxWidth()
                                                )
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Small floating button at the bottom center to open diagnostic logs
                    TextButton(
                        onClick = { showDebugPanel = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .testTag("abrir_diagnostico")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Exibir Logs de Diagnóstico",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@SuppressLint("DefaultLocale")
private fun formatSeconds(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
