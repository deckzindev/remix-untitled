package com.example

import com.example.data.workflow.WorkflowTemplate
import com.example.util.SampleProjectGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun testWorkflowTemplateContainsEssentialSteps() {
    val yaml = WorkflowTemplate.WORKFLOW_YAML
    assertTrue("Workflow deve conter setup de Java JDK 17", yaml.contains("actions/setup-java@v4"))
    assertTrue("Workflow deve conter setup de Gradle", yaml.contains("gradle/actions/setup-gradle@v4"))
    assertTrue("Workflow deve compilar assembleDebug", yaml.contains("assembleDebug"))
    assertTrue("Workflow deve empacotar e publicar artefato APK", yaml.contains("actions/upload-artifact@v4"))
    assertTrue("Workflow deve conter geração de keystores", yaml.contains("debug.keystore"))
  }

  @Test
  fun testSampleProjectGeneratorCreatesValidAndroidProject() {
    val result = SampleProjectGenerator.generateSampleProject()
    assertNotNull(result)
    assertTrue("Projeto de exemplo deve ser marcado como projeto Android válido", result.isValidAndroidProject)
    assertTrue("Projeto deve conter configurações do Gradle", result.hasSettingsGradle)
    assertTrue("Projeto deve conter módulo de app", result.hasAppModule)
    assertTrue("Projeto deve conter arquivos válidos", result.totalFiles >= 5)

    val filePaths = result.files.map { it.path }
    assertTrue(filePaths.contains("settings.gradle.kts"))
    assertTrue(filePaths.contains("build.gradle.kts"))
    assertTrue(filePaths.contains("app/build.gradle.kts"))
    assertTrue(filePaths.contains("app/src/main/AndroidManifest.xml"))
    assertTrue(filePaths.contains("app/src/main/java/com/sample/app/MainActivity.kt"))
  }

  @Test
  fun testZipParserRejectsPathTraversalZipSlip() {
    val outputStream = java.io.ByteArrayOutputStream()
    val zipStream = java.util.zip.ZipOutputStream(outputStream)
    val evilEntry = java.util.zip.ZipEntry("../../etc/passwd")
    zipStream.putNextEntry(evilEntry)
    zipStream.write("malicious_data".toByteArray())
    zipStream.closeEntry()
    zipStream.close()

    val inputStream = java.io.ByteArrayInputStream(outputStream.toByteArray())
    val result = com.example.util.ZipProjectParser.parseZipStream(inputStream, "evil.zip")

    assertTrue("Deve rejeitar tentativa de ZipSlip / Path Traversal", !result.isValidAndroidProject)
    assertTrue("Mensagem deve avisar sobre Path Traversal", result.error?.contains("Path Traversal") == true)
  }

  @Test
  fun testCompatibilityEngineRecommendsJava17ForAgp8() {
    val sampleBuildGradle = """
      plugins {
        id("com.android.application") version "8.5.0"
      }
    """.trimIndent()
    val base64 = com.example.engine.CompatibilityEngine.encodeBase64String(sampleBuildGradle.toByteArray())
    val files = listOf(
      com.example.util.ProjectFileInfo(
        path = "build.gradle.kts",
        base64Content = base64,
        sizeBytes = sampleBuildGradle.length.toLong()
      )
    )

    val (updatedFiles, analysis) = com.example.engine.CompatibilityEngine.inspectAndAdapt(files)
    assertEquals("8.5.0", analysis.detectedAgpVersion)
    assertEquals("17", analysis.recommendedJavaVersion)
    assertTrue("Deve propor gradle-wrapper.properties quando ausente", analysis.hasMissingWrapper)
    assertTrue("Deve injetar arquivo gradle-wrapper.properties", updatedFiles.any { it.path == "gradle/wrapper/gradle-wrapper.properties" })
  }

  @Test
  fun testWorkflowTemplateDoesNotHaveSilentFallback() {
    val yaml = WorkflowTemplate.WORKFLOW_YAML
    assertTrue("Workflow não deve conter || ./gradlew assembleDebug como fallback silencioso", !yaml.contains("|| ./gradlew assembleDebug"))
    assertTrue("Workflow não deve criar google-services.json fictício com package com.example", !yaml.contains("package_name\": \"com.example"))
  }
}
