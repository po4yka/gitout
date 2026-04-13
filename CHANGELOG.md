# Change Log

## [Unreleased]
[Unreleased]: https://github.com/po4yka/gitout/compare/0.3.0...HEAD

New:
- Linux Arm variant of Docker container.

## [0.4.0-fork-SNAPSHOT] - In Development

### New Features
- Parallel repository synchronization with configurable worker pool (4-8x speedup)
- Production-grade metrics system (console, JSON, Prometheus formats)
- Telegram bot integration with notifications and command interface
- Semantic repository search via Gemini Embedding 2 + Qdrant
- Intelligent retry mechanism with adaptive backoff and error categorization
- Performance optimizations: shallow clones, HTTP/2, connection pooling, compression
- Comprehensive test suite (41+ tests)

### Enhanced
- SSL/TLS auto-detection from common system certificate locations
- Flexible authentication: config file, token file, or environment variable
- Docker support with PUID/PGID user mapping
- Structured logging with configurable verbosity levels

### Configuration Additions
- `[parallelism]` section for worker pool and priority scheduling
- `[metrics]` section for observability
- `[telegram]` section for notifications
- `[search]` section for semantic search
- `[http]`, `[large_repos]`, `[failure_tracking]`, `[maintenance]`, `[lfs]` sections

### Environment Variables
- `GITOUT_WORKERS` - parallel worker count
- `GITOUT_TIMEOUT` - git operation timeout
- `GITOUT_METRICS_FORMAT` / `GITOUT_METRICS_PATH` - metrics configuration
- `GITHUB_TOKEN_FILE` - path to token file
- `TELEGRAM_BOT_TOKEN` / `TELEGRAM_BOT_TOKEN_FILE` - Telegram bot token


## [0.3.0] - 2025-09-08
[0.3.0]: https://github.com/JakeWharton/gitout/releases/tag/0.3.0

New:
- Rewrite the app in Kotlin (from Rust). Sorry (not sorry).
- The binary distribution (which now requires a JVM) supports scheduled sync with the `--cron` option.
- Add `--timeout` option / `GITOUT_TIMEOUT` env var to control limit on git operations. Default is 10 minutes.

Changed:
- The `CRON`, `HEALTHCHECK_ID`, and `HEALTHCHECK_HOST` Docker container environment variables are now named `GITOUT_CRON`, `GITOUT_HC_ID`, and `GITOUT_HC_HOST`, respectively. These will also be honored by the standalone binary.


## [0.2.0] - 2020-05-23
[0.2.0]: https://github.com/JakeWharton/gitout/releases/tag/0.2.0

 * New: Output will now only print repositories which have updates. A counter is included to display overall progress.
 * Fix: Do not ping healthcheck if command fails in Docker container.


## [0.1.0]
[0.1.0]: https://github.com/JakeWharton/gitout/releases/tag/0.1.0

Initial release.
