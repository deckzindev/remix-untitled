package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Collect Setting values
    val resolution by settingsRepository.resolutionFlow.collectAsState(initial = VideoResolution.HD_720P)
    val fps by settingsRepository.fpsFlow.collectAsState(initial = VideoFps.FPS_30)
    val bitrate by settingsRepository.bitrateFlow.collectAsState(initial = 0)
    val orientation by settingsRepository.orientationFlow.collectAsState(initial = VideoOrientation.AUTO)

    val audioSource by settingsRepository.audioSourceFlow.collectAsState(initial = AudioSource.MIC)

    val theme by settingsRepository.themeFlow.collectAsState(initial = AppTheme.SYSTEM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Resolution selector
                    SettingsDropdownRow(
                        label = "Resolução",
                        description = "Escolha a definição do vídeo",
                        currentValue = resolution.label,
                        options = VideoResolution.values().map { it.label },
                        onOptionSelected = { label ->
                            val selected = VideoResolution.values().find { it.label == label } ?: VideoResolution.HD_720P
                            coroutineScope.launch { settingsRepository.setResolution(selected) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // FPS selector
                    SettingsDropdownRow(
                        label = "Taxa de Quadros (FPS)",
                        description = "Quadros por segundo",
                        currentValue = "${fps.value} FPS",
                        options = VideoFps.values().map { "${it.value} FPS" },
                        onOptionSelected = { label ->
                            val fpsVal = label.split(" ")[0].toIntOrNull() ?: 30
                            val selected = VideoFps.values().find { it.value == fpsVal } ?: VideoFps.FPS_30
                            coroutineScope.launch { settingsRepository.setFps(selected) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Bitrate Selector
                    SettingsDropdownRow(
                        label = "Bitrate de Vídeo",
                        description = "Define a taxa de compressão de dados",
                        currentValue = if (bitrate == 0) "Automático" else "${bitrate / 1_000_000} Mbps",
                        options = listOf("Automático", "2 Mbps", "4 Mbps", "6 Mbps", "10 Mbps", "15 Mbps", "20 Mbps"),
                        onOptionSelected = { label ->
                            val bps = when (label) {
                                "2 Mbps" -> 2_000_000
                                "4 Mbps" -> 4_000_000
                                "6 Mbps" -> 6_000_000
                                "10 Mbps" -> 10_000_000
                                "15 Mbps" -> 15_000_000
                                "20 Mbps" -> 20_000_000
                                else -> 0 // Auto
                            }
                            coroutineScope.launch { settingsRepository.setBitrate(bps) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Orientation Selector
                    SettingsDropdownRow(
                        label = "Orientação da Tela",
                        description = "Sentido da captura",
                        currentValue = orientation.label,
                        options = VideoOrientation.values().map { it.label },
                        onOptionSelected = { label ->
                            val selected = VideoOrientation.values().find { it.label == label } ?: VideoOrientation.AUTO
                            coroutineScope.launch { settingsRepository.setOrientation(selected) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Audio Source Selector
                    SettingsDropdownRow(
                        label = "Fonte de Áudio",
                        description = "Selecione o canal de captura",
                        currentValue = audioSource.label,
                        options = AudioSource.values().map { it.label },
                        onOptionSelected = { label ->
                            val selected = AudioSource.values().find { it.label == label } ?: AudioSource.MIC
                            coroutineScope.launch { settingsRepository.setAudioSource(selected) }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Tema do Aplicativo
                    SettingsDropdownRow(
                        label = "Tema do Aplicativo",
                        description = "Alterne o esquema de cores",
                        currentValue = theme.label,
                        options = AppTheme.values().map { it.label },
                        onOptionSelected = { label ->
                            val selected = AppTheme.values().find { it.label == label } ?: AppTheme.SYSTEM
                            coroutineScope.launch { settingsRepository.setTheme(selected) }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsDropdownRow(
    label: String,
    description: String,
    currentValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Box {
            Text(
                text = currentValue,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
