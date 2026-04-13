# Git Out

[![Build & Test](https://github.com/po4yka/gitout/actions/workflows/build.yaml/badge.svg)](https://github.com/po4yka/gitout/actions/workflows/build.yaml)
[![Publish](https://github.com/po4yka/gitout/actions/workflows/publish.yaml/badge.svg)](https://github.com/po4yka/gitout/actions/workflows/publish.yaml)
[![CodeQL](https://github.com/po4yka/gitout/actions/workflows/codeql.yaml/badge.svg)](https://github.com/po4yka/gitout/actions/workflows/codeql.yaml)
[![Docker Image Version](https://img.shields.io/docker/v/po4yka/gitout?sort=semver)][hub]
[![Docker Image Size](https://img.shields.io/docker/image-size/po4yka/gitout)][hub]

 [hub]: https://hub.docker.com/r/po4yka/gitout/

A command-line tool to automatically backup Git repositories from GitHub or anywhere.

Fork of [JakeWharton/gitout](https://github.com/JakeWharton/gitout) with parallel sync, metrics, Telegram notifications, and semantic search.

The tool clones git repos from GitHub or any other hosting service.
If the repository was already cloned, it fetches updates to keep your local copy in sync.

When you add your GitHub username and a token, `gitout` discovers all of your owned repositories and synchronizes them automatically.
You can opt-in to having starred or watched repositories synchronized as well.

Cloned repositories are [bare](https://www.saintsjd.com/2011/01/what-is-a-bare-git-repository/) (no working copy).
To access files: `git clone /path/to/bare/repo`.


## Installation

### Docker

Multi-arch images (amd64, arm64) are published to Docker Hub and GHCR on every master commit and version tag.

```bash
$ docker run -d \
    -v /path/to/data:/data \
    -v /path/to/config.toml:/config/config.toml \
    -e "GITOUT_CRON=0 * * * *" \
    -e "PUID=1000" \
    -e "PGID=1000" \
    po4yka/gitout
```

Docker Compose:
```yaml
services:
  gitout:
    image: po4yka/gitout:latest
    restart: unless-stopped
    volumes:
      - /path/to/data:/data
      - /path/to/config.toml:/config/config.toml
    environment:
      - "GITOUT_CRON=0 * * * *"
      - "PUID=1000"
      - "PGID=1000"
```

Mount `/data` for repository storage and `/config/config.toml` for configuration.
Set `GITOUT_CRON` for scheduled syncs (see [cron.help](https://cron.help)).
Set `PUID`/`PGID` to control file ownership.

#### Local debugging

The `dev/` directory contains a turnkey setup:

1. Place tokens in `dev/secrets/github_token.txt` and `dev/secrets/telegram_bot_token.txt`
2. Customize `dev/config.local.toml`
3. Run: `docker compose -f dev/docker-compose.local.yml up --build`

### Binaries

Download a `.zip` from the [latest release](https://github.com/po4yka/gitout/releases/latest).
Requires JRE 17+. After unzipping, run `bin/gitout` (macOS/Linux) or `bin\gitout.bat` (Windows).


## Usage

```
$ gitout --help
Usage: gitout [<options>] <config> <destination>

Options:
  --version               Show the version and exit
  --workers=<number>      Number of parallel workers (default: from config or 4)
  --metrics / --no-metrics Enable/disable metrics collection
  --metrics-format=<fmt>  Metrics format: console, json, prometheus
  --metrics-path=<path>   Export metrics to file
  -v, --verbose           Increase verbosity (-v info, -vv debug, -vvv trace)
  -q, --quiet             Decrease verbosity
  --dry-run               Print actions without performing them
  --cron=<expression>     Run on schedule
  --hc-id=<id>            Healthchecks.io service ID
  --hc-host=<url>         Healthchecks.io host (requires --hc-id)
  -h, --help              Show this message and exit

Environment Variables:
  GITOUT_WORKERS         Parallel workers (overrides config)
  GITOUT_TIMEOUT         Git operation timeout (default: 10m)
  GITOUT_CRON            Cron schedule
  GITHUB_TOKEN           GitHub personal access token
  GITHUB_TOKEN_FILE      Path to file containing GitHub token
  SSL_CERT_FILE          Path to SSL certificate bundle
```


## Configuration

```toml
version = 0

[github]
user = "example"
token = "abcd1234efgh5678ij90"  # Optional - can use env vars instead

[github.clone]
starred = true  # default false
watched = true  # default false
gists = true    # default true
repos = ["JakeWharton/gitout"]       # extra repos to sync
ignore = ["JakeWharton/SomeRepo"]    # repos to skip

[git.repos]
asm = "https://gitlab.ow2.org/asm/asm.git"

[ssl]
cert_file = "/etc/ssl/certs/ca-certificates.crt"
verify_certificates = true  # set false only for testing

[parallelism]
workers = 4
progress_interval_ms = 1000
repository_timeout_seconds = 600

[[parallelism.priorities]]
pattern = "org/critical-repo"
priority = 100   # 0-100, higher = synced first
timeout = 600

[metrics]
enabled = true
format = "console"  # console, json, prometheus
export_path = "/var/log/gitout/metrics.json"

[telegram]
token = "BOT_TOKEN"
chat_id = "123456789"
enabled = true
notify_start = true
notify_progress = true
notify_completion = true
notify_errors = true
notify_new_repos = true
notify_updates = false
enable_commands = false
allowed_users = []

[search]
enabled = false
qdrant_url = "http://localhost:6333"
collection_name = "repositories"
top_k = 10
auto_index = true
```

### GitHub Token

Token sources (priority order):
1. Config file: `[github] token = "..."`
2. Token file: `GITHUB_TOKEN_FILE=/path/to/token`
3. Environment: `GITHUB_TOKEN=...`

To create a token: visit https://github.com/settings/tokens, select `repo`, `gist`, and `read:user` scopes.

### Telegram Notifications

Provides real-time notifications about sync operations.

**Setup:**
1. Create a bot via [@BotFather](https://t.me/botfather) and save the token
2. Get your chat ID from [@userinfobot](https://t.me/userinfobot)
3. Add `[telegram]` section to config

**Bot commands** (when `enable_commands = true`):
`/status`, `/stats`, `/fails`, `/info`, `/ping`, `/help`, `/start`

Only users in `allowed_users` can interact with the bot. Token can also be set via `TELEGRAM_BOT_TOKEN_FILE` or `TELEGRAM_BOT_TOKEN` env vars.

### Semantic Search

Search repositories using natural language via [Gemini Embedding 2](https://ai.google.dev/gemini-api/docs/embeddings) and [Qdrant](https://qdrant.tech/).

**Prerequisites:** Qdrant running locally, `GEMINI_API_KEY` env var set.

```bash
# Search
gitout search "OAuth library in Kotlin" config.toml /backup/path

# Re-index
gitout index config.toml /backup/path
```

When `enable_commands = true` and `search.enabled = true`, Telegram commands `/find <query>` and `/reindex` are available.

### SSL/TLS

Auto-detects certificates from common locations (`/etc/ssl/certs/ca-certificates.crt`, `/etc/ssl/cert.pem`, etc.). Override via `[ssl] cert_file` or `SSL_CERT_FILE` env var.

### Parallel Sync

Concurrent repository synchronization using coroutines with configurable worker pool.

**Priority:** `--workers` CLI > `GITOUT_WORKERS` env > config > default (4)

Performance improvement with 100 repos: sequential ~30min, 4 workers ~8min, 8 workers ~4min.

Use 4-8 workers for GitHub (rate limits), 10-16 for self-hosted servers.

### Retry and Reliability

Automatic retry with linear backoff for failed git operations (up to 6 attempts: 5s, 10s, 15s...). Adaptive error categorization with HTTP/1.1 fallback on protocol errors. Configurable timeout via `GITOUT_TIMEOUT` (default: 10m).


## Development

```bash
./gradlew build          # Build with tests
./gradlew test           # Run tests only
./gradlew installDist    # Create distribution
```

Binary output: `build/install/gitout/bin/gitout`


## License

MIT. See `LICENSE.txt`.

    Copyright 2020 Jake Wharton
