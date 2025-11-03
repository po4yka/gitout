# Git Out

A command-line tool to automatically backup Git repositories from GitHub or anywhere.

The `gitout` tool will clone git repos from GitHub or any other git hosting service.
If the repository was already cloned, it will fetch any updates to keep your local copy in sync.

When you add your GitHub username and a token, `gitout` will discover all of your owned repositories and synchronize them automatically.
You can opt-in to having repositories that you've starred or watched synchronized as well.

The cloned repositories are [bare](https://www.saintsjd.com/2011/01/what-is-a-bare-git-repository/).
In other words, there is no working copy of the files for you to interact with.
If you need access to the files, you can `git clone /path/to/bare/repo`.


## Installation

### Docker

The binary is available inside the `po4yka/gitout` Docker container (Kotlin version with SSL improvements).

[![Docker Image Version](https://img.shields.io/docker/v/po4yka/gitout?sort=semver)][hub]
[![Docker Image Size](https://img.shields.io/docker/image-size/po4yka/gitout)][hub]

 [hub]: https://hub.docker.com/r/po4yka/gitout/

Mount a `/data` volume which is where the repositories will be stored.
Mount the `/config` folder which contains a `config.toml` or mount a `/config/config.toml` file directly.

By default, the tool will run a single sync and then exit.
If you specify the `GITOUT_CRON` environment variable with a valid cron specifier, the tool will not exit and perform automatic syncs in accordance with the schedule.

```bash
$ docker run -d \
    -v /path/to/data:/data \
    -v /path/to/config.toml:/config/config.toml \
    -e "GITOUT_CRON=0 * * * *" \
    -e "PUID=1000" \
    -e "PGID=1000" \
    po4yka/gitout
```

For help creating a valid cron specifier, visit [cron.help](https://cron.help/#0_*_*_*_*).

To be notified when sync is failing visit https://healthchecks.io, create a check, and specify the ID to the container using the `GITOUT_HC_ID` environment variable (for example, `-e "GITOUT_HC_ID=..."`).

To write data as a particular user, the `PUID` and `PGID` environment variables can be set to your user ID and group ID, respectively.

If you're using Docker Compose, an example setup looks like:
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
      # Optional:
      - "GITOUT_HC_ID=..."
      - "GITOUT_TIMEOUT=10m"
```

Note: You may want to specify an explicit version rather than `latest`.
See https://hub.docker.com/r/po4yka/gitout/tags or `CHANGELOG.md` for the available versions.

### Binaries

A `.zip` can be downloaded from the [latest GitHub release](https://github.com/JakeWharton/gitout/releases/latest).

The Java Virtual Machine must be installed on your system to run.
After unzipping, run either `bin/gitout` (macOS, Linux) or `bin/gitout.bat` (Windows).


## Usage

```
$ gitout --help
Usage: gitout [<options>] <config> <destination>

Options:
  --version            Show the version and exit
  --workers=<number>   Number of parallel workers for repository synchronization (default: from config or 4)
  -v, --verbose        Increase logging verbosity. -v = informational, -vv = debug, -vvv = trace
  -q, --quiet          Decrease logging verbosity. Takes precedence over verbosity
  --dry-run            Print actions instead of performing them
  --cron=<expression>  Run command forever and perform sync on this schedule
  --hc-id=<id>         ID of Healthchecks.io service to notify
  --hc-host=<url>      Host of Healthchecks.io service to notify. Requires --hc-id
  -h, --help           Show this message and exit

Arguments:
  <config>       Configuration TOML
  <destination>  Backup directory
```


## Configuration

Until version 1.0 of the tool, the TOML version is set to 0 and may change incompatibly between 0.x releases.
You can find migration information in the `CHANGELOG.md` file.

```toml
version = 0

[github]
user = "example"
token = "abcd1234efgh5678ij90"  # Optional - can use environment variables instead

[github.clone]
starred = true  # Optional, default false
watched = true  # Optional, default false
gists = true    # Optional, default true
# Extra repos to synchronize that are not owned, starred, or watched by you.
repos = [
  "JakeWharton/gitout",
]
# Repos temporary or otherwise that you do not want to be synchronized.
ignore = [
  "JakeWharton/TestParameterInjector",
]

# Repos not on GitHub to synchronize.
[git.repos]
asm = "https://gitlab.ow2.org/asm/asm.git"

# SSL configuration (optional)
[ssl]
cert_file = "/etc/ssl/certs/ca-certificates.crt"  # Path to your certificate bundle
verify_certificates = true  # Optional, default true. Set to false only for testing!

# Parallel synchronization configuration (optional)
[parallelism]
workers = 4  # Number of repositories to sync in parallel (default: 4)
```

### GitHub Token Configuration

The GitHub token can be provided in multiple ways with the following priority order:

1. **config.toml file** - Specify the token directly in the `[github]` section:
   ```toml
   [github]
   user = "example"
   token = "abcd1234efgh5678ij90"
   ```

2. **GITHUB_TOKEN_FILE environment variable** - Path to a file containing the token:
   ```bash
   export GITHUB_TOKEN_FILE="/path/to/token-file"
   ```
   This is useful for keeping secrets in separate files managed by secret management systems.

3. **GITHUB_TOKEN environment variable** - Token value directly:
   ```bash
   export GITHUB_TOKEN="abcd1234efgh5678ij90"
   ```

The tool will use the first available token found in this order. If you have a GitHub configuration but no token is found in any location, the tool will fail with a helpful error message.

When using Docker, you can pass environment variables like this:
```bash
docker run -d \
  -v /path/to/data:/data \
  -v /path/to/config.toml:/config/config.toml \
  -e "GITHUB_TOKEN_FILE=/secrets/github-token" \
  -e "GITOUT_CRON=0 * * * *" \
  po4yka/gitout
```

### SSL/TLS Configuration

The tool automatically searches for SSL certificates in common locations:
- `/etc/ssl/certs/ca-certificates.crt`
- `/etc/ssl/cert.pem`
- `/usr/lib/ssl/cert.pem`
- `/etc/pki/tls/certs/ca-bundle.crt`

If your certificates are in a different location, specify the path in the `[ssl]` section of your config.

The `SSL_CERT_FILE` environment variable can also be used to specify the certificate path.

### Retry and Reliability

The tool includes automatic retry logic for network operations:
- Up to 6 retry attempts for failed git operations
- Exponential backoff between retries (5s, 10s, 15s, etc.)
- Configurable timeout via `GITOUT_TIMEOUT` environment variable (default: 10m)

### Parallel Synchronization

The tool supports parallel synchronization of repositories for improved performance:

**Configuration Options:**
- `--workers` CLI option: Override the number of parallel workers
- `GITOUT_WORKERS` environment variable: Set workers without changing config
- `[parallelism]` section in config.toml: Set default worker pool size
- Default: 4 workers

**Priority:** CLI option > Environment variable > Config file > Default (4)

**Usage Examples:**

```bash
# Use 8 parallel workers
gitout --workers=8 config.toml /backup/path

# Use environment variable
export GITOUT_WORKERS=6
gitout config.toml /backup/path

# Sequential mode (disable parallelism)
gitout --workers=1 config.toml /backup/path
```

**Performance Benefits:**

Parallel synchronization significantly reduces total sync time:
- **Sequential sync:** ~30 minutes for 100 repos (assuming 18 seconds per repo)
- **Parallel sync (4 workers):** ~8 minutes for 100 repos (4x speedup)
- **Parallel sync (8 workers):** ~4 minutes for 100 repos (8x speedup)

Example performance improvement:
```
Sequential: 100 repos × 18s = 1800s (~30 min)
Parallel (4 workers): 100 repos ÷ 4 × 18s = 450s (~8 min)
Parallel (8 workers): 100 repos ÷ 8 × 18s = 225s (~4 min)
```

**Best Practices:**
- Use 4-8 workers for GitHub to stay within rate limits
- Higher values (10-16) work well for self-hosted git servers
- Use workers=1 for debugging or when network is unreliable
- Monitor logs to ensure parallel operations complete successfully

**Error Handling:**
- One repository failure doesn't stop others from syncing
- All failures are collected and reported at the end
- Failed repositories are listed with error messages
- Exit code indicates if any repositories failed

### Creating a GitHub token

  1. Visit https://github.com/settings/tokens
  2. Click "Generate new token"
  3. Type "gitout" in the name field
  4. Select the "repo", "gist", and "read:user" scopes
     - `repo`: Needed to discover and clone private repositories (if you only have public repositories then just `public_repo` will also work)
     - `gist`: Needed to discover and clone private gists (if you only have public gists then this is not required)
     - `read:user`: Needed to traverse your owned, starred, and watched repo lists
  5. Select "Generate token"
  6. Copy the value into your `config.toml` as it will not be shown again


## Fork Improvements

This fork (po4yka/gitout) is based on the Kotlin rewrite by JakeWharton and includes the following enhancements:

### Parallel Synchronization (NEW)
- Concurrent repository synchronization using Kotlin coroutines
- Configurable worker pool (default: 4 workers)
- Massive performance improvement: 4-8x faster for large repository sets
- Intelligent error handling: one failure doesn't stop others
- Configurable via `--workers` CLI option, `GITOUT_WORKERS` env var, or config.toml
- Respects rate limits with bounded concurrency

### SSL/TLS Improvements
- Automatic detection of SSL certificates in common locations
- Support for custom certificate paths via config or environment variables
- Enhanced SSL error handling and diagnostics
- Support for disabling certificate verification (for testing environments)

### Network Reliability
- Automatic retry mechanism with exponential backoff (up to 6 attempts)
- Configurable operation timeout via `GITOUT_TIMEOUT` environment variable
- Better error messages for network failures
- Improved handling of slow or flaky network connections

### Docker Improvements
- User/Group ID mapping via `PUID` and `PGID` environment variables
- Proper file permission handling in containers
- Enhanced SSL certificate support in Docker images
- Simplified configuration with environment variables

## Development

Build the project:
```bash
./gradlew build
```

Run tests:
```bash
./gradlew test
```

Create distribution:
```bash
./gradlew installDist
```

The binary will be in `build/install/gitout/bin/gitout`.


# LICENSE

MIT. See `LICENSE.txt`.

    Copyright 2020 Jake Wharton
