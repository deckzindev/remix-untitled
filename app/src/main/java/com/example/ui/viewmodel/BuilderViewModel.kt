package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.data.model.GitHubArtifact
import com.example.data.model.GitHubRepo
import com.example.data.model.GitHubUser
import com.example.data.model.ProjectBuildEntity
import com.example.data.model.WorkflowJob
import com.example.data.remote.BuildStepProgress
import com.example.data.remote.GitHubApiClient
import com.example.data.remote.GitHubRepository
import com.example.util.SampleProjectGenerator
import com.example.util.ZipAnalysisResult
import com.example.util.ZipProjectParser
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AuthUiState(
  val token: String = "",
  val isVerifying: Boolean = false,
  val user: GitHubUser? = null,
  val repos: List<GitHubRepo> = emptyList(),
  val selectedRepo: String = "",
  val isCreatingRepo: Boolean = false,
  val isInstallingWorkflow: Boolean = false,
  val infoMessage: String? = null,
  val errorMessage: String? = null
)

data class BuildUiState(
  val projectAnalysis: ZipAnalysisResult? = null,
  val compatibilityAnalysis: com.example.engine.CompatibilityAnalysis? = null,
  val projectName: String = "",
  val buildVariant: String = "release",
  val engineStrategy: String = "compatibility",
  val isAnalyzing: Boolean = false,
  val isBuilding: Boolean = false,
  val currentProgress: BuildStepProgress? = null,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
  val missingRequiredNotice: String? = null
)

data class LogsDialogState(
  val isVisible: Boolean = false,
  val buildEntity: ProjectBuildEntity? = null,
  val jobs: List<WorkflowJob> = emptyList(),
  val artifacts: List<GitHubArtifact> = emptyList(),
  val rawLogs: String? = null,
  val isLoading: Boolean = false,
  val error: String? = null
)

class BuilderViewModel(application: Application) : AndroidViewModel(application) {

  private val database = AppDatabase.getDatabase(application)
  private val buildDao = database.projectBuildDao()
  private val prefs = PreferencesManager(application)

  private val _authUiState = MutableStateFlow(AuthUiState())
  val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

  private val _buildUiState = MutableStateFlow(BuildUiState())
  val buildUiState: StateFlow<BuildUiState> = _buildUiState.asStateFlow()

  private val _logsDialogState = MutableStateFlow(LogsDialogState())
  val logsDialogState: StateFlow<LogsDialogState> = _logsDialogState.asStateFlow()

  val allBuilds: StateFlow<List<ProjectBuildEntity>> = buildDao.getAllBuilds()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _downloadingBuildIds = MutableStateFlow<Set<Long>>(emptySet())
  val downloadingBuildIds: StateFlow<Set<Long>> = _downloadingBuildIds.asStateFlow()

  private val _statusNotice = MutableStateFlow<String?>(null)
  val statusNotice: StateFlow<String?> = _statusNotice.asStateFlow()

  fun clearStatusNotice() {
    _statusNotice.value = null
  }

