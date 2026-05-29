"""Construction of the ``git`` argv for clone/update operations.

Port of the command builder in ``Engine.kt`` (the ``buildGitCommand`` helper around
line 950). This is the heart of the tool, so the exact argv is captured as fixtures.
``build_git_command`` is implemented in Phase 1.

Argv shape (in order):

    git
      -c safe.directory=*
      [-c http.sslVerify=false]                 # when not verify_certificates
      [-c http.version=HTTP/1.1]                # when force_http1 or http_version == HTTP/1.1
      -c http.postBuffer=<post_buffer_size>
      [-c http.lowSpeedLimit=<n> -c http.lowSpeedTime=<n>]   # when low_speed_limit > 0
      [-c credential.helper=store --file=<credentials_path>] # when credentials_path
      <operation...>

    operation when repo does NOT exist:
      shallow:        clone --depth=1 --single-branch [--progress] -- <url> <repo_name>
      single_branch:  clone --bare --single-branch [--branch <default_branch>]
                            [--progress] -- <url> <repo_name>
      mirror:         clone --mirror [--progress] -- <url> <repo_name>

    operation when repo exists:
      single_branch_only: fetch --prune origin
      mirror (default):   remote update --prune
"""

from __future__ import annotations

GIT_EXECUTABLE = "git"


def build_git_command(
    *,
    repo_exists: bool,
    url: str | None = None,
    repo_name: str | None = None,
    git_executable: str = GIT_EXECUTABLE,
    verify_certificates: bool = True,
    http_version: str = "HTTP/1.1",
    post_buffer_size: int = 524_288_000,
    low_speed_limit: int = 1000,
    low_speed_time: int = 60,
    credentials_path: str | None = None,
    force_http1: bool = False,
    use_shallow_clone: bool = False,
    show_progress: bool = False,
    single_branch_only: bool = False,
    default_branch: str | None = None,
) -> list[str]:
    """Build the full ``git`` argv for a clone or update of a single repository."""
    raise NotImplementedError("Phase 1: port Engine.buildGitCommand")
