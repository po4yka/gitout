use std::collections::HashSet;
use std::env;
use std::fs;
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::Duration;

use git2::{opts, Cred, ErrorClass, FetchOptions, RemoteCallbacks, Repository};
use rayon::prelude::*;
use rayon::ThreadPoolBuilder;

mod args;
mod config;
mod github;

/// Number of attempts to retry a fetch operation upon failure.
const FETCH_RETRIES: usize = 6;

/// Delay (in seconds) between retry attempts.
const RETRY_DELAY_SECS: u64 = 5;

/// Stagger (in milliseconds) before starting a clone or fetch. This reduces the
/// thundering-herd effect when many worker threads start at once.
const STAGGER_MS: u64 = 300;

fn setup_ssl(config: &config::SslConfig) {
    // Set SSL certificate file if specified
    if let Some(cert_file) = &config.cert_file {
        env::set_var("SSL_CERT_FILE", cert_file);
        if let Some(dir) = Path::new(cert_file).parent().and_then(|p| p.to_str()) {
            env::set_var("SSL_CERT_DIR", dir);
            unsafe {
                let _ = opts::set_ssl_cert_dir(dir);
            }
        }
        // Also try to configure libgit2 directly
        unsafe {
            if let Err(e) = opts::set_ssl_cert_file(cert_file) {
                eprintln!("Failed to set libgit2 SSL cert file {cert_file}: {e}");
            }
        }
    } else if env::var_os("SSL_CERT_FILE").is_none() {
        // Attempt a few common paths if the environment variable is unset.
        for candidate in [
            "/etc/ssl/certs/ca-certificates.crt",
            "/etc/ssl/cert.pem",
            "/usr/lib/ssl/cert.pem",
        ] {
            if fs::metadata(candidate).is_ok() {
                env::set_var("SSL_CERT_FILE", candidate);
                if let Some(dir) = Path::new(candidate).parent().and_then(|p| p.to_str()) {
                    env::set_var("SSL_CERT_DIR", dir);
                    unsafe {
                        let _ = opts::set_ssl_cert_dir(dir);
                    }
                }
                unsafe {
                    let _ = opts::set_ssl_cert_file(candidate);
                }
                break;
            }
        }
    } else if env::var_os("SSL_CERT_DIR").is_none() {
        if let Ok(file) = env::var("SSL_CERT_FILE") {
            if let Some(dir) = Path::new(&file).parent().and_then(|p| p.to_str()) {
                env::set_var("SSL_CERT_DIR", dir);
                unsafe {
                    let _ = opts::set_ssl_cert_dir(dir);
                }
            }
        }
    }

    if !config.verify_certificates {
        env::set_var("GIT_SSL_NO_VERIFY", "1");
    }

    // Extend libgit2 network timeouts. The defaults can be as low as 15s which
    // is sometimes not enough when talking to GitHub over slow or flaky
    // connections.
    unsafe {
        // 60 s to establish the TCP/TLS connection.
        let _ = opts::set_server_connect_timeout_in_milliseconds(60_000);
        // 10 min overall I/O timeout.
        let _ = opts::set_server_timeout_in_milliseconds(600_000);
    }
}

fn main() {
    let args = args::parse_args();
    if args.verbose {
        dbg!(&args);
    }

    if args.workers > 1 {
        ThreadPoolBuilder::new()
            .num_threads(args.workers)
            .thread_name(|i| format!("gitout-worker-{}", i))
            .build_global()
            .unwrap();
    }

    loop {
        sync_once(&args);

        if let Some(interval) = args.interval {
            thread::Builder::new()
                .name("gitout-sync".to_string())
                .spawn(move || {
                    thread::sleep(Duration::from_secs(interval));
                })
                .unwrap()
                .join()
                .unwrap();
        } else {
            break;
        }
    }
}

