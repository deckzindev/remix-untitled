package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ProjectFileInfo(
  val path: String,
  val base64Content: String,
  val sizeBytes: Long,
  val isExecutable: Boolean = false
)

data class ZipAnalysisResult(
  val isValidAndroidProject: Boolean,
  val detectedProjectName: String,
  val files: List<ProjectFileInfo>,
  val totalFiles: Int,
  val totalSizeBytes: Long,
  val hasGradleWrapper: Boolean,
  val hasSettingsGradle: Boolean,
  val hasAppModule: Boolean,
  val error: String? = null,
  val warning: String? = null,
  val ignoredFilesSummary: List<String> = emptyList(),
  val missingRequiredConfigs: List<String> = emptyList(),
  val detectedPackageName: String? = null
)

object ZipProjectParser {

  // Safety limits to prevent Denial of Service and Zip Bomb exploits
  const val MAX_UNCOMPRESSED_TOTAL_BYTES = 250L * 1024L * 1024L // 250 MB
  const val MAX_INDIVIDUAL_FILE_BYTES = 20L * 1024L * 1024L    // 20 MB per file
  const val MAX_TOTAL_FILES = 1500
  const val MAX_DIRECTORY_DEPTH = 16

  private val IGNORED_PATH_SEGMENTS = listOf(
    "/.gradle/",
    "/build/",
    "/.idea/",
    "/.git/",
    "/.kotlin/",
    "/.cxx/",
    "/__MACOSX/",
    ".gradle/",
    "build/",
    ".idea/",
    ".git/",
    ".kotlin/",
    ".cxx/",
    "__MACOSX/"
  )

  private val IGNORED_EXTENSIONS = listOf(
    ".apk",
    ".aab",
    ".dex",
    ".class",
    ".hprof",
    ".pyc"
  )

  private val IGNORED_FILES = setOf(
    "local.properties",
    ".DS_Store",
    "Thumbs.db",
    "desktop.ini"
  )

  fun analyzeAndExtractZip(context: Context, uri: Uri): ZipAnalysisResult {
    return try {
      val inputStream = context.contentResolver.openInputStream(uri)
        ?: return ZipAnalysisResult(
          isValidAndroidProject = false,
          detectedProjectName = "Desconhecido",
          files = emptyList(),
          totalFiles = 0,
          totalSizeBytes = 0,
          hasGradleWrapper = false,
          hasSettingsGradle = false,
          hasAppModule = false,
          error = "Não foi possível abrir o arquivo ZIP selecionado."
        )

      val result = parseZipStream(inputStream, extractFileName(context, uri))
      inputStream.close()
      result
    } catch (e: Exception) {
      ZipAnalysisResult(
        isValidAndroidProject = false,
        detectedProjectName = "Erro",
        files = emptyList(),
        totalFiles = 0,
        totalSizeBytes = 0,
        hasGradleWrapper = false,
        hasSettingsGradle = false,
        hasAppModule = false,
        error = e.localizedMessage ?: "Erro ao analisar arquivo ZIP."
      )
    }
  }

