package com.example.data.remote

import android.util.Base64
import com.example.data.model.CreateBlobRequest
import com.example.data.model.CreateCommitRequest
import com.example.data.model.CreateOrUpdateFileRequest
import com.example.data.model.CreateRefRequest
import com.example.data.model.CreateRepoRequest
import com.example.data.model.CreateTreeRequest
import com.example.data.model.GitHubArtifact
import com.example.data.model.GitHubRepo
import com.example.data.model.GitHubUser
import com.example.data.model.GitTreeItem
import com.example.data.model.WorkflowJob
import com.example.data.model.WorkflowRun
import com.example.data.workflow.WorkflowTemplate
import com.example.util.ProjectFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

sealed class BuildStepProgress {
  data class UploadingFiles(val current: Int, val total: Int, val currentFile: String) : BuildStepProgress()
  data object CreatingTree : BuildStepProgress()
  data object CreatingCommit : BuildStepProgress()
  data object UpdatingBranch : BuildStepProgress()
  data object InstallingWorkflow : BuildStepProgress()
  data object WaitingForRunner : BuildStepProgress()
  data class WorkflowStarted(val runId: Long, val runUrl: String) : BuildStepProgress()
}

class GitHubRepository(private val apiService: GitHubApiService) {

  private suspend fun <T> withRetry(maxAttempts: Int = 3, delayMs: Long = 1200, block: suspend () -> T): T {
    var lastException: Exception? = null
    for (attempt in 1..maxAttempts) {
      try {
        return block()
      } catch (e: Exception) {
        lastException = e
        val isTransient = e is java.io.IOException ||
                          e is java.net.UnknownHostException ||
                          e is java.net.SocketTimeoutException ||
                          e is java.net.ConnectException ||
                          e.message?.contains("Unable to resolve host", ignoreCase = true) == true
        if (attempt < maxAttempts && isTransient) {
          kotlinx.coroutines.delay(delayMs * attempt)
        } else {
          throw e
        }
      }
    }
    throw lastException ?: Exception("Falha na comunicação com o servidor após tentativas.")
  }

