use std::collections::HashSet;
use std::io::Write;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;
use std::{fs, io};
use std::env;

use git2::{Cred, FetchOptions, RemoteCallbacks, Repository};
use rayon::prelude::*;
use rayon::ThreadPoolBuilder;

mod args;
mod config;
mod github;

/// Number of attempts to retry a fetch operation upon failure.
const FETCH_RETRIES: usize = 3;

fn setup_ssl(config: &config::SslConfig) {
    // Set SSL backend
    if let Err(e) = git2::opts::set_ssl_backend(&config.backend) {
        eprintln!("Warning: Failed to set SSL backend '{}': {}", config.backend, e);
    }

    // Set SSL certificate file if specified
    if let Some(cert_file) = &config.cert_file {
        if let Err(e) = env::set_var("SSL_CERT_FILE", cert_file) {
            eprintln!("Warning: Failed to set SSL_CERT_FILE: {}", e);
        }
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
        experimental_archive,
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

    if let Some(github) = config.github {
        let github_client = github::GitHub::new();

        println!("Querying GitHub information for {0}…", &github.user);
        io::stdout().flush().unwrap();
        let user_repos = github_client.user_repos(&github.user, &github.token);
        if verbose {
            dbg!(&user_repos);
        }
        println!();

        let mut github_dir = destination.clone();
        github_dir.push("github");

        let mut archive_dir = github_dir.clone();
        archive_dir.push("archive");

        let mut archive_repos = vec![];
        // archive_repos.extend(github.archive.repos.clone());
        if github.archive.owned {
            archive_repos.extend(user_repos.owned.clone());
        }
        let archive_repos: HashSet<String> = archive_repos.into_iter().collect();

        if experimental_archive {
            println!("Archiving {0} GitHub repositories…", archive_repos.len());
            for (i, repo) in archive_repos.iter().enumerate() {
                print!(
                    "{0}/{1} Archiving {2}… ",
                    i + 1,
                    &archive_repos.len(),
                    &repo
                );
                io::stdout().flush().unwrap();

                github_client.archive_repo(archive_dir.as_path(), &repo, &github.token);

                println!("Done");
            }
            println!();
        }

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
                    print!("\r{0}/{1} ", i + 1, &gist_names.len());
                    io::stdout().flush().unwrap();

                    let url = format!("https://gist.github.com/{0}.git", &name);
                    let username = &github.user;
                    let password = &github.token;
                    clone_or_fetch_bare(
                        &gists_dir,
                        &name,
                        &url,
                        dry_run,
                        Some((username, password)),
                        &config.ssl,
                    );
                }
                println!("\n");
            }
        }

        let mut clone_dir = github_dir;
        clone_dir.push("clone");

        let mut clone_repos = vec![];
        if owned {
            clone_repos.extend(user_repos.owned.clone());
        }
        clone_repos.extend(archive_repos);
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
                clone_or_fetch_bare(&clone_dir, repo, &url, dry_run, Some((username, password)), &config.ssl);
            });
        } else {
            for (i, repo) in clone_repos.iter().enumerate() {
                print!("\r{0}/{1} ", i + 1, &clone_repos.len());
                io::stdout().flush().unwrap();

                let url = format!("https://github.com/{0}.git", &repo);
                let username = &github.user;
                let password = &github.token;
                clone_or_fetch_bare(&clone_dir, &repo, &url, dry_run, Some((username, password)), &config.ssl);
            }
            println!("\n");
        }
    }

    if let Some(git) = config.git {
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
                print!("\r{0}/{1} ", i + 1, git.repos.len());
                io::stdout().flush().unwrap();

                let url = url.as_str().unwrap();
                clone_or_fetch_bare(&git_dir, &path, url, dry_run, None, &config.ssl);
            }
            println!("\n");
        }
    }

    println!("Done!");
}

fn clone_or_fetch_bare(
    dir: &PathBuf,
    repository: &str,
    url: &str,
    dry_run: bool,
    credentials: Option<(&str, &str)>,
    ssl_config: &config::SslConfig,
) {
    let mut updated = false;

    {
        let mut callbacks = RemoteCallbacks::new();

        if let Some((username, password)) = credentials {
            callbacks.credentials(move |_url, _username_from_url, _allowed_types| {
                Cred::userpass_plaintext(username, password)
            });
        }

        callbacks.transfer_progress(|_progress| {
            if !updated {
                print!("Synchronizing {0} from {1}… ", &repository, &url);
                io::stdout().flush().unwrap();
                updated = true;
            }
            true
        });

        let mut fo = FetchOptions::new();
        fo.remote_callbacks(callbacks);

        // Configure SSL options
        let mut ssl_opts = git2::SslOptions::new();
        ssl_opts.verify_certificates(ssl_config.verify_certificates);
        fo.ssl_options(ssl_opts);

        if !dry_run {
            let mut repo_dir = dir.clone();
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
                        thread::sleep(Duration::from_secs(2));
                    }
                }
            }
        }
    }

    if updated {
        println!("Done")
    }
}

#[cfg(test)]
mod clone_or_fetch_bare_tests;
