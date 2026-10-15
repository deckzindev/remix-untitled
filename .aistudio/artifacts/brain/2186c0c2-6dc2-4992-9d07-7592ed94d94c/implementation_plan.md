# Studio APK - Reestruturação e Motor Inteligente de Compilação

Um plano definitivo e integral para auditar, corrigir e evoluir o Studio APK em uma ferramenta profissional de compilação Android em nuvem, eliminando execuções duplicadas, garantindo fidelidade estrita de variantes (Release vs Debug), protegendo o token com o Android KeyStore e introduzindo um Motor Inteligente de Compatibilidade e Auto-Correção.

## User Review & Critical Decisions

> [!IMPORTANT]
> Decisões confirmadas pelo usuário para guiar a implementação:
> - **Estratégia Padrão do Motor**: Modo Compatibilidade por padrão (preservar as configurações originais do projeto, aplicando apenas intervenções mínimas e comprovadamente seguras para viabilizar a compilação).
> - **Ciclos de Auto-Correção**: Limite de até 3 tentativas automáticas de correção por solicitação de compilação, com diff registrado e reversão de regressões.
> - **Configurações Obrigatórias Ausentes (ex: `google-services.json`)**: Interromper a compilação de forma controlada e solicitar o arquivo ao usuário — nunca gerar arquivos fictícios com pacotes genéricos.

---

## 1. Auditoria Completa do Projeto Existente

A investigação detalhada do código-fonte confirmou os 10 problemas críticos levantados:

| ID | Arquivo Afetado | Causa Raiz Identificada | Gravidade | Solução Arquitetural | Teste de Validação |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **P1** | `WorkflowTemplate.kt` (linhas 116-123) | Fallback automático silencioso para `assembleDebug` quando `assembleRelease` falha. | **CRÍTICA** | Remover qualquer fallback silencioso. Se Release falhar, registrar erro legítimo e preservar logs exatos. | Testar build Release forçando falha e comprovar ausência de execução Debug. |
| **P2** | `GitHubRepository.kt` (linhas 270-305) | Disparo duplo via `dispatchWorkflow` quando polling inicial da API não encontra a execução do push imediatamente. | **ALTA** | Idempotência estrita: usar commit SHA único, chave de idempotência e reconciliação sem disparar duplicatas. | Simular atraso na API e verificar que apenas 1 workflow run é iniciado. |
| **P3** | `ZipProjectParser.kt` (linha 125) | Condição `if (bytes.size <= 15MB)` descarta arquivos grandes silenciosamente sem alertar o usuário. | **ALTA** | Registrar violação de tamanho explicitamente, listar o nome do arquivo que excedeu o limite e interromper com diagnóstico claro. | Testar ZIP com arquivo >15MB e verificar interrupção com mensagem explicativa. |
| **P4** | `WorkflowTemplate.kt` (linhas 72-76) | Criação de `google-services.json` fictício com package `com.example` mascarando ausência do arquivo real. | **ALTA** | Eliminar injeção de JSON falso no CI. Se o plugin Google Services estiver ativo e o JSON faltar, acusar falta de configuração. | Testar projeto com plugin Google Services sem JSON e validar parada controlada. |
| **P5** | `PreferencesManager.kt` (linha 11) | Token do GitHub gravado em plaintext em SharedPreferences padrão (`gitbuilder_prefs`). | **CRÍTICA** | Criar `CryptoManager` com criptografia AES-256-GCM backed pelo hardware Android KeyStore (`AndroidKeyStore`). | Validar que o token armazenado em disco é criptografado e ilegível sem chave privada. |
| **P6** | `ZipProjectParser.kt` | Ausência de limites globais para ZIP bombs, caminhos `../` (zip-slip traversal), contagem de arquivos e profundidade. | **ALTA** | Implementar `SafeZipExtractor` com validação de canonical path contra path traversal, limite uncompressed de 250MB e contagem de até 1500 arquivos. | Testar ZIP malicioso com `../` e arquivos recursivos para validar bloqueio seguro. |
| **P7** | Novo módulo | Inexistência de analisador de matriz de compatibilidade (AGP, Gradle, Java, compileSdk, minSdk). | **ALTA** | Construir `CompatibilityEngine` com matriz oficial de versões (AGP 7.x-8.x, Java 8/11/17/21, Gradle 6.x-8.x). | Analisar projetos legados (ex: Java 8 / AGP 7.2) e comprovar detecção correta. |
| **P8** | `WorkflowTemplate.kt` / `ZipProjectParser.kt` | Se `gradlew` ou `gradle-wrapper.properties` faltar, o projeto pode falhar sem diagnóstico da versão necessária. | **MÉDIA** | `CompatibilityEngine` detecta a versão ideal do Gradle e provisiona o wrapper correto de forma auditável e transparente. | Testar projeto sem pasta `gradle/wrapper/` e verificar injeção do wrapper apropriado. |
| **P9** | `BuilderViewModel.kt` | Algumas rotinas usavam variantes fixas ou nomeavam artefatos como release independentemente da seleção. | **MÉDIA** | Sincronizar parâmetro de variante ponta a ponta: requisição, build task, artefato gerado e identificação no banco. | Testar seleção explícita de Debug e comprovar geração e identificação de Debug APK. |
| **P10** | `app/build.gradle.kts` / `metadata.json` | `metadata.json` e recursos de nomes precisam estar estritamente alinhados e sem vazamento de segredos. | **BAIXA** | Alinhamento e auditoria preventiva em `.env.example`, `strings.xml`, `AndroidManifest.xml` e ProGuard. | Verificar build release e ausência de tokens em BuildConfig/logs. |

