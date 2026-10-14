# Studio APK - Robust Android Cloud Builder Architecture

A comprehensive plan to eliminate upload freezes, fix GitHub API authentication and S3 artifact download failures, optimize CI compilation workflows, and elevate Studio APK into a professional, resilient Android build tool.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions have been confirmed through user feedback and form the foundation of this plan:
> - **Upload Architecture**: Parallel batched blob upload with concurrency rate limiter (4 concurrent workers) and automatic default branch CI workflow synchronization.
> - **Build Variants & Signing**: Dual support for Release and Debug variants with automated keystore generation and self-signed APK outputs.
> - **Monitoring & Logs**: Live visual multi-phase step progress tracking paired with an interactive, filterable log viewer.

---

## 1. Overview & Core Concept

### What It Does
Studio APK transforms any mobile device into a cloud-based Android compilation station. Users import or generate an Android project (ZIP archive), configure build parameters, push the codebase to a dedicated or personal GitHub repository, compile real signed APKs using GitHub Actions cloud runners, stream compilation logs in real time, and download and install the resulting APK directly onto their device.

### Target Audience & Persona
Mobile and Android developers, students, and hobbyists who need to compile Android Gradle projects into APKs on the go without requiring a high-powered desktop workstation or local Android Studio installation.

### Key Value Delivered
- **Zero-Freeze Code Transmission**: Batched asynchronous upload prevents API timeouts and secondary rate limits.
- **Reliable CI Workflow Lifecycle**: Automated repository initialization and default branch workflow syncing ensure GitHub Actions always triggers immediately.
- **Flawless Artifact Retrieval**: Fixes the S3 redirect authorization bug to ensure APK downloads complete 100% of the time.
- **Transparent Build Telemetry**: Real-time progress, live job steps, and searchable build logs provide complete visibility into compilation.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Setup & Verification**:
   - User inputs GitHub Personal Access Token (PAT).
   - Instant token validation with user avatar, name, and repository picker.
   - One-tap button to automatically configure the dedicated compilation repository (`android-compiler-runner`) with the required CI workflow on the default branch.

2. **Project Selection & Pre-Flight Validation**:
   - User picks an Android project ZIP or generates a sample Jetpack Compose project.
   - Comprehensive pre-flight check validates Gradle configuration, module structure, and estimates transfer size.
   - Clear project summary card displaying module count, detected build variants, and total files to upload.

3. **Execution & Live Progress Tracking**:
   - Single tap on "Enviar e Compilar APK".
   - Dynamic multi-step progress bar showing active phase:
     1. Project sanitization & filtering (stripping build cache/temp files).
     2. Batched blob transmission (e.g., "Enviando arquivos: 42/180").
     3. Git Tree & Commit creation.
     4. Branch publication & CI runner dispatch.
     5. GitHub Actions live job polling.
   - Auto-navigation to the compilation monitor with real-time runner status.

4. **Log Inspection & Troubleshooting**:
   - Interactive modal displaying current step execution (`setup-java`, `setup-gradle`, `compileReleaseApk`).
   - Terminal-style dark console viewer with text search, auto-scroll, and error highlighting.

5. **Artifact Download & Local Installation**:
   - Once compilation finishes, the card shows a prominent "Instalar APK" or "Baixar APK" button.
   - Clean download directly to app storage with progress indication.
   - Automated invocation of Android `FileProvider` package installer with permission guidance for unknown sources.

### Visual Identity & Theme
- **Theme Direction**: Premium Developer Dark aesthetic (obsidian background `#0B0F19`, slate cards `#161E2E`, vibrant electric blue accent `#3B82F6`, neon emerald success `#10B981`, and amber warning `#F59E0B`).
- **Typography**: Clean, monospace typography for logs and code hashes, modern high-legibility sans-serif for UI labels and metrics.
- **Feedback & Motion**: Subtle pulsing glow during active builds, smooth progress bars, and staggered entry animations for build history items.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Concurrency-Limited Parallel Blob Uploading
- **Chosen Approach**: Use Kotlin Coroutines with a `Semaphore(permits = 4)` to upload Git blobs in parallel batches with adaptive retry.
- **Why**: Sequential uploads take 3-6 minutes for medium projects and trigger GitHub secondary rate limits. Concurrency of 4 reduces upload time to under 20-30 seconds while remaining comfortably within GitHub API rate limits.
- **Alternative Considered**: Uploading via a single large archive to GitHub Releases or commit endpoint. Bypassing Git trees prevents GitHub Actions from running standard checkouts.

### Decision 2: OkHttp Host-Aware Authorization Interceptor
- **Chosen Approach**: Modify `GitHubApiClient` interceptor so the `Authorization: Bearer <token>` header is **only** attached when `request.url.host` equals `api.github.com` or `github.com`.
- **Why**: GitHub artifact download endpoints return an HTTP 302 redirect to AWS S3 pre-signed URLs. When OkHttp forwards the Authorization header to AWS S3, S3 rejects the request with HTTP 400 because pre-signed URLs cannot be combined with Bearer auth headers.
- **Alternative Considered**: Disabling redirects in OkHttp and manually handling the redirect URL in a separate client. Host checking in the interceptor is cleaner, safer, and handles all redirects automatically.

