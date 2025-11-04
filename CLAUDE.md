# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**gitout** is a Kotlin-based CLI tool for automatically backing up Git repositories from GitHub or any git hosting service. The project was completely rewritten from Rust to Kotlin, leveraging modern JVM features, Kotlin coroutines for parallelism, and Apollo GraphQL for GitHub API interactions.

**Key Features:**
- Parallel repository synchronization (4-8x faster than sequential)
- Automatic retry mechanism with configurable backoff strategies
- Production-grade metrics system (console, JSON, Prometheus formats)
- Enhanced SSL/TLS support with auto-detection
- Flexible authentication (config file, token file, environment variable)
- Telegram bot integration with notifications and command interface

## Build & Development Commands

### Building
```bash
./gradlew build                  # Full build with tests
./gradlew installDist            # Create distribution (output: build/install/gitout/)
./gradlew distZip                # Create ZIP distribution
./gradlew distTar                # Create TAR distribution
```

### Testing
```bash
./gradlew test                   # Run all tests
./gradlew test --tests ConfigTest           # Run specific test class
./gradlew test --tests "*.RetryPolicyTest"  # Run tests matching pattern
```

### Running
```bash
# After installDist
./build/install/gitout/bin/gitout config.toml /backup/path

# With Gradle
./gradlew run --args="config.toml /backup/path"

# With options
./gradlew run --args="--workers=8 --verbose config.toml /backup/path"
```

### Docker
```bash
# Build Docker image
docker build -t gitout .

# Run with config
docker run -v /path/to/data:/data -v /path/to/config.toml:/config/config.toml gitout
```

### Code Quality
```bash
./gradlew check                  # Run all checks (build + test)
```

## Architecture

### Core Components

**Main Entry (main.kt:31-36):**
- `GitOutCommand`: Clikt-based CLI command parser with suspend support
- Parses arguments, options, and environment variables
- Initializes core components (Logger, OkHttpClient, Engine, HealthCheckService)
- Handles scheduling with cron support via cardiologist library

**Engine (Engine.kt:26-462):**
The central orchestrator that manages the entire synchronization process.

Key responsibilities:
1. **Configuration Loading**: Parses TOML config and validates version compatibility
2. **GitHub Token Resolution** (Engine.kt:63-101): Priority-based token resolution:
   - Config file `[github] token` → `GITHUB_TOKEN_FILE` env → `GITHUB_TOKEN` env
3. **SSL Setup** (Engine.kt:314-364): Auto-detects certificates from standard locations:
   - `/etc/ssl/certs/ca-certificates.crt`, `/etc/ssl/cert.pem`, etc.
   - Sets `SSL_CERT_FILE` and `SSL_CERT_DIR` environment variables for git subprocesses
4. **Task Collection**: Gathers all repositories (GitHub + custom git repos) into `SyncTask` objects
5. **Parallel Execution** (Engine.kt:258-312): Uses Kotlin coroutines with semaphore-based worker pool
6. **Retry Logic**: Delegates to `RetryPolicy` for transient failure handling

**Key Flow:**
```
performSync() → setupSsl() → resolveGitHubToken() → loadRepositories()
            → collect SyncTasks → executeSyncTasksInParallel() → syncBare()
            → retryPolicy.execute() → executeSyncOperation()
```

**RetryPolicy (RetryPolicy.kt:32-149):**
Reusable retry mechanism with three backoff strategies:
- `LINEAR`: delay = baseDelayMs × attempt (5s, 10s, 15s, ...)
- `EXPONENTIAL`: delay = baseDelayMs × 2^(attempt-1) (5s, 10s, 20s, 40s, ...)
- `CONSTANT`: delay = baseDelayMs (5s, 5s, 5s, ...)

Default configuration: 6 attempts, 5-second base delay, LINEAR backoff
Used in: `Engine.syncBare()` for git clone/update operations

