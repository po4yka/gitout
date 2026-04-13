# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

**gitout** is a Kotlin CLI tool for automatically backing up Git repositories from GitHub or any git hosting service. It uses Kotlin coroutines for parallel sync, Apollo GraphQL for GitHub API, and includes metrics, Telegram notifications, and semantic search.

## Build & Development

```bash
./gradlew build                  # Full build with tests
./gradlew test                   # Run all tests
./gradlew test --tests ConfigTest           # Specific test class
./gradlew installDist            # Distribution (build/install/gitout/)
./gradlew run --args="config.toml /backup/path"
./gradlew check                  # All checks
docker build -t gitout .         # Docker image
```

## Architecture

### Core Components

**Main Entry (main.kt):** Clikt CLI parser, initializes Logger, OkHttpClient, Engine, HealthCheckService. Cron support via cardiologist.

**Engine (Engine.kt):** Central orchestrator.
- Token resolution: config → `GITHUB_TOKEN_FILE` → `GITHUB_TOKEN`
- SSL auto-detection from standard paths
- Task collection: GitHub repos + custom git repos → `SyncTask` list
- Parallel execution: coroutines + semaphore worker pool
- Retry delegation to `RetryPolicy`

**Flow:** `performSync()` → `setupSsl()` → `resolveGitHubToken()` → `loadRepositories()` → `executeSyncTasksInParallel()` → `syncBare()` → `retryPolicy.execute()`

**RetryPolicy (RetryPolicy.kt):** Backoff strategies (LINEAR/EXPONENTIAL/CONSTANT), adaptive error categorization, HTTP/1.1 fallback. Default: 6 attempts, 5s base delay, LINEAR.

**GitHub (GitHub.kt):** Apollo GraphQL with cursor-based pagination. Returns owned, starred, watched repos and gists.

**Config (Config.kt):** TOML parser (ktoml). Sections: github, git, ssl, http, parallelism, metrics, telegram, search, large_repos, failure_tracking, maintenance, lfs.

**Logger (Logger.kt):** Verbosity levels: lifecycle, info, warn, debug, trace. Controlled by `-v` flags.

### Key Patterns

**Parallel sync:** Semaphore + `async(Dispatchers.IO)` + `awaitAll()`. Worker priority: CLI → env → config → default (4).

**Git operations:** Clone: `git clone --mirror`. Update: `git remote update --prune`. Timeout: 10min default, configurable via `GITOUT_TIMEOUT`.

### Dependencies (libs.versions.toml)

- Kotlin 2.3.10, Kotlinx Coroutines 1.10.2
- Apollo GraphQL 4.4.3, OkHttp 5.3.2
- Clikt 5.1.0, ktoml 0.7.1, cardiologist 0.8.0
- Poko (data classes), BuildConfig (version generation)
- Testing: JUnit 4.13.2, AssertK 0.28.1, MockWebServer

## Build Configuration

- Version: `0.4.0-fork-SNAPSHOT`
- Main class: `com.jakewharton.gitout.Main`
- Explicit API mode enabled
- JVM target 1.8 with `-Xjdk-release=8`
- GraphQL schema: `src/main/graphql/`

## Testing

Tests in `src/test/kotlin/`: ConfigTest, EngineTest, RetryPolicyTest, ParallelSyncTest, IntegrationTest, TelegramNotificationTest, SearchIndexServiceTest, ReadmeExtractorTest.

Uses MockWebServer for API mocking, temp directories for filesystem ops, `kotlinx-coroutines-test`.

## CI/CD

- **build.yaml**: PRs + master pushes. Tests, distributions, Docker build test, code quality.
- **publish.yaml**: Master + version tags. Multi-arch Docker (amd64/arm64) to Docker Hub + GHCR. GitHub releases.
- **codeql.yaml**: Weekly + on-demand security scanning.
- Secrets: `DOCKER_HUB_TOKEN` (Docker Hub), `GITHUB_TOKEN` (auto).

## Development Patterns

### Adding Config Options
1. Add field to `Config` class with `@Serializable` and `@SerialName` for snake_case
2. Provide defaults in constructor
3. Add test in `ConfigTest`

### Retry Pattern
```kotlin
retryPolicy.execute(operationDescription = "operation") { context ->
    performOperation()
}
```

### Parallel Pattern
```kotlin
val semaphore = Semaphore(workerCount)
coroutineScope {
    items.map { async(Dispatchers.IO) { semaphore.withPermit { process(it) } } }.awaitAll()
}
```

## Implementation Notes

- SSL env vars MUST be set on ProcessBuilder, not globally
- Tokens written to temp credentials file, deleted after sync
- Never log token values
- Timeouts on all git operations; graceful shutdown: `destroy()` then `destroyForcibly()`
- Use `Dispatchers.IO` for blocking ops, `Semaphore` for concurrency limits
- Use `coroutineScope` for error propagation, `async + awaitAll()` for parallel collection

## Git Commit Guidelines

- Never mention Claude, AI, or assistant tools in commit messages
- Imperative mood ("Add feature" not "Added feature")
- Focus on what and why, not how

## Docker

- Entrypoint handles PUID/PGID user mapping
- Multi-arch: linux/amd64, linux/arm64
- Published to Docker Hub (`po4yka/gitout`) and GHCR (`ghcr.io/po4yka/gitout`)

## Telegram Bot (TelegramNotificationService.kt)

- Token resolution: config → `TELEGRAM_BOT_TOKEN_FILE` → `TELEGRAM_BOT_TOKEN`
- Commands: `/status`, `/stats`, `/fails`, `/info`, `/ping`, `/help`, `/start`, `/find`, `/reindex`
- Whitelist auth via `allowed_users` (Long user IDs)
- Thread-safe status tracking with `AtomicReference`
- Initialized in `main.kt`, called at sync lifecycle points in Engine