  fun isOnline(): Boolean {
    val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun formatNetworkError(e: Throwable?): String {
    if (e == null) return "Erro desconhecido ao comunicar com o GitHub."
    val message = e.message ?: e.localizedMessage ?: ""
    return when {
      e is java.net.UnknownHostException ||
      message.contains("Unable to resolve host", ignoreCase = true) ||
      message.contains("No address associated with hostname", ignoreCase = true) ->
        "Sem conexão com a internet. Não foi possível conectar aos servidores do GitHub (api.github.com). Verifique seu Wi-Fi ou dados móveis e tente novamente."

      e is java.net.SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
        "O tempo limite de conexão com o GitHub expirou. Sua conexão pode estar instável. Toque em Tentar Novamente."

      e is java.net.ConnectException || message.contains("Failed to connect", ignoreCase = true) ->
        "Falha ao conectar com os servidores do GitHub. Verifique sua conexão com a internet."

      e is javax.net.ssl.SSLException || message.contains("SSL", ignoreCase = true) ->
        "Falha na conexão segura (SSL). Verifique a data e hora do seu dispositivo."

      message.contains("401", ignoreCase = true) || message.contains("Bad credentials", ignoreCase = true) ->
        "Token do GitHub inválido ou expirado. Acesse a aba Configuração e atualize seu token."

      message.contains("403", ignoreCase = true) || message.contains("rate limit", ignoreCase = true) ->
        "Limite de requisições da API do GitHub atingido ou permissão negada. Verifique as permissões do seu token (repo, workflow)."

      message.contains("404", ignoreCase = true) ->
        "Recurso ou repositório não encontrado no GitHub (404). Verifique o nome do repositório configurado."

      else -> message
    }
  }

  private var gitHubRepository: GitHubRepository? = null
  private var pollingJob: Job? = null

  init {
    val savedToken = prefs.githubToken.value
    val savedRepo = prefs.defaultRepo.value
    val savedVariant = prefs.defaultVariant.value

    _authUiState.value = _authUiState.value.copy(
      token = savedToken,
      selectedRepo = savedRepo
    )
    _buildUiState.value = _buildUiState.value.copy(
      buildVariant = "release"
    )

    if (savedToken.isNotBlank()) {
      saveAndVerifyToken(savedToken)
    }

    startPollingActiveBuilds()
  }

  private fun initializeApi(token: String) {
    val service = GitHubApiClient.createService(token)
    gitHubRepository = GitHubRepository(service)
  }

  fun updateToken(token: String) {
    _authUiState.value = _authUiState.value.copy(token = token, errorMessage = null)
  }

  fun saveAndVerifyToken(token: String) {
    viewModelScope.launch {
      val cleanToken = token.trim()
      if (cleanToken.isBlank()) {
        _authUiState.value = _authUiState.value.copy(errorMessage = "Insira um token válido do GitHub.")
        return@launch
      }

      _authUiState.value = _authUiState.value.copy(isVerifying = true, errorMessage = null, infoMessage = null)
      initializeApi(cleanToken)

      val repo = gitHubRepository ?: return@launch
      val userResult = repo.validateToken()

      if (userResult.isSuccess) {
        val user = userResult.getOrNull()!!
        prefs.saveGitHubToken(cleanToken)
        prefs.saveGitHubUsername(user.login)

        val currentRepo = _authUiState.value.selectedRepo.ifBlank {
          prefs.defaultRepo.value.ifBlank {
            "${user.login}/android-compiler-runner"
          }
        }
        prefs.saveDefaultRepo(currentRepo)

        _authUiState.value = _authUiState.value.copy(
          token = cleanToken,
          user = user,
          selectedRepo = currentRepo,
          isVerifying = false,
          errorMessage = null,
          infoMessage = "Autenticado com sucesso como @${user.login}!"
        )

        fetchUserRepos()
      } else {
        val friendlyMsg = formatNetworkError(userResult.exceptionOrNull())
        _authUiState.value = _authUiState.value.copy(
          isVerifying = false,
          errorMessage = friendlyMsg
        )
      }
    }
  }

  fun fetchUserRepos() {
    viewModelScope.launch {
      val repo = gitHubRepository ?: return@launch
      val result = repo.getUserRepos()
      if (result.isSuccess) {
        val list = result.getOrNull().orEmpty()
        val currentSelected = _authUiState.value.selectedRepo
        val defaultChoice = if (currentSelected.isNotBlank() && list.any { it.fullName == currentSelected }) {
          currentSelected
        } else if (list.isNotEmpty()) {
          list.first().fullName
        } else {
          val username = _authUiState.value.user?.login ?: prefs.githubUsername.value
          if (username.isNotBlank()) "$username/android-compiler-runner" else currentSelected
        }

        _authUiState.value = _authUiState.value.copy(
          repos = list,
          selectedRepo = defaultChoice
        )
        if (defaultChoice.isNotBlank()) {
          prefs.saveDefaultRepo(defaultChoice)
        }
      }
    }
  }

  fun setSelectedRepo(repoFullName: String) {
    val clean = repoFullName.trim()
    _authUiState.value = _authUiState.value.copy(selectedRepo = clean)
    prefs.saveDefaultRepo(clean)
  }

  fun setBuildVariant(variant: String) {
    _buildUiState.value = _buildUiState.value.copy(buildVariant = variant)
    prefs.saveDefaultVariant(variant)
  }

  fun createCompilerRepo(repoName: String) {
    viewModelScope.launch {
      val repo = gitHubRepository ?: run {
        _authUiState.value = _authUiState.value.copy(errorMessage = "Configure o token do GitHub primeiro.")
        return@launch
      }

      _authUiState.value = _authUiState.value.copy(isCreatingRepo = true, errorMessage = null)
      val result = repo.createCompilerRepository(repoName.trim())

      if (result.isSuccess) {
        val created = result.getOrNull()!!
        _authUiState.value = _authUiState.value.copy(
          isCreatingRepo = false,
          selectedRepo = created.fullName,
          infoMessage = "Repositório ${created.fullName} criado com sucesso e configurado com o Workflow CI!"
        )
        prefs.saveDefaultRepo(created.fullName)
        fetchUserRepos()
      } else {
        _authUiState.value = _authUiState.value.copy(
          isCreatingRepo = false,
          errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Erro ao criar repositório."
        )
      }
    }
  }

  fun installWorkflowToSelectedRepo() {
    viewModelScope.launch {
      val repoFullName = _authUiState.value.selectedRepo
      if (repoFullName.isBlank()) {
        _authUiState.value = _authUiState.value.copy(errorMessage = "Selecione um repositório primeiro.")
        return@launch
      }

      val repo = gitHubRepository ?: return@launch
      _authUiState.value = _authUiState.value.copy(isInstallingWorkflow = true, errorMessage = null)

      val result = repo.installWorkflow(repoFullName)
      if (result.isSuccess) {
        _authUiState.value = _authUiState.value.copy(
          isInstallingWorkflow = false,
          infoMessage = "Workflow CI instalado/atualizado em $repoFullName (.github/workflows/android-compile.yml)!"
        )
      } else {
        _authUiState.value = _authUiState.value.copy(
          isInstallingWorkflow = false,
          errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Erro ao instalar workflow."
        )
      }
    }
  }

  fun setEngineStrategy(strategy: String) {
    _buildUiState.value = _buildUiState.value.copy(engineStrategy = strategy)
    prefs.saveEngineStrategy(strategy)

    // Re-run compatibility engine on currently selected project if present
    val analysis = _buildUiState.value.projectAnalysis
    if (analysis != null) {
      val (adaptedFiles, compatAnalysis) = com.example.engine.CompatibilityEngine.inspectAndAdapt(analysis.files, strategy)
      val updatedAnalysis = analysis.copy(files = adaptedFiles)
      _buildUiState.value = _buildUiState.value.copy(
        projectAnalysis = updatedAnalysis,
        compatibilityAnalysis = compatAnalysis
      )
    }
  }

  fun analyzeZipUri(uri: Uri) {
    viewModelScope.launch {
      _buildUiState.value = _buildUiState.value.copy(isAnalyzing = true, errorMessage = null, missingRequiredNotice = null)
      val context = getApplication<Application>()
      val analysis = ZipProjectParser.analyzeAndExtractZip(context, uri)

      if (analysis.error != null && !analysis.isValidAndroidProject) {
        _buildUiState.value = _buildUiState.value.copy(
          isAnalyzing = false,
          projectAnalysis = analysis,
          projectName = analysis.detectedProjectName,
          errorMessage = analysis.error
        )
        return@launch
      }

      val missingNotice = if (analysis.missingRequiredConfigs.contains("google-services.json")) {
        "Configuração obrigatória ausente: Este projeto utiliza Firebase / Google Services, mas o arquivo 'google-services.json' não foi encontrado no ZIP."
      } else null

      val strategy = prefs.engineStrategy.value
      val (adaptedFiles, compatAnalysis) = com.example.engine.CompatibilityEngine.inspectAndAdapt(analysis.files, strategy)
      val updatedAnalysis = analysis.copy(files = adaptedFiles)

      _buildUiState.value = _buildUiState.value.copy(
        isAnalyzing = false,
        projectAnalysis = updatedAnalysis,
        compatibilityAnalysis = compatAnalysis,
        projectName = updatedAnalysis.detectedProjectName,
        engineStrategy = strategy,
        errorMessage = analysis.error,
        missingRequiredNotice = missingNotice
      )
    }
  }

  fun loadSampleProject() {
    _buildUiState.value = _buildUiState.value.copy(isAnalyzing = true, errorMessage = null, missingRequiredNotice = null)
    val analysis = SampleProjectGenerator.generateSampleProject()
    val strategy = prefs.engineStrategy.value
    val (adaptedFiles, compatAnalysis) = com.example.engine.CompatibilityEngine.inspectAndAdapt(analysis.files, strategy)
    val updatedAnalysis = analysis.copy(files = adaptedFiles)

    _buildUiState.value = _buildUiState.value.copy(
      isAnalyzing = false,
      projectAnalysis = updatedAnalysis,
      compatibilityAnalysis = compatAnalysis,
      projectName = updatedAnalysis.detectedProjectName,
      engineStrategy = strategy,
      statusMessage = "Projeto de exemplo Jetpack Compose gerado com sucesso! Pronto para compilar."
    )
  }

  fun updateProjectName(name: String) {
    _buildUiState.value = _buildUiState.value.copy(projectName = name)
  }

  fun clearSelectedProject() {
    _buildUiState.value = _buildUiState.value.copy(
      projectAnalysis = null,
      compatibilityAnalysis = null,
      projectName = "",
      statusMessage = null,
      errorMessage = null,
      missingRequiredNotice = null,
      currentProgress = null
    )
  }

  fun startBuild(onSuccessNavigateToHistory: () -> Unit) {
    if (!isOnline()) {
      _buildUiState.value = _buildUiState.value.copy(
        isBuilding = false,
        errorMessage = "Sem conexão com a internet. Verifique seu Wi-Fi ou dados móveis e tente novamente."
      )
      return
    }

    val analysis = _buildUiState.value.projectAnalysis ?: run {
      _buildUiState.value = _buildUiState.value.copy(errorMessage = "Nenhum projeto ZIP selecionado.")
      return
    }

    // Regra de segurança: Se google-services.json for exigido mas não fornecido, interromper e orientar o usuário
    if (analysis.missingRequiredConfigs.contains("google-services.json")) {
      _buildUiState.value = _buildUiState.value.copy(
        errorMessage = "Compilação bloqueada: O arquivo 'google-services.json' é obrigatório para este projeto. O Studio APK não utiliza arquivos fictícios nem mascara erros."
      )
      return
    }

    val repoFullName = _authUiState.value.selectedRepo
    if (repoFullName.isBlank()) {
      _buildUiState.value = _buildUiState.value.copy(errorMessage = "Selecione o repositório nas configurações.")
      return
    }

    val gitRepo = gitHubRepository ?: run {
      _buildUiState.value = _buildUiState.value.copy(errorMessage = "Configure o token do GitHub primeiro.")
      return
    }

    viewModelScope.launch {
      _buildUiState.value = _buildUiState.value.copy(
        isBuilding = true,
        errorMessage = null,
        statusMessage = "Preparando arquivos para envio ao GitHub..."
      )

      val projectName = _buildUiState.value.projectName.ifBlank { "App" }
      val variant = _buildUiState.value.buildVariant.ifBlank { "release" }
      val gradleTask = if (variant.equals("debug", ignoreCase = true)) "assembleDebug" else "assembleRelease"

      // 1. Create DB record in "uploading" state
      val buildEntity = ProjectBuildEntity(
        projectName = projectName,
        repoFullName = repoFullName,
        branchName = "preparando...",
        buildVariant = variant,
        gradleTask = gradleTask,
        status = "uploading",
        totalFiles = analysis.totalFiles,
        zipSizeBytes = analysis.totalSizeBytes
      )
      val insertedId = buildDao.insertBuild(buildEntity)

      val result = gitRepo.pushProjectAndStartBuild(
        repoFullName = repoFullName,
        projectName = projectName,
        files = analysis.files,
        buildVariant = variant
      ) { progress ->
        _buildUiState.value = _buildUiState.value.copy(currentProgress = progress)
      }

      if (result.isSuccess) {
        val (branchName, runId, commitSha) = result.getOrNull()!!
        val updated = buildDao.getBuildById(insertedId)?.copy(
          branchName = branchName,
          runId = runId,
          commitSha = commitSha,
          status = if (runId != null) "in_progress" else "queued"
        )
        if (updated != null) {
          buildDao.updateBuild(updated)
        }

        _buildUiState.value = _buildUiState.value.copy(
          isBuilding = false,
          currentProgress = null,
          statusMessage = "Código enviado com sucesso para o branch $branchName! Compilação em andamento."
        )

        onSuccessNavigateToHistory()
      } else {
        val friendlyMsg = formatNetworkError(result.exceptionOrNull())
        val failed = buildDao.getBuildById(insertedId)?.copy(
          status = "failed",
          conclusion = "failure",
          errorMessage = friendlyMsg,
          completedAt = System.currentTimeMillis()
        )
        if (failed != null) {
          buildDao.updateBuild(failed)
        }

        _buildUiState.value = _buildUiState.value.copy(
          isBuilding = false,
          currentProgress = null,
          errorMessage = friendlyMsg
        )
      }
    }
  }

  private fun startPollingActiveBuilds() {
    pollingJob?.cancel()
    pollingJob = viewModelScope.launch {
      while (isActive) {
        try {
          val activeList = allBuilds.value.filter { it.status in listOf("uploading", "queued", "in_progress") }
          val repo = gitHubRepository

          if (repo != null && activeList.isNotEmpty()) {
            for (build in activeList) {
              if (build.runId != null) {
                // Poll GitHub run status
                val runResult = repo.getRunStatus(build.repoFullName, build.runId)
                if (runResult.isSuccess) {
                  val run = runResult.getOrNull()!!
                  val isFinished = run.status == "completed"

                  var artifactName: String? = null
                  var artifactUrl: String? = null
                  var artifactId: Long? = null

                  if (isFinished && run.conclusion == "success") {
                    val artifactsResult = repo.getArtifacts(build.repoFullName, build.runId)
                    if (artifactsResult.isSuccess) {
                      val list = artifactsResult.getOrNull().orEmpty()
                      val apkArtifact = list.firstOrNull { it.name.contains("apk", ignoreCase = true) } ?: list.firstOrNull()
                      if (apkArtifact != null) {
                        artifactName = apkArtifact.name
                        artifactUrl = apkArtifact.archiveDownloadUrl
                        artifactId = apkArtifact.id
                      }
                    }
                  }

                  var buildErrorMessage = build.errorMessage
                  if (isFinished && run.conclusion == "failure" && build.errorMessage.isNullOrBlank()) {
                    val jobsResult = repo.getRunJobs(build.repoFullName, build.runId)
                    val firstFailedJob = jobsResult.getOrNull()?.firstOrNull { it.conclusion == "failure" }
                    if (firstFailedJob != null) {
                      val logResult = repo.getJobLogs(build.repoFullName, firstFailedJob.id)
                      if (logResult.isSuccess) {
                        buildErrorMessage = diagnoseBuildError(logResult.getOrNull())
                      }
                    }
                    if (buildErrorMessage.isNullOrBlank()) {
                      buildErrorMessage = "Compilação falhou no GitHub Actions. Consulte os logs detalhados para o rastreamento do erro."
                    }
                  }

                  val updated = build.copy(
                    status = run.status ?: build.status,
                    conclusion = run.conclusion,
                    runUrl = run.htmlUrl ?: build.runUrl,
                    errorMessage = buildErrorMessage ?: build.errorMessage,
                    artifactName = artifactName ?: build.artifactName,
                    artifactId = artifactId ?: build.artifactId,
                    artifactDownloadUrl = artifactUrl ?: build.artifactDownloadUrl,
                    apkFileName = if (build.apkFileName == null) "${build.projectName}-${build.buildVariant}.apk" else build.apkFileName,
                    completedAt = if (isFinished) System.currentTimeMillis() else null
                  )
                  buildDao.updateBuild(updated)
                }
              } else if (build.branchName.isNotBlank() && build.branchName.startsWith("build/")) {
                // Discover the runId if not captured yet
                val findResult = repo.findWorkflowRun(build.repoFullName, build.branchName, build.commitSha)
                if (findResult.isSuccess && findResult.getOrNull() != null) {
                  val matchedRun = findResult.getOrNull()!!
                  val updated = build.copy(
                    runId = matchedRun.id,
                    runUrl = matchedRun.htmlUrl ?: build.runUrl,
                    status = matchedRun.status ?: "in_progress"
                  )
                  buildDao.updateBuild(updated)
                }
              }
            }
          }
        } catch (_: Exception) {
          // Keep polling alive gracefully
        }
        delay(6000)
      }
    }
  }

  fun diagnoseBuildError(logs: String?): String {
    if (logs.isNullOrBlank()) return "Falha na compilação do Gradle."
    return when {
      logs.contains("google-services.json is missing", ignoreCase = true) ||
      logs.contains("File google-services.json is missing", ignoreCase = true) ->
        "Configuração Ausente: O arquivo 'google-services.json' é obrigatório para projetos com Firebase e não foi fornecido."

      logs.contains("Unsupported class file major version", ignoreCase = true) ||
      logs.contains("compiled by a more recent version of the Java Runtime", ignoreCase = true) ->
        "Incompatibilidade Java: A versão do Java exigida pelo projeto ou dependências é incompatível com o compilador em execução."

      logs.contains("Could not find", ignoreCase = true) && logs.contains("Searched in the following locations", ignoreCase = true) ->
        "Dependência Inexistente: O Gradle não conseguiu localizar uma ou mais bibliotecas nos repositórios Maven configurados."

      logs.contains("Namespace not specified", ignoreCase = true) ->
        "Namespace Ausente: O Android Gradle Plugin 8+ exige que a propriedade 'namespace' seja configurada no build.gradle."

      logs.contains("Unresolved reference", ignoreCase = true) ->
        "Erro de Sintaxe / Referência: O código Kotlin contém referências não resolvidas ou incompatibilidade de API."

      logs.contains("AAPT: error:", ignoreCase = true) || logs.contains("Android resource linking failed", ignoreCase = true) ->
        "Erro de Recursos (AAPT): Conflito de atributos ou recurso XML corrompido em res/."

      logs.contains("SigningConfig", ignoreCase = true) || logs.contains("Keystore file", ignoreCase = true) ->
        "Erro de Assinatura: Configuração de keystore inválida ou arquivo de chaves inacessível no build.gradle."

      logs.contains("Out of memory", ignoreCase = true) || logs.contains("Java heap space", ignoreCase = true) ->
        "Memória Insuficiente: O compilador do Gradle excedeu o limite de heap alocado na JVM."

      else ->
        "Falha na compilação do Gradle. Abra os logs detalhados para visualizar o rastreamento do erro."
    }
  }

  fun openLogsForBuild(build: ProjectBuildEntity) {
    _logsDialogState.value = LogsDialogState(
      isVisible = true,
      buildEntity = build,
      isLoading = true
    )

    viewModelScope.launch {
      val repo = gitHubRepository
      if (repo == null || build.runId == null) {
        _logsDialogState.value = _logsDialogState.value.copy(
          isLoading = false,
          error = "Informações de execução do GitHub não disponíveis para este build."
        )
        return@launch
      }

      val jobsResult = repo.getRunJobs(build.repoFullName, build.runId)
      val artifactsResult = repo.getArtifacts(build.repoFullName, build.runId)
      val jobsList = jobsResult.getOrNull().orEmpty()

      var rawLogs: String? = null
      val primaryJob = jobsList.firstOrNull()
      if (primaryJob != null) {
        val logsResult = repo.getJobLogs(build.repoFullName, primaryJob.id)
        if (logsResult.isSuccess) {
          rawLogs = logsResult.getOrNull()
        }
      }

      _logsDialogState.value = _logsDialogState.value.copy(
        isLoading = false,
        jobs = jobsList,
        artifacts = artifactsResult.getOrNull().orEmpty(),
        rawLogs = rawLogs,
        error = jobsResult.exceptionOrNull()?.localizedMessage
      )
    }
  }

  fun dismissLogsDialog() {
    _logsDialogState.value = LogsDialogState(isVisible = false)
  }

  fun downloadAndInstallApk(build: ProjectBuildEntity, context: Context) {
    // 1. If APK is already downloaded locally and file exists on disk, launch installer immediately!
    val existingPath = build.localApkPath
    if (!existingPath.isNullOrBlank()) {
      val localFile = File(existingPath)
      if (localFile.exists() && localFile.length() > 0) {
        launchApkInstaller(context, localFile)
        return
      }
    }

    // 2. Otherwise download directly from GitHub Actions
    val cleanToken = prefs.githubToken.value.trim()
    if (cleanToken.isBlank()) {
      _statusNotice.value = "Configure o token do GitHub nas configurações primeiro."
      return
    }

    if (gitHubRepository == null) {
      initializeApi(cleanToken)
    }

    val repo = gitHubRepository ?: run {
      _statusNotice.value = "Inicializando conexão com o GitHub..."
      return
    }

    if (_downloadingBuildIds.value.contains(build.id)) return

    viewModelScope.launch {
      _downloadingBuildIds.value = _downloadingBuildIds.value + build.id
      try {
        var targetArtifactId = build.artifactId
        var targetArtifactUrl = build.artifactDownloadUrl

        // If artifact ID is missing, query GitHub artifacts API
        if (targetArtifactId == null && build.runId != null) {
          val artifactsResult = repo.getArtifacts(build.repoFullName, build.runId)
          if (artifactsResult.isSuccess) {
            val list = artifactsResult.getOrNull().orEmpty()
            val apkArtifact = list.firstOrNull { it.name.contains("apk", ignoreCase = true) } ?: list.firstOrNull()
            if (apkArtifact != null) {
              targetArtifactId = apkArtifact.id
              targetArtifactUrl = apkArtifact.archiveDownloadUrl
            }
          }
        }

        val destDir = File(context.filesDir, "downloaded_apks")
        val baseName = build.projectName.ifBlank { "app" }

        val downloadResult = repo.downloadAndExtractApk(
          repoFullName = build.repoFullName,
          artifactId = targetArtifactId,
          artifactUrl = targetArtifactUrl,
          destinationDir = destDir,
          preferredName = "$baseName-${build.buildVariant.ifBlank { "release" }}"
        )

        if (downloadResult.isSuccess) {
          val apkFile = downloadResult.getOrNull()!!
          val updated = build.copy(
            localApkPath = apkFile.absolutePath,
            artifactId = targetArtifactId,
            artifactDownloadUrl = targetArtifactUrl,
            apkFileName = apkFile.name
          )
          buildDao.updateBuild(updated)

          // Immediately launch installer with the downloaded real APK file
          launchApkInstaller(context, apkFile)
        } else {
          _statusNotice.value = downloadResult.exceptionOrNull()?.localizedMessage ?: "Falha ao baixar o arquivo APK."
        }
      } catch (e: Exception) {
        _statusNotice.value = "Erro no download do APK: ${e.localizedMessage}"
      } finally {
        _downloadingBuildIds.value = _downloadingBuildIds.value - build.id
      }
    }
  }

  fun launchApkInstaller(context: Context, apkFile: File) {
    if (!apkFile.exists() || apkFile.length() == 0L) {
      Toast.makeText(context, "Arquivo APK não encontrado no armazenamento local.", Toast.LENGTH_SHORT).show()
      return
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!context.packageManager.canRequestPackageInstalls()) {
          val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(settingsIntent)
          Toast.makeText(
            context,
            "Permita a instalação de fontes desconhecidas para prosseguir.",
            Toast.LENGTH_LONG
          ).show()
          return
        }
      }

      val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
      )

      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

      context.startActivity(installIntent)
    } catch (e: Exception) {
      Toast.makeText(context, "Erro ao abrir instalador: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
  }

  fun deleteBuild(id: Long) {
    viewModelScope.launch {
      val build = buildDao.getBuildById(id)
      if (build?.localApkPath != null) {
        try {
          File(build.localApkPath).delete()
        } catch (_: Exception) {}
      }
      buildDao.deleteById(id)
    }
  }

  fun clearAllBuilds() {
    viewModelScope.launch {
      allBuilds.value.forEach { b ->
        if (b.localApkPath != null) {
          try {
            File(b.localApkPath).delete()
          } catch (_: Exception) {}
        }
      }
      buildDao.clearAll()
    }
  }

  override fun onCleared() {
    super.onCleared()
    pollingJob?.cancel()
  }
}
