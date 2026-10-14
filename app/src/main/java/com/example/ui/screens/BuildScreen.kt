package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.BuildStepProgress
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentBlueBright
import com.example.ui.theme.AccentGreenBright
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.BuilderViewModel

@Composable
fun BuildScreen(
  viewModel: BuilderViewModel,
  onNavigateToSettings: () -> Unit,
  onNavigateToHistory: () -> Unit
) {
  val authState by viewModel.authUiState.collectAsState()
  val buildState by viewModel.buildUiState.collectAsState()

  val zipPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) {
      viewModel.analyzeZipUri(uri)
    }
  }

  val scrollState = rememberScrollState()

  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp, vertical = 16.dp),
    contentAlignment = Alignment.TopCenter
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(scrollState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Token check banner
      if (authState.token.isBlank()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.12f)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Token do GitHub Necessário",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentAmber
              )
              Text(
                text = "Configure seu PAT nas configurações para enviar o código e gerar APKs.",
                fontSize = 12.sp,
                color = TextSecondary
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = onNavigateToSettings,
              colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.testTag("setup_token_banner_button")
            ) {
              Text("Configurar", color = DarkSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      // Active Build Progress Card
      if (buildState.isBuilding) {
        Card(
          colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentBlueBright.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        ) {
          Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlueBright, strokeWidth = 2.5.dp)
              Spacer(modifier = Modifier.width(12.dp))
              val step = buildState.currentProgress
              val stepText = when (step) {
                is BuildStepProgress.UploadingFiles -> "Enviando arquivos (${step.current}/${step.total})"
                is BuildStepProgress.CreatingTree -> "Construindo árvore Git..."
                is BuildStepProgress.CreatingCommit -> "Criando commit no GitHub..."
                is BuildStepProgress.UpdatingBranch -> "Atualizando branch no runner..."
                is BuildStepProgress.InstallingWorkflow -> "Instalando workflow no repositório..."
                is BuildStepProgress.WaitingForRunner -> "Aguardando GitHub Actions iniciar..."
                is BuildStepProgress.WorkflowStarted -> "Compilação iniciada (#${step.runId})"
                else -> "Preparando ambiente de compilação..."
              }
              Text(
                text = stepText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = AccentBlueBright
              )
            }

            val step = buildState.currentProgress
            if (step is BuildStepProgress.UploadingFiles && step.total > 0) {
              val progressFraction = (step.current.toFloat() / step.total.toFloat()).coerceIn(0f, 1f)
              LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AccentBlueBright,
                trackColor = DarkSurface
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = step.currentFile.substringAfterLast('/'),
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextMuted,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f)
                )
                Text(
                  text = "${(progressFraction * 100).toInt()}%",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = AccentBlueBright
                )
              }
            } else {
              LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AccentBlueBright,
                trackColor = DarkSurface
              )
            }
          }
        }
      } else if (buildState.isAnalyzing) {
        Card(
          colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentBlueBright)
            Spacer(modifier = Modifier.width(14.dp))
            Text("Examinando estrutura do projeto Android...", fontSize = 13.sp, color = TextPrimary)
          }
        }
      } else if (buildState.projectAnalysis == null) {
        // Zero-state selection cards
        Card(
          colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Icon(
              Icons.Default.FolderZip,
              contentDescription = null,
              tint = AccentBlueBright,
              modifier = Modifier.size(48.dp)
            )

            Text(
              text = "Selecione o Projeto Android",
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              color = TextPrimary
            )

            Text(
              text = "Escolha um arquivo ZIP com o código-fonte do seu app Android (com build.gradle e módulo /app) para compilar no GitHub.",
              fontSize = 13.sp,
              color = TextSecondary,
              lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
              onClick = {
                zipPickerLauncher.launch(
                  arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "*/*"
                  )
                )
              },
              modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("pick_zip_button"),
              colors = ButtonDefaults.buttonColors(containerColor = AccentBlueBright),
              shape = RoundedCornerShape(12.dp)
            ) {
              Icon(Icons.Default.FolderZip, contentDescription = null, tint = DarkSurface, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = "Escolher Arquivo ZIP",
                color = DarkSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
              )
            }

            OutlinedButton(
              onClick = { viewModel.loadSampleProject() },
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("load_sample_project_button"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlueBright),
              shape = RoundedCornerShape(12.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlueBright.copy(alpha = 0.5f))
            ) {
              Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentBlueBright, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Testar com Projeto de Exemplo",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
              )
            }
          }
        }
      } else {
        // Project selected: details & build configuration
        val analysis = buildState.projectAnalysis!!

        Card(
          colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, tint = AccentBlueBright, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text(
                    text = buildState.projectName.ifBlank { "Projeto Android" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                  )
                  Text(
                    text = "${analysis.totalFiles} arquivos • ${(analysis.totalSizeBytes / 1024.0 / 1024.0).let { "%.2f MB".format(it) }}",
                    fontSize = 12.sp,
                    color = TextMuted
                  )
                }
              }

              IconButton(
                onClick = { viewModel.clearSelectedProject() },
                modifier = Modifier.size(32.dp).testTag("clear_project_button")
              ) {
                Icon(Icons.Default.Close, contentDescription = "Remover", tint = TextMuted, modifier = Modifier.size(18.dp))
              }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Project name editor
            OutlinedTextField(
              value = buildState.projectName,
              onValueChange = { viewModel.updateProjectName(it) },
              label = { Text("Nome do Aplicativo / APK") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("project_name_input"),
              colors = TextFieldDefaults.colors(
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                focusedIndicatorColor = AccentBlueBright,
                unfocusedIndicatorColor = DarkBorder,
                focusedLabelColor = AccentBlueBright,
                unfocusedLabelColor = TextMuted,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
              ),
              shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Build Variant selector (Release vs Debug)
            Text(
              text = "Variante de Compilação:",
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              color = TextSecondary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              FilterChip(
                selected = buildState.buildVariant == "release",
                onClick = { viewModel.setBuildVariant("release") },
                label = { Text("Release (Assinado)") },
                leadingIcon = if (buildState.buildVariant == "release") {
                  { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = AccentBlueBright.copy(alpha = 0.2f),
                  selectedLabelColor = AccentBlueBright,
                  selectedLeadingIconColor = AccentBlueBright,
                  containerColor = DarkSurface,
                  labelColor = TextMuted
                ),
                border = FilterChipDefaults.filterChipBorder(
                  enabled = true,
                  selected = buildState.buildVariant == "release",
                  borderColor = if (buildState.buildVariant == "release") AccentBlueBright else DarkBorder
                ),
                modifier = Modifier.weight(1f).testTag("variant_release_chip")
              )

              FilterChip(
                selected = buildState.buildVariant == "debug",
                onClick = { viewModel.setBuildVariant("debug") },
                label = { Text("Debug (Rápido)") },
                leadingIcon = if (buildState.buildVariant == "debug") {
                  { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = AccentGreenBright.copy(alpha = 0.2f),
                  selectedLabelColor = AccentGreenBright,
                  selectedLeadingIconColor = AccentGreenBright,
                  containerColor = DarkSurface,
                  labelColor = TextMuted
                ),
                border = FilterChipDefaults.filterChipBorder(
                  enabled = true,
                  selected = buildState.buildVariant == "debug",
                  borderColor = if (buildState.buildVariant == "debug") AccentGreenBright else DarkBorder
                ),
                modifier = Modifier.weight(1f).testTag("variant_debug_chip")
              )
            }
          }
        }

        // Target repository display
        if (authState.selectedRepo.isNotBlank()) {
          Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text("Repositório de Execução:", fontSize = 11.sp, color = TextMuted)
                Text(authState.selectedRepo, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = AccentBlueBright)
              }
              IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Alterar", tint = TextMuted, modifier = Modifier.size(16.dp))
              }
            }
          }
        }

        // Compile Button
        Button(
          onClick = {
            if (authState.token.isBlank()) {
              onNavigateToSettings()
            } else {
              viewModel.startBuild { onNavigateToHistory() }
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("start_build_button"),
          colors = ButtonDefaults.buttonColors(containerColor = AccentGreenBright),
          shape = RoundedCornerShape(12.dp)
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkSurface, modifier = Modifier.size(20.dp))
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "Enviar e Compilar APK",
            color = DarkSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
        }
      }

      // Status notice or error card
      if (buildState.errorMessage != null && !buildState.isBuilding) {
        Card(
          colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.12f)),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AccentRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              verticalAlignment = Alignment.Top,
              modifier = Modifier.fillMaxWidth()
            ) {
              Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = buildState.errorMessage ?: "",
                color = AccentRed,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
              )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End
            ) {
              OutlinedButton(
                onClick = {
                  if (authState.token.isBlank()) {
                    onNavigateToSettings()
                  } else {
                    viewModel.startBuild { onNavigateToHistory() }
                  }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("retry_build_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Tentar Novamente",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
    }
  }
}
