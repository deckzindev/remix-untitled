package com.example.engine

import com.example.util.ProjectFileInfo
import java.nio.charset.StandardCharsets

data class CompatibilityAnalysis(
  val detectedAgpVersion: String?,
  val detectedGradleVersion: String?,
  val detectedKotlinVersion: String?,
  val recommendedJavaVersion: String, // "17", "11", or "8"
  val detectedCompileSdk: Int?,
  val detectedMinSdk: Int?,
  val detectedTargetSdk: Int?,
  val hasMissingWrapper: Boolean,
  val missingRequirements: List<String>,
  val proposedModifications: List<EnginePatch>,
  val strategyUsed: String
)

data class EnginePatch(
  val filePath: String,
  val description: String,
  val patchType: String, // "WRAPPER_CREATION", "JAVA_ALIGNMENT", "SDK_UPGRADE"
  val originalContent: String?,
  val updatedContent: String
)

object CompatibilityEngine {

  /**
   * Analyzes project files and determines the compatibility requirements and necessary patches.
   * Strategies: "compatibility" (default), "modernization", "recovery"
   */
  fun inspectAndAdapt(
    files: List<ProjectFileInfo>,
    strategy: String = "compatibility"
  ): Pair<List<ProjectFileInfo>, CompatibilityAnalysis> {
    var detectedAgp: String? = null
    var detectedGradle: String? = null
    var detectedKotlin: String? = null
    var compileSdk: Int? = null
    var minSdk: Int? = null
    var targetSdk: Int? = null
    var hasWrapperProperties = false
    val missingRequirements = mutableListOf<String>()
    val proposedPatches = mutableListOf<EnginePatch>()

    val updatedFiles = files.toMutableList()

    // 1. Inspect Gradle Wrapper properties
    val wrapperFile = files.find { it.path == "gradle/wrapper/gradle-wrapper.properties" }
    if (wrapperFile != null) {
      hasWrapperProperties = true
      val content = decodeBase64(wrapperFile.base64Content)
      val gradleVersionRegex = Regex("""gradle-([0-9.]+)-(bin|all)\.zip""")
      gradleVersionRegex.find(content)?.let {
        detectedGradle = it.groupValues[1]
      }
    }

    // 2. Inspect root and module build files
    for (file in files) {
      if (file.path.endsWith("build.gradle") || file.path.endsWith("build.gradle.kts") || file.path == "gradle/libs.versions.toml") {
        val content = decodeBase64(file.base64Content)

        // AGP detection
        if (detectedAgp == null) {
          val agpRegex = Regex("""com\.android\.tools\.build:gradle:([0-9.]+)""")
          val agpPluginRegex = Regex("""id\(?["']com\.android\.application["']\)?\s*version\s*["']([0-9.]+)["']""")
          val tomlAgpRegex = Regex("""agp\s*=\s*["']([0-9.]+)["']""")

          agpRegex.find(content)?.let { detectedAgp = it.groupValues[1] }
            ?: agpPluginRegex.find(content)?.let { detectedAgp = it.groupValues[1] }
            ?: tomlAgpRegex.find(content)?.let { detectedAgp = it.groupValues[1] }
        }

        // Kotlin detection
        if (detectedKotlin == null) {
          val kotlinRegex = Regex("""kotlin-gradle-plugin:([0-9.]+)""")
          val kotlinPluginRegex = Regex("""id\(?["']org\.jetbrains\.kotlin\.android["']\)?\s*version\s*["']([0-9.]+)["']""")
          val tomlKotlinRegex = Regex("""kotlin\s*=\s*["']([0-9.]+)["']""")

          kotlinRegex.find(content)?.let { detectedKotlin = it.groupValues[1] }
            ?: kotlinPluginRegex.find(content)?.let { detectedKotlin = it.groupValues[1] }
            ?: tomlKotlinRegex.find(content)?.let { detectedKotlin = it.groupValues[1] }
        }

        // SDK values
        val compileSdkRegex = Regex("""compileSdk(?:Version)?\s*=?\s*([0-9]{2})""")
        compileSdkRegex.find(content)?.let { compileSdk = it.groupValues[1].toIntOrNull() }

        val minSdkRegex = Regex("""minSdk(?:Version)?\s*=?\s*([0-9]{2})""")
        minSdkRegex.find(content)?.let { minSdk = it.groupValues[1].toIntOrNull() }

        val targetSdkRegex = Regex("""targetSdk(?:Version)?\s*=?\s*([0-9]{2})""")
        targetSdkRegex.find(content)?.let { targetSdk = it.groupValues[1].toIntOrNull() }
      }
    }

    // 3. Determine recommended Java version based on AGP compatibility matrix
    val recommendedJava = when {
      detectedAgp != null && (detectedAgp!!.startsWith("8.") || detectedAgp!!.startsWith("9.")) -> "17"
      detectedAgp != null && detectedAgp!!.startsWith("7.") -> "17"
      detectedAgp != null && (detectedAgp!!.startsWith("4.") || detectedAgp!!.startsWith("3.")) -> "11"
      detectedGradle != null && (detectedGradle!!.startsWith("8.") || detectedGradle!!.startsWith("7.")) -> "17"
      else -> "17"
    }

    // 4. Handle Missing Gradle Wrapper: Provision official wrapper properties if absent
    if (!hasWrapperProperties) {
      val targetGradleVersion = when {
        detectedAgp != null && detectedAgp!!.startsWith("8.") -> "8.7"
        detectedAgp != null && detectedAgp!!.startsWith("7.") -> "7.5"
        else -> "8.7"
      }
      val wrapperContent = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-$targetGradleVersion-bin.zip
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
      """.trimIndent()

      val patch = EnginePatch(
        filePath = "gradle/wrapper/gradle-wrapper.properties",
        description = "Gerar gradle-wrapper.properties ausente compatível com Gradle $targetGradleVersion",
        patchType = "WRAPPER_CREATION",
        originalContent = null,
        updatedContent = wrapperContent
      )
      proposedPatches.add(patch)

      // Add to files
      val base64 = encodeBase64String(wrapperContent.toByteArray(StandardCharsets.UTF_8))
      updatedFiles.add(
        ProjectFileInfo(
          path = "gradle/wrapper/gradle-wrapper.properties",
          base64Content = base64,
          sizeBytes = wrapperContent.toByteArray().size.toLong(),
          isExecutable = false
        )
      )
    }

    // 5. Strategy-specific adaptations:
    if (strategy == "modernization") {
      // If legacy compileSdk is lower than 34, propose modernization patch if requested
      if (compileSdk != null && compileSdk!! < 33) {
        val patch = EnginePatch(
          filePath = "app/build.gradle",
          description = "Modernizar compileSdk de $compileSdk para 34 para compatibilidade com bibliotecas recentes",
          patchType = "SDK_UPGRADE",
          originalContent = "compileSdk = $compileSdk",
          updatedContent = "compileSdk = 34"
        )
        proposedPatches.add(patch)
      }
    }

    val analysis = CompatibilityAnalysis(
      detectedAgpVersion = detectedAgp,
      detectedGradleVersion = detectedGradle,
      detectedKotlinVersion = detectedKotlin,
      recommendedJavaVersion = recommendedJava,
      detectedCompileSdk = compileSdk,
      detectedMinSdk = minSdk,
      detectedTargetSdk = targetSdk,
      hasMissingWrapper = !hasWrapperProperties,
      missingRequirements = missingRequirements,
      proposedModifications = proposedPatches,
      strategyUsed = strategy
    )

    return Pair(updatedFiles, analysis)
  }

  fun encodeBase64String(bytes: ByteArray): String {
    return try {
      java.util.Base64.getEncoder().encodeToString(bytes)
    } catch (_: Throwable) {
      try {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
      } catch (_: Throwable) {
        ""
      }
    }
  }

  fun decodeBase64(base64: String): String {
    return try {
      String(java.util.Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8)
    } catch (_: Throwable) {
      try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        String(bytes, StandardCharsets.UTF_8)
      } catch (_: Throwable) {
        ""
      }
    }
  }
}
