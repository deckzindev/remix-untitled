package com.example.data.workflow

object WorkflowTemplate {
  const val WORKFLOW_PATH = ".github/workflows/android-compile.yml"
  const val WORKFLOW_FILE_NAME = "android-compile.yml"

  val WORKFLOW_YAML = """
name: Studio APK Compiler CI

on:
  push:
    branches:
      - 'main'
      - 'build/**'
  workflow_dispatch:
    inputs:
      build_variant:
        description: 'Variante de compilação (debug ou release)'
        required: true
        default: 'release'
        type: choice
        options:
          - debug
          - release
      client_request_id:
        description: 'Identificador único da solicitação para idempotência'
        required: false
        default: ''

concurrency:
  group: studio-build-${'$'}{{ github.ref }}
  cancel-in-progress: false

jobs:
  build:
    name: Compilar APK Android
    runs-on: ubuntu-latest
    timeout-minutes: 35

    steps:
      - name: 📥 Clonar Código-Fonte Enviado
        uses: actions/checkout@v4

      - name: ☕ Configurar Java JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'gradle'

      - name: 🐘 Configurar Ambiente Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: 🔑 Configurar Keystore e Variáveis de Compilação
        run: |
          echo "=== 1. Gerando chaves de assinatura autônomas ==="
          keytool -genkeypair -alias androiddebugkey -keypass android -keystore debug.keystore -storepass android -dname "CN=Android Debug,O=StudioAPK,C=US" -validity 10000 -keyalg RSA -keysize 2048 || true
          keytool -genkeypair -alias upload -keypass android -keystore my-upload-key.jks -storepass android -dname "CN=Android Release,O=StudioAPK,C=US" -validity 10000 -keyalg RSA -keysize 2048 || true
          mkdir -p ~/.android
          cp debug.keystore ~/.android/debug.keystore || true
          mkdir -p app
          cp debug.keystore app/debug.keystore || true
          cp my-upload-key.jks app/my-upload-key.jks || true

          echo "=== 2. Configurando .env para Secrets Gradle Plugin ==="
          if [ ! -f ".env" ] && [ -f ".env.example" ]; then
            cp .env.example .env
          elif [ ! -f ".env" ]; then
            touch .env
          fi
          echo "FIREBASE_APPCHECK_DEBUG_TOKEN=dummy_token" >> .env

          echo "=== 3. Aceitando licenças do Android SDK ==="
          yes | sdkmanager --licenses || true

          echo "=== 4. Otimizando gradle.properties para ambiente CI ==="
          echo "org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" >> gradle.properties
          echo "android.useAndroidX=true" >> gradle.properties
          echo "android.builder.sdkDownload=true" >> gradle.properties

      - name: 🛠️ Garantir Permissões do Gradle Wrapper
        run: |
          if [ ! -f "./gradlew" ]; then
            echo "Aviso: gradlew não encontrado na raiz. Inicializando wrapper do Gradle..."
            gradle wrapper || true
          fi
          if [ -f "./gradlew" ]; then
            chmod +x ./gradlew
          fi

      - name: 🔨 Executar Compilação da Variante Solicitada
        env:
          STORE_PASSWORD: android
          KEY_PASSWORD: android
          KEYSTORE_PATH: ${'$'}{{ github.workspace }}/my-upload-key.jks
        run: |
          BUILD_TYPE="${'$'}{{ github.event.inputs.build_variant || 'release' }}"
          echo "=========================================================="
          echo " Iniciando compilação da variante: [${'$'}BUILD_TYPE]"
          echo "=========================================================="

          if [ "${'$'}BUILD_TYPE" = "debug" ]; then
            GRADLE_TASK="assembleDebug"
          else
            GRADLE_TASK="assembleRelease"
          fi

          echo "Executando tarefa: ${'$'}GRADLE_TASK"
          export STORE_PASSWORD=android
          export KEY_PASSWORD=android
          export KEYSTORE_PATH="${'$'}GITHUB_WORKSPACE/my-upload-key.jks"

          if [ -f "./gradlew" ]; then
            ./gradlew ${'$'}GRADLE_TASK --no-daemon --stacktrace
          else
            gradle ${'$'}GRADLE_TASK --no-daemon --stacktrace
          fi

      - name: 📦 Localizar e Validar APK da Variante
        run: |
          BUILD_TYPE="${'$'}{{ github.event.inputs.build_variant || 'release' }}"
          mkdir -p output_apks

          echo "Buscando APKs gerados correspondentes a [${'$'}BUILD_TYPE]..."
          find . -type f -name "*${'$'}{BUILD_TYPE}*.apk" -exec cp {} output_apks/ \;

          # Se não encontrou pelo nome exato da variante, buscar em caminhos padrão de outputs
          if [ -z "$(ls -A output_apks)" ]; then
            find . -path "*/build/outputs/apk/*" -type f -name "*.apk" -exec cp {} output_apks/ \;
          fi

          TOTAL_APKS=$(ls -1 output_apks/*.apk 2>/dev/null | wc -l)
          if [ "${'$'}TOTAL_APKS" -eq 0 ]; then
            echo "ERRO CRÍTICO: Nenhum arquivo APK correspondente à variante ${'$'}BUILD_TYPE foi gerado."
            exit 1
          fi

          echo "APKs validados com sucesso:"
          ls -la output_apks/

      - name: 🚀 Publicar Artefato APK
        uses: actions/upload-artifact@v4
        with:
          name: app-compiled-apk
          path: output_apks/*.apk
          retention-days: 14
          if-no-files-found: error
""".trimIndent()
}