**GitHub API (GitHub.kt:9-111):**
- Uses Apollo GraphQL client with `UserReposQuery` (defined in `.graphql` files)
- Implements cursor-based pagination for all repository types
- Returns `UserRepositories` containing owned, starred, watching, and gists
- Error handling for missing users and null nodes in API responses

**Config (Config.kt:9-91):**
TOML configuration parser using ktoml library. Structure:
- `GitHub`: user, token (optional), archive settings, clone settings
- `Git`: custom repository map (name → URL)
- `Ssl`: cert_file (optional), verify_certificates (default: true)
- `Parallelism`: workers (default: 4), progress settings, priority patterns
- `MetricsConfig`: enabled, format (console/json/prometheus), export_path

**Logger (Logger.kt):**
Simple verbosity-based logger with levels: lifecycle, info, warn, debug, trace
Controlled by `-v` flags: `-v` = info, `-vv` = debug, `-vvv` = trace

### Parallel Synchronization

The parallel sync system (Engine.kt:258-312) uses:
1. **Semaphore**: Limits concurrent operations to configured worker count
2. **Coroutines**: Each sync task runs in an `async(Dispatchers.IO)` block
3. **awaitAll()**: Collects all results before proceeding
4. **Result Tracking**: `SyncResult` data class tracks success/failure with errors
5. **Error Reporting**: Groups failures and provides actionable error messages

Worker pool size priority: CLI `--workers` → `GITOUT_WORKERS` env → config `[parallelism] workers` → default (4)

### Git Operations

**Command Building (Engine.kt:384-414):**
- Cloning: `git clone --mirror <url> <name>` (bare repository)
- Updating: `git remote update --prune`
- SSL config: `-c http.sslVerify=false` if disabled
- Credentials: `-c credential.helper=store --file=<path>`

**Process Execution (Engine.kt:420-461):**
- Creates parent directories with `createDirectories()`
- Applies SSL environment variables to subprocess
- Enforces timeout (default: 10 minutes, configurable via `GITOUT_TIMEOUT`)
- Graceful process termination with `destroy()` then `destroyForcibly()`

### Dependencies & Libraries

**Core Libraries (libs.versions.toml):**
- **Kotlin**: 2.2.10 (JVM target: 1.8)
- **Kotlinx Coroutines**: 1.10.2 (parallel sync, async operations)
- **Apollo GraphQL**: 4.3.3 (GitHub API client)
- **OkHttp**: 5.1.0 (HTTP client with connection pooling)
- **Clikt**: 5.0.3 (CLI argument parsing)
- **ktoml**: 0.7.1 (TOML configuration parsing)
- **cardiologist**: 0.8.0 (cron scheduling)

**Build Plugins:**
- `kotlin.jvm`: Kotlin JVM compilation
- `kotlin.plugin.serialization`: kotlinx.serialization support
- `dev.drewhamilton.poko`: Data class generation with `@Poko`
- `com.github.gmazzo.buildconfig`: Generates BuildConfig.kt with version
- `com.apollographql.apollo`: GraphQL code generation

**Testing:**
- JUnit 4.13.2
- AssertK 0.28.1 (assertions)
- OkHttp MockWebServer (HTTP mocking)

## Configuration Files

**build.gradle:**
- Version: `0.4.0-fork-SNAPSHOT`
- Application main class: `com.jakewharton.gitout.Main`
- Explicit API mode enabled for all public declarations
- JVM target 1.8 with `-Xjdk-release=8` for compatibility

**GraphQL Schema:**
Located in `src/main/graphql/` (Apollo convention). Queries:
- `UserReposQuery`: Fetches owned, starred, watching repos and gists with pagination

## Testing Strategy

**Test Structure (src/test/kotlin/):**
- `ConfigTest`: TOML parsing and validation
- `EngineTest`: Core sync engine logic
- `RetryPolicyTest`: Retry strategies and backoff calculations
- `ParallelSyncTest`: Parallel synchronization with worker pools
- `IntegrationTest`: End-to-end scenarios with MockWebServer