---

## 2. Visão Geral e Conceito do Produto

### O Que É o Studio APK Evoluído
Uma estação móvel de engenharia Android que permite a desenvolvedores e estudantes transformar código-fonte em pacotes APK reais diretamente do smartphone. O aplicativo inspeciona o projeto ZIP com regras de segurança rígidas, avalia a compatibilidade de ferramentas de compilação, propõe ou aplica correções não-destrutivas (em cópia de trabalho isolada) e orquestra a compilação no GitHub Actions com rastreabilidade absoluta e idempotência.

### Modos de Operação do Motor de Compatibilidade
1. **Modo Compatibilidade (Padrão Recomendado)**: Mantém o código e scripts de build originais, ajustando somente variáveis de ambiente mínimas (ex: JVM version, JDK 17 vs 11, licenças SDK) necessárias para a execução com as versões originais do projeto.
2. **Modo Modernização**: Atualiza controladamente o AGP, Gradle Wrapper e plugins obsoletos para padrões modernos estáveis, registrando diffs das alterações.
3. **Modo Recuperação**: Disparado após falhas de compilação, diagnostica a causa raiz no log do Gradle, propõe correção isolada e reverte automaticamente em caso de regressão.

---

## 3. Experiência do Usuário e Interface

### Fluxo Ponta a Ponta
```
┌─────────────────┐     ┌───────────────────┐     ┌───────────────────┐
│ 1. Seleção ZIP  │ ──► │ 2. Sanitização &  │ ──► │ 3. Relatório de   │
│ (ou Projeto Ex) │     │ Validação Segura  │     │ Compatibilidade   │
└─────────────────┘     └───────────────────┘     └─────────┬─────────┘
                                                            │
┌─────────────────┐     ┌───────────────────┐               ▼
│ 6. Download &   │ ◄── │ 5. Monitoramento  │ ◄── ┌───────────────────┐
│ Instalação APK  │     │ Real Idempotente  │     │ 4. Envio Paralelo │
└─────────────────┘     └───────────────────┘     │ & Disparo Único   │
                                                  └───────────────────┘
```

1. **Seleção & Pre-flight**: Usuário seleciona o ZIP. O extrator seguro valida hashes, profundidade e ausência de zip-slip.
2. **Diagnóstico Imediato**: Exibição da estrutura detectada: módulos, Gradle, AGP, Java target, plugins e dependências. Se faltar `google-services.json` ou chaves privadas, um alerta bloqueante orienta o envio do arquivo.
3. **Seleção de Variante Consciente**: Alternador explícito entre **Release (Assinado)** e **Debug**.
4. **Execução Transparente**: Barra de progresso com porcentagem determinística durante o envio em lotes paralelos (`Semaphore(4)`).
5. **Acompanhamento Remoto Fiel**: Sincronização direta com a API do GitHub Actions, exibindo passos reais (`setup-java`, `assembleRelease`, `upload-artifact`).
6. **Entrega de Logs & Diagnóstico**: Se houver falha, botão para ver e copiar os logs completos com identificação do erro do Gradle (ex: sintaxe, dependência não resolvida).

---

## 4. Arquitetura Técnica e Estratégia de Dados

### Diagrama de Componentes do Sistema

