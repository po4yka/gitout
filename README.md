Git Out
=======

A command-line tool to automatically backup Git repositories from GitHub or anywhere.

The `gitout` tool will clone git repos from GitHub or any other git hosting service.
If the repository was already cloned, it will fetch any updates to keep your local copy in sync.

When you add your GitHub username and a token, `gitout` can discover your repositories.
Use the `--owned`, `--starred` (or `--stars`), and `--watched` flags to specify which
repositories are synchronized. When a flag is omitted the behavior falls back to the
values configured in `config.toml`.

The cloned repositories are [bare](https://www.saintsjd.com/2011/01/what-is-a-bare-git-repository/).
In other words, there is no working copy of the files for you to interact with.
If you need access to the files, you can `git clone /path/to/bare/repo`.


Installation
------------

### Rust

If you have Rust installed you can install the binary by running `cargo install gitout`.

[![Latest version](https://img.shields.io/crates/v/gitout.svg)](https://crates.io/crates/gitout)

### Docker

The binary is available inside the `po4yka/gitout` Docker container which can run it as a cron job.

[![Docker Image Version](https://img.shields.io/docker/v/po4yka/gitout?sort=semver)][hub]
[![Docker Image Size](https://img.shields.io/docker/image-size/po4yka/gitout)][layers]

 [hub]: https://hub.docker.com/r/po4yka/gitout/
 [layers]: https://hub.docker.com/r/po4yka/gitout

Mount a `/data` volume which is where the repositories will be stored.
Mount the `/app/config.toml` file directly with your configuration.
Specify a `CRON` environment variable with a cron specifier dictating the schedule for when the tool should run.

```bash
$ docker run -d \
    -v /path/to/data:/data \
    -v /path/to/config.toml:/app/config.toml:ro \
    -e "CRON=0 * * * *" \
    po4yka/gitout \
    /app/gitout \
    --owned --starred --watched \
    /app/config.toml \
    /data
```

For help creating a valid cron specifier, visit [cron.help](https://cron.help/#0_*_*_*_*).

To be notified when sync is failing visit https://healthchecks.io, create a check, and specify the ID to the container using the `HEALTHCHECK_ID` environment variable (for example, `-e "HEALTHCHECK_ID=..."`).

To write data as a particular user, the `PUID` and `PGID` environment variables can be set to your user ID and group ID, respectively.

If you're using Docker Compose, an example setup looks like:
```yaml
services:
  gitout:
    image: po4yka/gitout:latest
    restart: unless-stopped
    volumes:
      - /path/to/data:/data
      - /path/to/config.toml:/app/config.toml:ro
    command: >
      /app/gitout
      --owned --starred --watched
      /app/config.toml
      /data
    environment:
      - "CRON=0 * * * *"
      # Optional:
      - "HEALTHCHECK_ID=..."
      - "PUID=..."
      - "PGID=..."
      - "GITHUB_TOKEN_FILE=/run/secrets/github_pat"  # If using Docker secrets
    secrets:
      - github_pat  # If using Docker secrets

secrets:
  github_pat:
    file: /path/to/github_token.txt  # Path to your GitHub token file
```

Here's a complete example of both configuration files:

1. `/opt/gitout/config.toml`:
```toml
version = 0

[github]
user = "your-github-username"
# token is not needed if using GITHUB_TOKEN_FILE in docker-compose

[github.archive]
owned = true

[github.clone]
starred = true
watched = true
gists = true
repos = [
    # Add specific repositories to clone
    # "username/repo-name"
]
ignored = [
    # Add repositories to ignore
    # "username/repo-name"
]

[git.repos]
# Add non-GitHub git repositories
# example = "https://example.com/example.git"
```

2. `/opt/gitout/docker-compose.yml`:
```yaml
services:
  watchtower:
    image: containrrr/watchtower
    container_name: watchtower
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    command: --cleanup --interval 21600
    restart: unless-stopped

  gitout:
    image: ghcr.io/po4yka/gitout:latest
    platform: linux/arm64  # or linux/amd64 for x86_64
    restart: unless-stopped
    volumes:
      - /mnt/nvme/git-backups:/data
      - /opt/gitout/config.toml:/app/config.toml:ro
    environment:
      - GITHUB_TOKEN_FILE=/run/secrets/github_pat
      - CRON=0 0 * * *  # Run daily at midnight
    secrets:
      - github_pat
    command: >
      /app/gitout
      --stars --owned --watched
      --interval 86400
      --workers 4
      /app/config.toml
      /data

secrets:
  github_pat:
    file: /opt/gitout/github_token.txt
```

To set up:

1. Create the config directory and files:
```bash
sudo mkdir -p /opt/gitout
sudo nano /opt/gitout/config.toml  # Add the config.toml content
sudo nano /opt/gitout/github_token.txt  # Add your GitHub token
sudo nano /opt/gitout/docker-compose.yml  # Add the docker-compose.yml content
```

2. Set proper permissions:
```bash
sudo chown -R root:root /opt/gitout
sudo chmod 644 /opt/gitout/config.toml
sudo chmod 600 /opt/gitout/github_token.txt
sudo chmod 644 /opt/gitout/docker-compose.yml
```

3. Start the services:
```bash
cd /opt/gitout
sudo docker compose up -d
```

Note: You may want to specify an explicit version rather than `latest`.
See https://hub.docker.com/r/po4yka/gitout/tags or `CHANGELOG.md` for the available versions.

#### Publishing with your own Docker credentials

If you maintain a fork or need to push images to your own Docker Hub account, add `DOCKER_USERNAME` and `DOCKER_PASSWORD` secrets to your repository and enable a login step in the build workflow:

```yaml
- uses: docker/login-action@v2
  with:
    username: ${{ secrets.DOCKER_USERNAME }}
    password: ${{ secrets.DOCKER_PASSWORD }}
```

Update the workflow's `images` setting to reference your repository before pushing.

### Binaries

Prebuilt binaries for Linux, macOS, and Windows are available on the
[GitHub releases page](https://github.com/JakeWharton/gitout/releases).
Download the archive for your platform, extract it, and place the `gitout`
executable somewhere on your `PATH`.

#### Building your own

If you want to build these binaries yourself, compile a release binary for each
target. When cross-compiling from Linux, [`cross`](https://github.com/cross-rs/cross)
is the easiest approach:

```bash
cross build --release --target x86_64-unknown-linux-gnu    # Linux
cross build --release --target x86_64-apple-darwin         # macOS
cross build --release --target x86_64-pc-windows-gnu       # Windows
```

Each build will produce `gitout` (or `gitout.exe` on Windows) under
`target/<target>/release/`. Package the executable into a `.tar.gz` or `.zip`
archive and upload it as a release asset on GitHub.


Usage
-----

```
$ gitout --help
gitout 0.2.0

Usage: gitout [OPTIONS] <CONFIG> <DESTINATION>

Arguments:
  <CONFIG>       Configuration file
  <DESTINATION>  Backup directory

Options:
  -v, --verbose               Enable verbose logging
      --experimental-archive  Enable experimental repository archiving
      --dry-run               Print actions instead of performing them
      --owned                 Include repositories you own
      --starred               Include repositories you have starred
      --watched               Include repositories you watch
  -h, --help                  Print help
  -V, --version               Print version
```


Configuration specification
---------------------------

Until version 1.0 of the tool, the TOML version is set to 0 and may change incompatibly between 0.x releases.
You can find migration information in the `CHANGELOG.md` file.

```toml
version = 0

[github]
user = "example"
token = "abcd1234efgh5678ij90"

[github.clone]
starred = true  # Optional, default false
watched = true  # Optional, default false
# Extra repos to synchronize that are not owned, starred, or watched by you.
repos = [
  "JakeWharton/gitout",
]
# Repos temporary or otherwise that you do not want to be synchronized.
ignored = [
  "JakeWharton/TestParameterInjector",
]

# Repos not on GitHub to synchronize.
[git.repos]
asm = "https://gitlab.ow2.org/asm/asm.git"
```

### Configuring SSL certificates

`gitout` relies on system SSL certificates to securely connect to remote
repositories. If your system stores certificates in a non-standard location,
specify the path in your configuration file under the `ssl` section or set the
`SSL_CERT_FILE` environment variable.

```toml
[ssl]
cert_file = "/etc/ssl/certs/ca-certificates.crt"  # Path to your certificate bundle
```

When no certificate path is configured, `gitout` will attempt a few common
locations such as `/etc/ssl/certs/ca-certificates.crt` and `/usr/lib/ssl/cert.pem`.

### Creating a GitHub token

1. Visit https://github.com/settings/tokens
2. Click "Generate new token" under **"Personal access tokens (classic)"**
3. Type "gitout" in the name field
4. Select the "repo", "gist", and "read:user" scopes
     - `repo`: Needed to discover and clone private repositories (if you only have public repositories then just `public_repo` will also work)
     - `gist`: Needed to discover and clone private gists (if you only have public gists then this is not required)
     - `read:user`: Needed to traverse your owned, starred, and watched repo lists
   - *Fine-grained personal access tokens are **not** supported by the GitHub migrations API.* Use a classic token instead.
5. Select "Generate token"
  6. Copy the value into your `config.toml` as it will not be shown again


Development
-----------

If you have Rust installed, a debug binary can be built with `cargo build` and a release binary with `cargo build --release`.
The binary will be in `target/debug/gitout` or `target/release/gitout`, respectively.
Run all the tests with `cargo test`.
Format the code with `cargo fmt`.
Run the Clippy tool with `cargo clippy`.

If you have Docker but not Rust, run `docker build .` which will do everything. This is what runs on CI.


LICENSE
======

MIT. See `LICENSE.txt`.

    Copyright 2020 Jake Wharton