**Test Execution Notes:**
- Tests use `MockWebServer` for GitHub API mocking
- Temporary directories for file system operations
- Coroutine test support with `kotlinx-coroutines-test`

## CI/CD Pipeline

**Workflows (.github/workflows/):**

1. **build.yaml**: Runs on PRs and pushes to master
   - Executes full test suite
   - Creates distribution packages
   - Tests Docker builds for amd64 and arm64
   - Uploads test results and artifacts

2. **publish.yaml**: Runs on master commits and version tags
   - Builds and publishes multi-arch Docker images (Docker Hub + GHCR)
   - Creates GitHub releases with ZIP/TAR distributions
   - Tags: `latest`, version tags (e.g., `v0.4.0`), commit SHA

3. **codeql.yaml**: Security scanning (weekly + on-demand)
   - Analyzes Java/Kotlin code
   - Security and quality queries

**Secrets Required:**
- `DOCKER_HUB_TOKEN`: For publishing to Docker Hub (po4yka user)
- `GITHUB_TOKEN`: Auto-provided for GHCR and releases

## Common Development Patterns

### Adding New Configuration Options

1. Add field to appropriate `Config` class (Config.kt) with `@Serializable`
2. Use `@kotlinx.serialization.SerialName` for snake_case TOML keys
3. Provide sensible defaults in constructor
4. Update README.md configuration section
5. Add test case in `ConfigTest`

### Adding Retry Support to New Operations

```kotlin
retryPolicy.execute(operationDescription = "operation name") { attempt ->
    // Your operation here
    performOperation()
}
```

### Working with Parallel Operations

Use the semaphore pattern from `executeSyncTasksInParallel()`:
```kotlin
val semaphore = Semaphore(workerCount)
coroutineScope {
    items.map { item ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                // Process item
            }
        }
    }.awaitAll()
}
```

## Important Implementation Notes

### SSL/TLS Handling
- SSL environment variables MUST be set on ProcessBuilder, not globally
- Certificate paths are auto-detected on startup
- `verify_certificates = false` should only be used for testing
- Environment variables: `SSL_CERT_FILE`, `SSL_CERT_DIR`, `GIT_SSL_NO_VERIFY`

### GitHub Token Security
- Tokens are written to temporary credentials file for git
- File is deleted immediately after sync completion (Engine.kt:242-248)
- Never log token values (only log source: config/file/env)

### Process Management
- Always use timeouts on git operations (default: 10m)
- Implement graceful shutdown: `destroy()` before `destroyForcibly()`
- Use `INHERIT` redirect for stderr to capture git error messages

### Coroutine Best Practices
- Use `Dispatchers.IO` for blocking git operations
- Limit concurrency with `Semaphore`, not thread pools
- Always use `coroutineScope` to ensure proper error propagation
- Use `async + awaitAll()` for parallel tasks with result collection

## Git Commit Guidelines

**Important Rules:**
- **Never mention Claude, AI, or assistant tools in commit messages**
- Keep commit messages concise and descriptive
- Use imperative mood ("Add feature" not "Added feature")
- Focus on what changed and why, not how it was implemented
- Example good commit: "Add Telegram notifications for new repository discovery"
- Example bad commit: "Add feature with Claude Code assistance"

## Release Process

1. Update version in `build.gradle` (e.g., `0.4.0-fork`)
2. Update `CHANGELOG.md` with release notes
3. Commit: `git commit -m "Prepare release 0.4.0-fork"`
4. Tag: `git tag v0.4.0-fork`
5. Push: `git push origin master v0.4.0-fork`
6. GitHub Actions automatically:
   - Runs all tests
   - Builds distributions (ZIP/TAR)
   - Publishes multi-arch Docker images
   - Creates GitHub release with artifacts

## Docker Specifics

**Entrypoint (docker-entrypoint.sh):**
- Sets up user/group via `PUID`/`PGID` environment variables
- Supports cron scheduling via `GITOUT_CRON`
- Health check support via `GITOUT_HC_ID`
- All `GITOUT_*` environment variables are passed through

