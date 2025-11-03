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

### Parallel Synchronization (Enhanced)

The tool supports enhanced parallel synchronization with advanced coroutine features:

**Basic Configuration:**
- `--workers` CLI option: Override the number of parallel workers
- `GITOUT_WORKERS` environment variable: Set workers without changing config
- `[parallelism]` section in config.toml: Set default worker pool size
- Default: 4 workers

**Priority:** CLI option > Environment variable > Config file > Default (4)

**Advanced Configuration (config.toml):**

```toml
[parallelism]
workers = 4                        # Number of parallel workers
progress_interval_ms = 1000        # Progress reporting interval in milliseconds
repository_timeout_seconds = 600   # Default timeout per repository (in seconds)

# Priority patterns: sync high-priority repos first, smaller repos first
[[parallelism.priorities]]
pattern = "JakeWharton/*"          # Pattern to match repository names
priority = 75                      # Priority level (0-100, higher = more important)
timeout = 300                      # Optional: custom timeout for matching repos

[[parallelism.priorities]]
pattern = "*/critical"             # Match any org with 'critical' repo
priority = 100                     # Highest priority
timeout = 600                      # 10 minute timeout

[[parallelism.priorities]]
pattern = "*/large-repo"           # Large repositories
priority = 50                      # Normal priority
timeout = 1800                     # 30 minute timeout
```

**Priority Levels:**
- `100`: PRIORITY_HIGHEST - Critical repositories, synced first
- `75`: PRIORITY_HIGH - Important repositories
- `50`: PRIORITY_NORMAL - Default priority
- `25`: PRIORITY_LOW - Less important repositories
- `0`: PRIORITY_LOWEST - Synced last

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

**Enhanced Features:**

1. **Progress Reporting:**
   - Real-time progress updates: "Progress: 45 of 100 repositories completed (45%)"
   - Tracks sync duration per repository
   - Reports average sync time and success rate

2. **Priority Queuing:**
   - High-priority repositories sync first
   - Smaller repositories prioritized within same priority level
   - Pattern-based priority configuration with wildcards

3. **Repository-Specific Timeouts:**
   - Configure different timeouts for different repositories
   - Pattern matching for flexible configuration
   - Prevents large repos from holding up the queue

4. **Advanced Error Handling:**
   - Categorized errors (Network, Auth, Timeout, Not Found, etc.)
   - Actionable suggestions for common errors
   - Detailed failure reports grouped by error category

5. **Metrics Collection:**
   - Success/failure rates
   - Average sync duration
   - Total sync time
   - Per-repository timing information

6. **Cancellation Support:**
   - Graceful handling of job cancellation
   - Proper cleanup on cancellation or timeout
   - Individual repository timeouts don't affect others

**Best Practices:**
- Use 4-8 workers for GitHub to stay within rate limits
- Higher values (10-16) work well for self-hosted git servers
- Use workers=1 for debugging or when network is unreliable
- Set higher priority for critical repositories
- Configure longer timeouts for large repositories
- Monitor logs to ensure parallel operations complete successfully

**Error Handling:**

The tool provides intelligent error categorization and suggestions:

**Error Categories:**
- `NETWORK`: Connection issues, DNS failures, SSL problems
- `AUTH`: Authentication or authorization failures
- `TIMEOUT`: Operation timeouts
- `NOT_FOUND`: Repository not found or access denied
- `GIT_COMMAND`: Git command execution errors
- `FILESYSTEM`: Disk space, permissions issues
- `UNKNOWN`: Uncategorized errors

**Example Error Output:**
```
Failed repositories by category:
  NETWORK: 2 repositories
    - user/repo1 (https://github.com/user/repo1.git): Connection refused
      Suggestion: Check your network connection and ensure the repository server is accessible.
    - user/repo2 (https://github.com/user/repo2.git): SSL certificate verification failed
      Suggestion: SSL/TLS error. Check certificate configuration in [ssl] section.

  AUTH: 1 repository
    - user/private-repo (https://github.com/user/private-repo.git): Authentication failed
      Suggestion: Verify your GitHub token is valid and has the required permissions.
```

**Logging Output:**

With enhanced parallel sync, you'll see detailed progress information:

```
Using 4 parallel workers for synchronization
Starting synchronization of 100 repositories with 4 workers
[1/100] Starting sync: https://github.com/user/critical-repo.git (priority: 100)
[2/100] Starting sync: https://github.com/user/important-repo.git (priority: 75)
Progress: 25 of 100 repositories completed (25%)
[25/100] Completed sync: https://github.com/user/repo25.git (2.3s)
Progress: 50 of 100 repositories completed (50%)
Progress: 75 of 100 repositories completed (75%)
Synchronization complete: 98 succeeded, 2 failed
Total duration: 8m 32s, Average per repository: 5.1s
Success rate: 98%
```

### Performance Optimization

The tool includes several performance optimizations for faster and more efficient operation:

#### Git Operations

**Shallow Clones:**
- By default, initial clones use `--depth=1` for faster downloads
- Reduces bandwidth and storage for repositories with long history
- Can be disabled in config if full history is needed

**Compression:**
- Git compression level set to 9 (maximum) by default
- Reduces network transfer size and storage usage
- Configurable via `compression_level` setting

**HTTP/2:**
- Enabled by default for git operations
- Provides better multiplexing and reduced latency
- Falls back to HTTP/1.1 if not available

#### Network Operations

**Connection Pooling:**
- Reuses HTTP connections for GitHub API calls
- Default pool size: 16 connections
- Keep-alive: 300 seconds (5 minutes)
- Significantly reduces connection overhead

**Timeouts:**
- Connection timeout: 30 seconds
- Read timeout: 60 seconds
- Write timeout: 60 seconds
- All configurable via `[performance]` section

#### File System Optimizations

**Credential File Reuse:**
- Credentials file created once and reused
- Avoids redundant file I/O operations
- Enabled by default via `reuse_credentials_file`

**Directory Creation:**
- Parent directories created once and cached
- Eliminates redundant filesystem checks
- Automatic synchronization for parallel operations

#### Performance Configuration

Add a `[performance]` section to your `config.toml`:

```toml
[performance]
# Git operation settings
shallow_clone = true          # Use --depth=1 for faster initial clones
clone_depth = 1               # Depth for shallow clones
compression_level = 9         # Git compression (0-9, 9 = max)

# Network settings
connection_timeout = 30       # Connection timeout in seconds
read_timeout = 60             # Read timeout in seconds
write_timeout = 60            # Write timeout in seconds
connection_pool_size = 16     # HTTP connection pool size
connection_pool_keep_alive = 300  # Keep-alive in seconds
enable_http2 = true           # Enable HTTP/2 for git operations

# File system settings
reuse_credentials_file = true # Reuse credentials file (recommended)
batch_size = 100              # API batch size for pagination
```

#### Performance Best Practices

**For GitHub Synchronization:**
- Use 4-8 workers to stay within API rate limits
- Enable shallow clones for faster initial sync
- Use HTTP/2 for better performance
- Increase connection pool size if syncing many repos

**For Self-Hosted Git Servers:**
- Can use higher worker counts (10-16)
- Adjust timeouts based on server performance
- Consider disabling shallow clones if server doesn't support it
- Monitor server load and adjust workers accordingly

**Network Optimization:**
- Use wired connection for large syncs when possible
- Schedule syncs during off-peak hours
- Monitor bandwidth usage with high worker counts
- Enable compression to reduce transfer sizes

**Disk I/O Optimization:**
- Use SSD storage for best performance
- Ensure sufficient disk space (repos can be large)
- Consider using a separate partition for backups
- Monitor disk I/O during sync operations

**Memory Usage:**
- Each worker requires memory for git operations
- Typical usage: 50-100 MB per worker
- Total memory: (workers × 100 MB) + base overhead
- Example: 8 workers ≈ 800 MB + 200 MB base = 1 GB

#### Troubleshooting Performance Issues

**Slow API Calls:**
- Check internet connection speed
- Verify GitHub API rate limits
- Reduce workers if hitting rate limits
- Enable debug logging: `-vv`

**Slow Git Operations:**
- Increase timeout values
- Check disk I/O performance
- Verify network bandwidth
- Consider using shallow clones

**High Memory Usage:**
- Reduce number of workers
- Monitor with system tools (top, htop)
- Check for memory leaks with `-vvv` logging

**High CPU Usage:**
- Normal during compression operations
- Reduce compression_level if needed
- Adjust worker count based on CPU cores

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