```
┌────────────────────────────────────────────────────────────────────────┐
│                        STUDIO APK - CORE ENGINE                        │
├──────────────────────────┬──────────────────────────┬──────────────────┤
│        UI LAYER          │      SECURITY LAYER      │   LOCAL STORAGE  │
│  • BuildScreen           │  • CryptoManager         │  • AppDatabase   │
│  • HistoryScreen         │    (KeyStore AES-256)    │    (Room DAO)    │
│  • SettingsScreen        │  • SafeZipExtractor      │  • Encrypted     │
│  • LogsViewerDialog      │    (ZipSlip & Bombs)     │    Preferences   │
└────────────┬─────────────┴────────────┬─────────────┴────────┬─────────┘
             │                          │                      │
             ▼                          ▼                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          BuilderViewModel                              │
│  • Coordenação do Ciclo de Build       • Gestão de Estado Idempotente  │
│  • Despacho do Motor de Compatibilidade• Sincronização em Background   │
└────────────┬─────────────────────────────────────────────────┬─────────┘
             │                                                 │
             ▼                                                 ▼
┌─────────────────────────────┐               ┌──────────────────────────┐
│     CompatibilityEngine     │               │     GitHubRepository     │
│ • Detect AGP/Gradle/Java    │               │ • Upload Paralelo Seguro │
│ • Compatibility Matrix      │               │ • Idempotent Run Poller  │
│ • Safe Patch & Diff Logger  │               │ • S3 Stream APK Download │
└─────────────────────────────┘               └─────────────┬────────────┘
                                                            │
                                                            ▼
                                              ┌──────────────────────────┐
                                              │   GitHub Actions CI      │
                                              │ • JDK 17/11 Matrix       │
                                              │ • assembleRelease Único  │
                                              │ • Validated Artifact Zip │
                                              └──────────────────────────┘
```

### Detalhamento das Classes Chave a Criar / Refatorar

1. **`com.example.security.CryptoManager`**:
   - Gera e armazena chave AES-256 no `AndroidKeyStore`.
   - Criptografia autenticada com AES/GCM/NoPadding.
   - Migração transparente dos tokens existentes para o formato cifrado.

2. **`com.example.util.SafeZipExtractor`**:
   - Limite máximo de tamanho descompactado: 250 MB.
   - Limite de arquivos: 1.500 entradas.
   - Limite de profundidade: 15 diretórios.
   - Detecção de Path Traversal (`entry.name.contains("..")` ou canonical path fora da raiz temporária).
   - Aviso explícito de arquivos ignorados com relatório completo (nunca descarte silencioso).

3. **`com.example.engine.CompatibilityEngine`**:
   - Mapeamento: AGP <-> Gradle <-> Versão Java compatível.
   - Inspeção de arquivos `build.gradle`, `build.gradle.kts`, `settings.gradle`, `gradle-wrapper.properties`.
   - Detecção de requisitos bloqueantes (`google-services.json`, chaves de assinatura privadas).
   - Geração de diff e registro histórico de alterações aplicadas.

4. **`com.example.data.workflow.WorkflowTemplate`**:
   - Eliminação de qualquer fallback silencioso para `assembleDebug`.
   - Remoção de injeção de JSON falso do Firebase.
   - Captura estrita da variante solicitada e validação de existência física do `.apk` antes de declarar sucesso no job.

5. **`com.example.data.remote.GitHubRepository`**:
   - Associação de cada compilação com um UUID único (`client_request_id`) incluído no commit e mensagem do build para idempotência.
   - Busca de execuções existentes antes de qualquer ação.
   - Eliminação de duplicatas de dispatch.

---

## 5. Sequência de Implementação

1. **Segurança de Dados**: Implementar `CryptoManager` e integrar ao `PreferencesManager`.
2. **Sanitização de Arquivos**: Implementar `SafeZipExtractor` com validação de zip bombs e traversal.
3. **Motor de Compatibilidade**: Construir `CompatibilityEngine` com detecção de versões e matriz AGP/Gradle/Java.
4. **Purificação do Workflow CI**: Atualizar `WorkflowTemplate.kt` removendo fallbacks ilegítimos e arquivos fictícios.
5. **Idempotência & Integração GitHub**: Atualizar `GitHubRepository.kt` com controle de execuções únicas e reconciliação.
6. **Interface & Feedback do Usuário**: Atualizar `BuildScreen.kt` e `SettingsScreen.kt` para expor modos de compatibilidade e alertas de arquivos ausentes.
7. **Verificação & Testes**: Executar testes unitários e `compile_applet`.