### Decision 3: Default-Branch Workflow Synchronization
- **Chosen Approach**: Always verify and install `.github/workflows/android-compile.yml` on the repository's default branch (`main`) before creating build branches.
- **Why**: GitHub Actions push-triggers on `build/**` branches are only evaluated if the workflow file is present in the repository's default branch. If committed only to a feature branch, GitHub Actions will refuse to run.
- **Alternative Considered**: Running strictly on `main` branch. Using isolated `build/<project>-<timestamp>` branches prevents dirty states and allows concurrent builds without commit collisions.

---

## 4. Technical Architecture & Data Strategy

### System Component Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Studio APK App (M3)                  │
├─────────────────┬───────────────────┬──────────────────┤
│  Build Screen   │  History Screen   │ Settings Screen  │
└────────┬────────┴─────────┬─────────┴────────┬─────────┘
         │                  │                  │
         ▼                  ▼                  ▼
┌────────────────────────────────────────────────────────┐
│                   BuilderViewModel                     │
├────────────────────────────────────────────────────────┤
│  • Pre-flight Sanitizer     • Build State Machine      │
│  • Parallel Transfer Pool   • Realtime Run Poller      │
└────────┬──────────────────┬──────────────────┬─────────┘
         │                  │                  │
         ▼                  ▼                  ▼
┌──────────────────┐┌──────────────────┐┌────────────────┐
│   AppDatabase    ││ GitHubRepository ││   ApkManager   │
│   (Room / DAO)   ││  (Retrofit/API)  ││ (FileProvider) │
│ • Local Builds   ││ • Blobs/Trees    ││ • Safe Storage │
│ • Artifact State ││ • Runs & Logs    ││ • OS Installer │
└──────────────────┘└────────┬─────────┘└────────────────┘
                             │
                             ▼
              ┌─────────────────────────────┐
              │     GitHub REST API / CI    │
              │ • Git Data (Blobs/Trees)    │
              │ • GitHub Actions Runners    │
              │ • AWS S3 Artifact Storage   │
              └─────────────────────────────┘
```

### Critical Problem Diagnoses & Proposed Fixes

| Problem Identified | Root Cause | Architectural Solution |
| :--- | :--- | :--- |
| **Upload Stuck / Freezing** | Sequential single-thread blob loop + memory bloat + secondary rate limits | Parallel asynchronous coroutines (`Semaphore(4)`), memory-efficient streaming, smart exclusion of `.gradle/`, `.git/`, and binary build outputs. |
| **Workflow Never Triggers** | Pushing orphan branch without workflow on `main` branch | Enforce default branch workflow installation during repo init/pre-flight; ensure branch commit points to valid base tree. |
| **Poller Times Out / Gets Stuck** | Short 16-second window for runner start; null `runId` never re-checked | Extended discovery window (up to 60s), persistent orphan recovery in polling worker that matches head commit SHA. |
| **APK Download Fails (HTTP 400)** | OkHttp forwards Bearer token on redirect to AWS S3 storage | Host-aware Auth interceptor that strips authorization headers on redirect to third-party CDNs/S3. |
| **APK Parsing / Corrupt Archive** | Artifact is a ZIP containing the APK, extracted naively | Robust multi-entry streaming `ZipInputStream` parser that locates `.apk` in nested artifact folders and validates checksums. |

### Data Model & Persistence
- **Room Database (`AppDatabase`)**:
  - `ProjectBuildEntity`: Tracks `id`, `projectName`, `repoFullName`, `branchName`, `commitSha`, `runId`, `buildVariant`, `gradleTask`, `status` (`pending`, `uploading`, `queued`, `in_progress`, `completed`, `failed`), `conclusion`, `artifactDownloadUrl`, `localApkPath`, `errorMessage`, `startedAt`, `completedAt`.
  - Continuous reactive observation via `Flow<List<ProjectBuildEntity>>`.

---

## 5. Implementation Sequence

1. **Network Layer Fortification**:
   - Update `GitHubApiClient.kt` with host-aware auth interceptor and optimized timeouts.
   - Enhance `GitHubApiService.kt` and `GitHubModels.kt` to support commit parent references and tree caching.
2. **Parallel Code Transfer Engine**:
   - Refactor `GitHubRepository.kt` to implement batched blob uploads with `Semaphore(4)` and progressive chunked callbacks.
   - Refactor `ZipProjectParser.kt` to strip superfluous build directories, caches, and large binary artifacts.
3. **Workflow & Branch Dispatch Optimization**:
   - Update `WorkflowTemplate.kt` with modern Gradle daemon optimizations, SDK license automation, and dual-variant artifact capture.
   - Implement resilient runner matching and commit SHA linking.
4. **Resilient APK Downloader & Installer**:
   - Implement streaming artifact extraction with retry and direct FileProvider package installation.
5. **UI & Telemetry Overhaul**:
   - Revamp `BuildScreen.kt` with real-time transfer progress and live status.
   - Polish `HistoryScreen.kt` and `LogsViewerDialog.kt` with log filtering, live status chips, and direct installer triggers.