  fun parseZipStream(inputStream: InputStream, defaultProjectName: String): ZipAnalysisResult {
    val zipStream = ZipInputStream(inputStream)
    val rawEntries = mutableListOf<Pair<String, ByteArray>>()
    val ignoredList = mutableListOf<String>()
    var totalExtractedBytes = 0L
    var detectedProjectName = defaultProjectName.removeSuffix(".zip")

    var entry: ZipEntry?
    try {
      entry = zipStream.nextEntry
    } catch (e: Exception) {
      return ZipAnalysisResult(
        isValidAndroidProject = false,
        detectedProjectName = detectedProjectName,
        files = emptyList(),
        totalFiles = 0,
        totalSizeBytes = 0,
        hasGradleWrapper = false,
        hasSettingsGradle = false,
        hasAppModule = false,
        error = "O arquivo ZIP está corrompido ou em formato inválido: ${e.localizedMessage}"
      )
    }

    while (entry != null) {
      val entryName = entry.name.replace('\\', '/')

      // 1. Defesa Zip-Slip: Bloqueia caminhos com "../" que tentem escapar do diretório
      if (entryName.contains("../") || entryName.contains("/..") || entryName.startsWith("../")) {
        return ZipAnalysisResult(
          isValidAndroidProject = false,
          detectedProjectName = detectedProjectName,
          files = emptyList(),
          totalFiles = 0,
          totalSizeBytes = 0,
          hasGradleWrapper = false,
          hasSettingsGradle = false,
          hasAppModule = false,
          error = "Segurança: Arquivo ZIP rejeitado devido a tentativa de Path Traversal ('../' detectado em: $entryName)."
        )
      }

      // 2. Defesa de profundidade excessiva
      val depth = entryName.count { it == '/' }
      if (depth > MAX_DIRECTORY_DEPTH) {
        return ZipAnalysisResult(
          isValidAndroidProject = false,
          detectedProjectName = detectedProjectName,
          files = emptyList(),
          totalFiles = 0,
          totalSizeBytes = 0,
          hasGradleWrapper = false,
          hasSettingsGradle = false,
          hasAppModule = false,
          error = "Segurança: Estrutura de pastas excede a profundidade máxima permitida ($depth > $MAX_DIRECTORY_DEPTH)."
        )
      }

      if (!entry.isDirectory) {
        val cleanPath = entryName.trimStart('/')
        val fileName = cleanPath.substringAfterLast('/')

        val isSafeToIgnore = IGNORED_PATH_SEGMENTS.any { cleanPath.contains(it) } ||
            IGNORED_EXTENSIONS.any { cleanPath.endsWith(it, ignoreCase = true) } ||
            IGNORED_FILES.contains(fileName) ||
            cleanPath.startsWith(".github/workflows/")

        if (isSafeToIgnore) {
          ignoredList.add(cleanPath)
        } else {
          // 3. Defesa de contagem de arquivos
          if (rawEntries.size >= MAX_TOTAL_FILES) {
            return ZipAnalysisResult(
              isValidAndroidProject = false,
              detectedProjectName = detectedProjectName,
              files = emptyList(),
              totalFiles = 0,
              totalSizeBytes = 0,
              hasGradleWrapper = false,
              hasSettingsGradle = false,
              hasAppModule = false,
              error = "O projeto contém arquivos em excesso (limite: $MAX_TOTAL_FILES arquivos). Verifique se pastas de compilação locais foram incluídas no ZIP."
            )
          }

          val buffer = ByteArrayOutputStream()
          val temp = ByteArray(8192)
          var len: Int
          var fileBytesCount = 0L

          while (zipStream.read(temp).also { len = it } > 0) {
            fileBytesCount += len
            totalExtractedBytes += len

            // 4. Defesa de tamanho individual - NUNCA descarte silencioso!
            if (fileBytesCount > MAX_INDIVIDUAL_FILE_BYTES) {
              val fileSizeMB = fileBytesCount / (1024 * 1024)
              return ZipAnalysisResult(
                isValidAndroidProject = false,
                detectedProjectName = detectedProjectName,
                files = emptyList(),
                totalFiles = 0,
                totalSizeBytes = 0,
                hasGradleWrapper = false,
                hasSettingsGradle = false,
                hasAppModule = false,
                error = "Arquivo individual excede o limite máximo permitido: '$cleanPath' possui mais de $fileSizeMB MB (limite: ${MAX_INDIVIDUAL_FILE_BYTES / (1024 * 1024)} MB). Remova artefatos pesados do ZIP."
              )
            }

            // 5. Defesa Zip Bomb total descompactado
            if (totalExtractedBytes > MAX_UNCOMPRESSED_TOTAL_BYTES) {
              return ZipAnalysisResult(
                isValidAndroidProject = false,
                detectedProjectName = detectedProjectName,
                files = emptyList(),
                totalFiles = 0,
                totalSizeBytes = 0,
                hasGradleWrapper = false,
                hasSettingsGradle = false,
                hasAppModule = false,
                error = "Segurança: O tamanho descompactado do projeto excede o limite de ${MAX_UNCOMPRESSED_TOTAL_BYTES / (1024 * 1024)} MB (Zip Bomb Defense)."
              )
            }

            buffer.write(temp, 0, len)
          }

          rawEntries.add(cleanPath to buffer.toByteArray())
        }
      }

      zipStream.closeEntry()
      entry = try {
        zipStream.nextEntry
      } catch (e: Exception) {
        return ZipAnalysisResult(
          isValidAndroidProject = false,
          detectedProjectName = detectedProjectName,
          files = emptyList(),
          totalFiles = 0,
          totalSizeBytes = 0,
          hasGradleWrapper = false,
          hasSettingsGradle = false,
          hasAppModule = false,
          error = "Erro ao descompactar entrada do ZIP: ${e.localizedMessage}"
        )
      }
    }

    if (rawEntries.isEmpty()) {
      return ZipAnalysisResult(
        isValidAndroidProject = false,
        detectedProjectName = detectedProjectName,
        files = emptyList(),
        totalFiles = 0,
        totalSizeBytes = 0,
        hasGradleWrapper = false,
        hasSettingsGradle = false,
        hasAppModule = false,
        error = "O arquivo ZIP está vazio ou não contém arquivos de código válidos."
      )
    }

    // Identificação de raiz aninhada comum (ex: "MeuProjeto/...")
    val firstSlash = rawEntries.first().first.indexOf('/')
    val normalizedEntries: List<Pair<String, ByteArray>> = if (firstSlash > 0) {
      val candidatePrefix = rawEntries.first().first.substring(0, firstSlash + 1)
      val allSharePrefix = rawEntries.all { it.first.startsWith(candidatePrefix) }
      if (allSharePrefix) {
        detectedProjectName = candidatePrefix.removeSuffix("/")
        rawEntries.map { (path, bytes) -> path.removePrefix(candidatePrefix) to bytes }
      } else {
        rawEntries
      }
    } else {
      rawEntries
    }

    val filesList = mutableListOf<ProjectFileInfo>()
    var totalBytes = 0L
    var hasGradleWrapper = false
    var hasSettingsGradle = false
    var hasAppModule = false
    var detectedPackageName: String? = null
    val missingConfigs = mutableListOf<String>()

    for ((cleanPath, bytes) in normalizedEntries) {
      if (cleanPath.isBlank()) continue
      totalBytes += bytes.size

      if (cleanPath == "gradlew" || cleanPath.endsWith("/gradlew")) {
        hasGradleWrapper = true
      }
      if (cleanPath.startsWith("settings.gradle")) {
        hasSettingsGradle = true
      }
      if (cleanPath.startsWith("app/")) {
        hasAppModule = true
      }

      // Check package name from AndroidManifest.xml
      if (cleanPath == "app/src/main/AndroidManifest.xml" || cleanPath.endsWith("AndroidManifest.xml")) {
        val manifestContent = String(bytes, Charsets.UTF_8)
        val packageRegex = Regex("""package\s*=\s*"([^"]+)"""")
        packageRegex.find(manifestContent)?.let {
          detectedPackageName = it.groupValues[1]
        }
      }

      // Check for Google Services requirement
      if (cleanPath.endsWith("build.gradle") || cleanPath.endsWith("build.gradle.kts")) {
        val buildScript = String(bytes, Charsets.UTF_8)
        if (buildScript.contains("com.google.gms.google-services") || buildScript.contains("google-services")) {
          val hasGoogleServicesJson = normalizedEntries.any {
            it.first == "app/google-services.json" || it.first == "google-services.json"
          }
          if (!hasGoogleServicesJson && !missingConfigs.contains("google-services.json")) {
            missingConfigs.add("google-services.json")
          }
        }
      }

      val isExecutable = cleanPath == "gradlew" || cleanPath.endsWith("/gradlew") || cleanPath.endsWith(".sh")
      val base64 = encodeBase64(bytes)

      filesList.add(
        ProjectFileInfo(
          path = cleanPath,
          base64Content = base64,
          sizeBytes = bytes.size.toLong(),
          isExecutable = isExecutable
        )
      )
    }

    val isValid = hasSettingsGradle || hasAppModule || filesList.any { it.path.contains("build.gradle") }

    val errorMsg = if (missingConfigs.contains("google-services.json")) {
      "Atenção: O projeto utiliza o plugin do Google Services / Firebase, mas o arquivo 'google-services.json' não foi encontrado. Adicione o arquivo para compilar com sucesso."
    } else if (!isValid) {
      "Aviso: Estrutura padrão do Android (build.gradle/settings.gradle) não foi detectada. Verifique se o ZIP contém a raiz do projeto."
    } else null

    return ZipAnalysisResult(
      isValidAndroidProject = isValid,
      detectedProjectName = detectedProjectName.ifBlank { "AndroidApp" },
      files = filesList,
      totalFiles = filesList.size,
      totalSizeBytes = totalBytes,
      hasGradleWrapper = hasGradleWrapper,
      hasSettingsGradle = hasSettingsGradle,
      hasAppModule = hasAppModule,
      error = errorMsg,
      warning = if (ignoredList.isNotEmpty()) "Foram filtrados ${ignoredList.size} arquivos temporários de compilação local (build/ e caches)." else null,
      ignoredFilesSummary = ignoredList.take(20),
      missingRequiredConfigs = missingConfigs,
      detectedPackageName = detectedPackageName
    )
  }

  private fun extractFileName(context: Context, uri: Uri): String {
    var name = "projeto.zip"
    try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
          name = cursor.getString(nameIndex) ?: name
        }
      }
    } catch (_: Exception) {
      name = uri.lastPathSegment ?: "projeto.zip"
    }
    return name
  }

  fun encodeBase64(bytes: ByteArray): String {
    return try {
      Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (_: Throwable) {
      java.util.Base64.getEncoder().encodeToString(bytes)
    }
  }
}