fn sync_once(args: &args::Args) {
    let args::Args {
        config: config_path,
        destination,
        verbose,
        dry_run,
        owned,
        starred,
        watched,
        ..
    } = args.clone();

    if !dry_run {
        let destination_metadata = match fs::metadata(&destination) {
            Ok(m) => m,
            Err(e) => {
                eprintln!("Failed to access destination {:?}: {}", destination, e);
                return;
            }
        };
        if !destination_metadata.is_dir() {
            eprintln!("Destination must exist and must be a directory");
            return;
        }
    }

    // Check if config path exists and is a file
    let config_metadata = match fs::metadata(&config_path) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("Failed to access config file at {:?}: {}", config_path, e);
            return;
        }
    };
    if config_metadata.is_dir() {
        eprintln!(
            "Config path {:?} is a directory, but must be a file",
            config_path
        );
        return;
    }

    let config = match fs::read_to_string(&config_path) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to read config file at {:?}: {}", config_path, e);
            return;
        }
    };
    let mut config = match config::parse_config(&config) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to parse config file at {:?}: {}", config_path, e);
            return;
        }
    };

    // Initialize SSL settings
    setup_ssl(&config.ssl);

    if let Some(ref mut github) = config.github {
        if starred {
            github.clone.starred = true;
        }
        if watched {
            github.clone.watched = true;
        }
    }
    if verbose {
        dbg!(&config);
    }

    if let Some(github) = &config.github {
        let mut github_dir = destination.clone();
        github_dir.push("github");

        let user_repos = github::GitHub::new().user_repos(&github.user, &github.token);

        if github.clone.gists {
            let mut gists_dir = github_dir.clone();
            gists_dir.push("gists");

            let gist_names = user_repos.gists;
            println!("Checking {0} GitHub gists for updates…", gist_names.len());
            if args.workers > 1 {
                gist_names.par_iter().for_each(|name| {
                    let url = format!("https://gist.github.com/{0}.git", name);
                    let username = &github.user;
                    let password = &github.token;
                    clone_or_fetch_bare(
                        &gists_dir,
                        name,
                        &url,
                        dry_run,
                        Some((username, password)),
                        &config.ssl,
                    );
                });
            } else {
                for (i, name) in gist_names.iter().enumerate() {
                    println!("({}/{})", i + 1, gist_names.len());

                    let url = format!("https://gist.github.com/{0}.git", name);
                    let username = &github.user;
                    let password = &github.token;
                    clone_or_fetch_bare(
                        &gists_dir,
                        name,
                        &url,
                        dry_run,
                        Some((username, password)),
                        &config.ssl,
                    );
                }
            }
        }

        let mut clone_dir = github_dir;
        clone_dir.push("clone");

        let mut clone_repos = vec![];
        if owned {
            clone_repos.extend(user_repos.owned.clone());
        }
        clone_repos.extend(github.clone.repos.clone());
        if github.clone.starred {
            clone_repos.extend(user_repos.starred);
        }
        if github.clone.watched {
            clone_repos.extend(user_repos.watched);
        }
        if !github.clone.ignored.is_empty() {
            clone_repos.retain(|r| !github.clone.ignored.contains(r))
        }
        let clone_repos: HashSet<String> = clone_repos.into_iter().collect();

        println!(
            "Checking {0} GitHub repositories for updates…",
            clone_repos.len()
        );
        if args.workers > 1 {
            clone_repos.par_iter().for_each(|repo| {
                let url = format!("https://github.com/{0}.git", repo);
                let username = &github.user;
                let password = &github.token;
                clone_or_fetch_bare(
                    &clone_dir,
                    repo,
                    &url,
                    dry_run,
                    Some((username, password)),
                    &config.ssl,
                );
            });
        } else {
            for (i, repo) in clone_repos.iter().enumerate() {
                println!("({}/{})", i + 1, clone_repos.len());

                let url = format!("https://github.com/{0}.git", repo);
                let username = &github.user;
                let password = &github.token;
                clone_or_fetch_bare(
                    &clone_dir,
                    repo,
                    &url,
                    dry_run,
                    Some((username, password)),
                    &config.ssl,
                );
            }
        }
    }

    if let Some(ref git) = config.git {
        let mut git_dir = destination;
        git_dir.push("git");

        println!(
            "Checking {0} git repository clones for updates…",
            git.repos.len()
        );
        if args.workers > 1 {
            let repos: Vec<_> = git.repos.iter().collect();
            repos.par_iter().for_each(|(path, url)| {
                let url = url.as_str().unwrap();
                clone_or_fetch_bare(&git_dir, path, url, dry_run, None, &config.ssl);
            });
        } else {
            for (i, (path, url)) in git.repos.iter().enumerate() {
                println!("({}/{})", i + 1, git.repos.len());
                let url = url.as_str().unwrap();
                clone_or_fetch_bare(&git_dir, path, url, dry_run, None, &config.ssl);
            }
        }
    }
}