**Multi-arch Support:**
- Built for `linux/amd64` and `linux/arm64`
- Base image: Eclipse Temurin JRE (Alpine-based)
- Published to Docker Hub (`po4yka/gitout`) and GHCR (`ghcr.io/po4yka/gitout`)

## Telegram Bot Integration

### Overview

The project includes a Telegram bot integration that provides:
- **Real-time Notifications**: Start, progress, completion, and error notifications
- **Command Interface**: Interactive bot commands for status queries
- **User Authentication**: Whitelist-based access control

### TelegramNotificationService (TelegramNotificationService.kt)

**Architecture:**
- Uses `kotlin-telegram-bot` library (version 6.2.0 from JitPack)
- Implements both push notifications and command polling
- Thread-safe status tracking with `AtomicReference`

**Key Components:**
1. **Bot Initialization** (lines 43-122):
   - Token resolution with priority: config → `TELEGRAM_BOT_TOKEN_FILE` → `TELEGRAM_BOT_TOKEN`
   - Command handler registration with `dispatch { }`
   - Automatic polling startup if commands enabled

2. **User Authentication** (lines 129-158):
   - `handleCommand()` checks user ID against `allowed_users` list
   - Logs unauthorized access attempts
   - Sends rejection message to unauthorized users

3. **Available Commands**:
   - `/start`: Welcome message and command list
   - `/help`: Detailed help information
   - `/status`: Current/last sync status (shows if sync in progress)
   - `/info`: Bot version, configuration, notification settings

4. **Status Tracking** (lines 39-41, 212-227, 262-295):
   - `isSyncing`: AtomicReference<Boolean> tracks active sync
   - `lastSyncStatus`: AtomicReference<String> stores latest status
   - Updated by notification methods for `/status` command

**Configuration:**
```toml
[telegram]
token = "BOT_TOKEN"
chat_id = "123456789"
enabled = true
enable_commands = true
allowed_users = [123456789, 987654321]
notify_start = true
notify_progress = true
notify_completion = true
notify_errors = true
```

**Integration Points:**
- Initialized in `main.kt` (lines 108-116)
- Passed to `Engine` constructor
- Called at sync lifecycle points in `Engine.executeSyncTasksInParallel()`

### Security Considerations

**User Authentication:**
- Only users in `allowed_users` list can execute commands
- User IDs are 64-bit integers (Long type)
- All unauthorized attempts are logged with user ID

**Token Security:**
- Tokens never logged in plaintext
- Support for external token files (`TELEGRAM_BOT_TOKEN_FILE`)
- Bot stops if token resolution fails

**Best Practices:**
- Keep `allowed_users` list minimal
- Use `TELEGRAM_BOT_TOKEN_FILE` for production (avoid config.toml)
- Monitor logs for unauthorized access attempts
- Disable commands (`enable_commands = false`) if not needed

### Testing

**TelegramNotificationTest.kt:**
- Tests service initialization with various configs
- Verifies disabled service behavior
- Ensures no exceptions when service unavailable

**Manual Testing:**
1. Create test bot via [@BotFather](https://t.me/botfather)
2. Get user ID via [@userinfobot](https://t.me/userinfobot)
3. Configure `config.toml` with test credentials
4. Run gitout and send commands to bot
5. Verify authentication and responses

### Common Issues

**Bot not responding to commands:**
- Check `enable_commands = true` in config
- Verify user ID is in `allowed_users` list
- Check logs for "Starting Telegram bot polling"
- Ensure bot token is valid

**Unauthorized message:**
- User ID not in `allowed_users` list
- Use [@userinfobot](https://t.me/userinfobot) to get correct ID
- User IDs are numeric Long values, not usernames

**Polling not starting:**
- Requires both `enable_commands = true` AND non-empty `allowed_users`
- Bot only polls when command interface is enabled
- Check logs for initialization errors

