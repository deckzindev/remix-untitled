package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun SettingsScreen(viewModel: BuilderViewModel) {
  val authState by viewModel.authUiState.collectAsState()

  var tokenInput by remember(authState.token) { mutableStateOf(authState.token) }
  var isTokenVisible by remember { mutableStateOf(false) }
  var customRepoInput by remember(authState.selectedRepo) { mutableStateOf(authState.selectedRepo) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "Configuração do GitHub & CI",
      fontWeight = FontWeight.Bold,
      fontSize = 20.sp,
      color = TextPrimary
    )

    // Token Card
    Card(
      colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Key, contentDescription = null, tint = AccentBlueBright)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Token de Acesso Pessoal (PAT)",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = TextPrimary
          )
        }

        Text(
          text = "Gere um token clássico no GitHub (com escopos: repo, workflow) ou use um fine-grained token com permissões de repositório e Actions.",
          fontSize = 12.sp,
          color = TextSecondary,
          lineHeight = 17.sp
        )

        OutlinedTextField(
          value = tokenInput,
          onValueChange = { tokenInput = it },
          placeholder = { Text("ghp_...", color = TextMuted) },
          singleLine = true,
          visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(
              onClick = { isTokenVisible = !isTokenVisible },
              modifier = Modifier.testTag("toggle_token_visibility")
            ) {
              Icon(
                imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Alternar visibilidade",
                tint = TextSecondary
              )
            }
          },
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentBlueBright,
            unfocusedBorderColor = DarkBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          ),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("github_token_input")
        )

        Button(
          onClick = { viewModel.saveAndVerifyToken(tokenInput) },
          enabled = tokenInput.isNotBlank() && !authState.isVerifying,
          colors = ButtonDefaults.buttonColors(containerColor = AccentBlueBright),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("verify_token_button")
        ) {
          if (authState.isVerifying) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DarkSurface, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Verificando com GitHub...", color = DarkSurface, fontSize = 13.sp)
          } else {
            Text("Salvar e Validar Token", color = DarkSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          }
        }

        if (authState.user != null) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreenBright, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Autenticado como @${authState.user?.login}",
              fontSize = 13.sp,
              color = AccentGreenBright,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }

    // Repository Card (available when authenticated)
    if (authState.user != null) {
      Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Source, contentDescription = null, tint = AccentBlueBright)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Repositório de Compilação",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = TextPrimary
              )
            }

            IconButton(
              onClick = { viewModel.fetchUserRepos() },
              modifier = Modifier.size(32.dp).testTag("refresh_repos_button")
            ) {
              Icon(Icons.Default.Refresh, contentDescription = "Atualizar repositórios", tint = TextMuted)
            }
          }

          Text(
            text = "Repositório onde os arquivos do projeto serão enviados e o GitHub Actions executará o build:",
            fontSize = 12.sp,
            color = TextSecondary
          )

          OutlinedTextField(
            value = customRepoInput,
            onValueChange = {
              customRepoInput = it
              viewModel.setSelectedRepo(it)
            },
            placeholder = { Text("${authState.user?.login}/meu-repo-ci", color = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = AccentBlueBright,
              unfocusedBorderColor = DarkBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("repo_name_input")
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = {
                val username = authState.user?.login ?: "user"
                viewModel.createCompilerRepo("android-compiler-runner")
              },
              enabled = !authState.isCreatingRepo,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.weight(1f).testTag("create_compiler_repo_button")
            ) {
              if (authState.isCreatingRepo) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
              } else {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Criar Repo Dedicado", fontSize = 11.sp, maxLines = 1)
              }
            }

            OutlinedButton(
              onClick = { viewModel.installWorkflowToSelectedRepo() },
              enabled = !authState.isInstallingWorkflow && authState.selectedRepo.isNotBlank(),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.weight(1f).testTag("install_workflow_button")
            ) {
              if (authState.isInstallingWorkflow) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
              } else {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sincronizar CI", fontSize = 11.sp, maxLines = 1)
              }
            }
          }
        }
      }
    }

    // Success Info Banner
    if (authState.infoMessage != null) {
      Card(
        colors = CardDefaults.cardColors(containerColor = AccentGreenBright.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, AccentGreenBright.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
      ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreenBright, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(text = authState.infoMessage ?: "", color = AccentGreenBright, fontSize = 12.sp)
        }
      }
    }

    // Error Banner
    if (authState.errorMessage != null) {
      Card(
        colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, AccentRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
      ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(text = authState.errorMessage ?: "", color = AccentRed, fontSize = 12.sp)
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
  }
}