fn clone_or_fetch_bare(
    dir: &Path,
    repository: &str,
    url: &str,
    dry_run: bool,
    credentials: Option<(&str, &str)>,
    _ssl_config: &config::SslConfig, // Mark as intentionally unused
) {
    // Stagger requests a bit to avoid hammering the same remote when many
    // threads start simultaneously.
    thread::sleep(Duration::from_millis(STAGGER_MS));

    let mut updated = false;

    {
        let mut callbacks = RemoteCallbacks::new();

        if let Some((username, password)) = credentials {
            callbacks.credentials(move |_url, _username_from_url, _allowed_types| {
                Cred::userpass_plaintext(username, password)
            });
        }

        // Emit a log line once the transfer actually starts so that idle
        // repositories do not produce noise. The callback ensures the log is
        // printed only once per repository when progress information becomes
        // available.
        callbacks.transfer_progress(|_progress| {
            if !updated {
                println!(
                    "Synchronizing {repo} from {url}…",
                    repo = repository,
                    url = url
                );
                updated = true;
            }
            true
        });

        let mut fo = FetchOptions::new();
        fo.remote_callbacks(callbacks);

        if !dry_run {
            let mut repo_dir = dir.to_path_buf();
            repo_dir.push(repository);

            // Ensure the parent directory exists
            if let Some(parent) = repo_dir.parent() {
                fs::create_dir_all(parent).unwrap();
            }

            let repo_exists = fs::metadata(&repo_dir).map_or_else(|_| false, |m| m.is_dir());

            // If repo_dir exists but is not a directory, remove it
            if fs::metadata(&repo_dir).map_or_else(|_| false, |m| !m.is_dir()) {
                fs::remove_file(&repo_dir).unwrap();
            }

            let git_repo: Repository;
            let mut origin = if repo_exists {
                git_repo = Repository::open_bare(&repo_dir).unwrap();
                git_repo.find_remote("origin").unwrap()
            } else {
                fs::create_dir_all(&repo_dir).unwrap();
                git_repo = Repository::init_bare(&repo_dir).unwrap();
                git_repo.remote("origin", url).unwrap()
            };

            let mut attempts = 0;
            loop {
                match origin.fetch(&[] as &[String], Some(&mut fo), None) {
                    Ok(_) => break,
                    Err(e) => {
                        attempts += 1;
                        if attempts >= FETCH_RETRIES {
                            // As a last-ditch effort, fall back to the git CLI. libgit2 is
                            // sometimes fragile with TLS (see libgit2/libgit2#5279 and similar
                            // reports).  If invoking the system `git` succeeds, we treat the
                            // operation as successful to avoid aborting the whole sync.
                            if e.class() == ErrorClass::Ssl {
                                if git_clone_or_fetch_cli(dir, repository, url) {
                                    println!(
                                        "Fallback to git CLI succeeded for {repo}",
                                        repo = repository
                                    );
                                    break;
                                }
                            }
                            eprintln!(
                                "Failed to synchronize {repo} from {url} after {attempts} attempts: {error}",
                                repo = repository,
                                url = url,
                                attempts = attempts,
                                error = e
                            );
                            return;
                        }

                        eprintln!(
                            "Attempt {attempt} to synchronize {repo} from {url} failed: {error}. Retrying…",
                            attempt = attempts,
                            repo = repository,
                            url = url,
                            error = e
                        );
                        if e.class() == ErrorClass::Ssl {
                            let file = env::var("SSL_CERT_FILE").unwrap_or_default();
                            let dir = env::var("SSL_CERT_DIR").unwrap_or_default();
                            eprintln!(
                                "SSL error details: SSL_CERT_FILE='{file}', SSL_CERT_DIR='{dir}'"
                            );
                        }
                        thread::sleep(Duration::from_secs(RETRY_DELAY_SECS));
                    }
                }
            }
        }
    }

    if updated {
        println!("Finished synchronizing {repo}", repo = repository);
    }
}

/// Use the system `git` executable as a fallback when libgit2 trips over TLS problems.
/// Returns `true` if the git command completed successfully.
fn git_clone_or_fetch_cli(dir: &Path, repository: &str, url: &str) -> bool {
    let mut repo_dir = dir.to_path_buf();
    repo_dir.push(repository);

    let status = if repo_dir.exists() {
        Command::new("git")
            .arg("-C")
            .arg(&repo_dir)
            .arg("fetch")
            .arg("--prune")
            .status()
    } else {
        // `--mirror` creates a bare clone and keeps remote refs, similar to libgit2's behaviour.
        Command::new("git")
            .arg("clone")
            .arg("--mirror")
            .arg(url)
            .arg(&repo_dir)
            .status()
    };

    match status {
        Ok(s) if s.success() => true,
        Ok(s) => {
            eprintln!("git exited with status {s}");
            false
        }
        Err(e) => {
            eprintln!("Failed to spawn git: {e}");
            false
        }
    }
}

#[cfg(test)]
mod clone_or_fetch_bare_tests;