  suspend fun validateToken(): Result<GitHubUser> = withContext(Dispatchers.IO) {
    try {
      val response = withRetry { apiService.getCurrentUser() }
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!)
      } else {
        Result.failure(Exception("Token inválido ou não autorizado (Código HTTP ${response.code()})"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getUserRepos(): Result<List<GitHubRepo>> = withContext(Dispatchers.IO) {
    try {
      val response = apiService.getUserRepos()
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!)
      } else {
        Result.failure(Exception("Erro ao buscar repositórios (${response.code()})"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun createCompilerRepository(repoName: String): Result<GitHubRepo> = withContext(Dispatchers.IO) {
    try {
      val req = CreateRepoRequest(
        name = repoName,
        description = "Repositório dedicado para compilação de APKs com Studio APK",
        private = true,
        autoInit = true
      )
      val response = apiService.createRepo(req)
      if (response.isSuccessful && response.body() != null) {
        val repo = response.body()!!
        // Install workflow immediately in new repo
        installWorkflow(repo.fullName)
        Result.success(repo)
      } else {
        Result.failure(Exception("Erro ao criar repositório (${response.code()}: ${response.errorBody()?.string()})"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun installWorkflow(repoFullName: String): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val owner = parts[0]
      val repo = parts[1]

      // Check if workflow file exists
      val existing = apiService.getFileContent(owner, repo, WorkflowTemplate.WORKFLOW_PATH)
      val sha = if (existing.isSuccessful) existing.body()?.sha else null

      val base64Workflow = Base64.encodeToString(
        WorkflowTemplate.WORKFLOW_YAML.toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP
      )

      val request = CreateOrUpdateFileRequest(
        message = "ci: Configurar workflow de compilação Studio APK",
        content = base64Workflow,
        sha = sha
      )

      val response = apiService.createOrUpdateFile(owner, repo, WorkflowTemplate.WORKFLOW_PATH, request)
      if (response.isSuccessful) {
        Result.success(true)
      } else {
        Result.failure(Exception("Falha ao gravar workflow: HTTP ${response.code()}"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun pushProjectAndStartBuild(
    repoFullName: String,
    projectName: String,
    files: List<ProjectFileInfo>,
    buildVariant: String,
    onProgress: (BuildStepProgress) -> Unit
  ): Result<Triple<String, Long?, String?>> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Formato de repositório inválido. Use usuário/repositório"))
      val owner = parts[0]
      val repo = parts[1]

      // 1. Garantir que o repositório existe (cria caso retorne 404)
      var defaultBranch = "main"
      val checkResp = withRetry { apiService.getFileContent(owner, repo, "README.md") }
      if (checkResp.code() == 404) {
        val userRepos = withRetry { apiService.getUserRepos(perPage = 100) }
        val exists = userRepos.body()?.any { it.name.equals(repo, ignoreCase = true) } == true
        if (!exists) {
          val createReq = CreateRepoRequest(
            name = repo,
            description = "Repositório de compilação automatizada com Studio APK",
            private = true,
            autoInit = true
          )
          val createdResp = withRetry { apiService.createRepo(createReq) }
          if (createdResp.isSuccessful) {
            kotlinx.coroutines.delay(2000)
            installWorkflow(repoFullName)
          }
        }
      }

      // 2. Garantir que o workflow CI está presente no branch principal para que as Actions sejam disparadas
      try {
        val userRepos = withRetry { apiService.getUserRepos(perPage = 100) }
        val matchingRepo = userRepos.body()?.find { it.fullName.equals(repoFullName, ignoreCase = true) }
        if (matchingRepo != null && matchingRepo.defaultBranch.isNotBlank()) {
          defaultBranch = matchingRepo.defaultBranch
        }
      } catch (_: Exception) {}

      val existingWf = try {
        apiService.getFileContent(owner, repo, WorkflowTemplate.WORKFLOW_PATH, ref = defaultBranch)
      } catch (_: Exception) { null }

      if (existingWf == null || !existingWf.isSuccessful || existingWf.body()?.sha == null) {
        onProgress(BuildStepProgress.InstallingWorkflow)
        installWorkflow(repoFullName)
      }

      val safeName = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
      val branchName = "build/$safeName-${System.currentTimeMillis()}"

      val treeItems = Collections.synchronizedList(mutableListOf<GitTreeItem>())
      val semaphore = Semaphore(4)
      val completedCounter = AtomicInteger(0)
      val totalFilesToUpload = files.size + 1

      // 3. Upload de blobs em lotes paralelos controlados (Semaphore com 4 workers concorrentes)
      coroutineScope {
        val uploadJobs = files.map { fileInfo ->
          async {
            semaphore.withPermit {
              val blobReq = CreateBlobRequest(content = fileInfo.base64Content)
              val blobResp = withRetry(maxAttempts = 4, delayMs = 1200) {
                apiService.createBlob(owner, repo, blobReq)
              }
              if (!blobResp.isSuccessful || blobResp.body() == null) {
                throw Exception("Falha ao enviar arquivo ${fileInfo.path}: HTTP ${blobResp.code()}")
              }
              val current = completedCounter.incrementAndGet()
              onProgress(BuildStepProgress.UploadingFiles(current, totalFilesToUpload, fileInfo.path))
              val mode = if (fileInfo.isExecutable) "100755" else "100644"
              treeItems.add(
                GitTreeItem(
                  path = fileInfo.path,
                  mode = mode,
                  type = "blob",
                  sha = blobResp.body()!!.sha
                )
              )
            }
          }
        }
        uploadJobs.awaitAll()
      }

      // 4. Inserir arquivo de workflow no branch do build
      val workflowBase64 = Base64.encodeToString(
        WorkflowTemplate.WORKFLOW_YAML.toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP
      )
      val wfBlob = withRetry { apiService.createBlob(owner, repo, CreateBlobRequest(content = workflowBase64)) }
      if (wfBlob.isSuccessful && wfBlob.body() != null) {
        treeItems.add(
          GitTreeItem(
            path = WorkflowTemplate.WORKFLOW_PATH,
            mode = "100644",
            type = "blob",
            sha = wfBlob.body()!!.sha
          )
        )
      }

      // 5. Criar Árvore Git
      onProgress(BuildStepProgress.CreatingTree)
      val treeReq = CreateTreeRequest(tree = treeItems.toList())
      val treeResp = withRetry { apiService.createTree(owner, repo, treeReq) }
      if (!treeResp.isSuccessful || treeResp.body() == null) {
        throw Exception("Falha ao criar árvore Git: HTTP ${treeResp.code()}")
      }
      val treeSha = treeResp.body()!!.sha

      // 6. Obter commit pai do branch default se existir
      val baseParentSha = try {
        apiService.getBranchRef(owner, repo, defaultBranch).body()?.gitObject?.sha
      } catch (_: Exception) { null }

      // 7. Criar Commit
      onProgress(BuildStepProgress.CreatingCommit)
      val commitReq = CreateCommitRequest(
        message = "Studio APK: Compilando $projectName [$buildVariant]",
        tree = treeSha,
        parents = if (baseParentSha != null) listOf(baseParentSha) else emptyList()
      )
      val commitResp = withRetry { apiService.createCommit(owner, repo, commitReq) }
      if (!commitResp.isSuccessful || commitResp.body() == null) {
        throw Exception("Falha ao criar commit: HTTP ${commitResp.code()}")
      }
      val commitSha = commitResp.body()!!.sha

      // 8. Criar Branch ref
      onProgress(BuildStepProgress.UpdatingBranch)
      val refReq = CreateRefRequest(
        ref = "refs/heads/$branchName",
        sha = commitSha
      )
      val refResp = withRetry { apiService.createRef(owner, repo, refReq) }
      if (!refResp.isSuccessful) {
        throw Exception("Falha ao criar branch $branchName: HTTP ${refResp.code()}")
      }

      // 9. Aguardar inicialização do runner no GitHub Actions (até 30s)
      onProgress(BuildStepProgress.WaitingForRunner)
      var runId: Long? = null
      var runUrl: String? = null

      for (attempt in 1..15) {
        kotlinx.coroutines.delay(2000)
        val runsResp = apiService.getWorkflowRuns(owner, repo, perPage = 10, branch = branchName)
        if (runsResp.isSuccessful) {
          val runs = runsResp.body()?.workflowRuns.orEmpty()
          val matched = runs.firstOrNull { it.headSha == commitSha || it.headBranch == branchName }
          if (matched != null) {
            runId = matched.id
            runUrl = matched.htmlUrl
            onProgress(BuildStepProgress.WorkflowStarted(matched.id, matched.htmlUrl ?: ""))
            break
          }
        }
      }

      // Fallback: se não iniciou automaticamente em 30s, aciona via workflow_dispatch
      if (runId == null) {
        try {
          apiService.dispatchWorkflow(
            owner = owner,
            repo = repo,
            workflowId = WorkflowTemplate.WORKFLOW_FILE_NAME,
            payload = com.example.data.model.WorkflowDispatchPayload(
              ref = branchName,
              inputs = mapOf("build_variant" to buildVariant)
            )
          )
          for (attempt in 1..6) {
            kotlinx.coroutines.delay(2000)
            val runsResp = apiService.getWorkflowRuns(owner, repo, perPage = 10, branch = branchName)
            if (runsResp.isSuccessful) {
              val runs = runsResp.body()?.workflowRuns.orEmpty()
              val matched = runs.firstOrNull { it.headSha == commitSha || it.headBranch == branchName }
              if (matched != null) {
                runId = matched.id
                runUrl = matched.htmlUrl
                onProgress(BuildStepProgress.WorkflowStarted(matched.id, matched.htmlUrl ?: ""))
                break
              }
            }
          }
        } catch (_: Exception) {}
      }

      Result.success(Triple(branchName, runId, commitSha))
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun findWorkflowRun(repoFullName: String, branchName: String, commitSha: String?): Result<WorkflowRun?> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val response = apiService.getWorkflowRuns(parts[0], parts[1], perPage = 10, branch = branchName)
      if (response.isSuccessful) {
        val runs = response.body()?.workflowRuns.orEmpty()
        val matched = runs.firstOrNull {
          (commitSha != null && it.headSha == commitSha) || it.headBranch == branchName
        }
        Result.success(matched)
      } else {
        Result.failure(Exception("HTTP ${response.code()} ao buscar execuções"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getRunStatus(repoFullName: String, runId: Long): Result<WorkflowRun> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val response = apiService.getWorkflowRun(parts[0], parts[1], runId)
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!)
      } else {
        Result.failure(Exception("HTTP ${response.code()} ao consultar execução"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getRunJobs(repoFullName: String, runId: Long): Result<List<WorkflowJob>> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val response = apiService.getWorkflowJobs(parts[0], parts[1], runId)
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!.jobs)
      } else {
        Result.failure(Exception("HTTP ${response.code()} ao buscar passos"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getArtifacts(repoFullName: String, runId: Long): Result<List<GitHubArtifact>> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val response = apiService.getWorkflowArtifacts(parts[0], parts[1], runId)
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!.artifacts)
      } else {
        Result.failure(Exception("HTTP ${response.code()} ao buscar artefatos"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getJobLogs(repoFullName: String, jobId: Long): Result<String> = withContext(Dispatchers.IO) {
    try {
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val response = apiService.getJobLogs(parts[0], parts[1], jobId)
      if (response.isSuccessful && response.body() != null) {
        Result.success(response.body()!!.string())
      } else {
        Result.failure(Exception("HTTP ${response.code()} ao buscar logs da execução"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun downloadAndExtractApk(
    repoFullName: String,
    artifactId: Long?,
    artifactUrl: String?,
    destinationDir: File,
    preferredName: String
  ): Result<File> = withContext(Dispatchers.IO) {
    try {
      destinationDir.mkdirs()
      val parts = repoFullName.split("/")
      if (parts.size != 2) return@withContext Result.failure(Exception("Nome de repositório inválido"))
      val owner = parts[0]
      val repo = parts[1]

      val response = if (artifactId != null && artifactId > 0) {
        apiService.downloadArtifactZip(owner, repo, artifactId)
      } else if (!artifactUrl.isNullOrBlank()) {
        apiService.downloadFromUrl(artifactUrl)
      } else {
        return@withContext Result.failure(Exception("Nenhum artefato disponível para download"))
      }

      if (!response.isSuccessful || response.body() == null) {
        return@withContext Result.failure(Exception("Falha no download do artefato: HTTP ${response.code()}"))
      }

      val body = response.body()!!
      val sanitizedBaseName = preferredName.replace(Regex("[^a-zA-Z0-9._-]"), "_").removeSuffix(".apk")
      val outputFile = File(destinationDir, "$sanitizedBaseName.apk")

      var foundApk = false
      ZipInputStream(body.byteStream().buffered()).use { zipIn ->
        var entry = zipIn.nextEntry
        while (entry != null) {
          val cleanEntryName = entry.name.replace('\\', '/')
          if (!entry.isDirectory && cleanEntryName.endsWith(".apk", ignoreCase = true)) {
            outputFile.outputStream().buffered().use { out ->
              zipIn.copyTo(out)
            }
            if (outputFile.exists() && outputFile.length() > 0) {
              foundApk = true
              zipIn.closeEntry()
              break
            }
          }
          zipIn.closeEntry()
          entry = zipIn.nextEntry
        }
      }

      if (!foundApk || outputFile.length() == 0L) {
        return@withContext Result.failure(Exception("Nenhum arquivo APK válido foi encontrado no pacote baixado."))
      }

      Result.success(outputFile)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
