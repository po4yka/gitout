use super::clone_or_fetch_bare;
use git2::Repository;
use std::path::{Path, PathBuf};
use std::process::Command;
use tempfile::TempDir;
use crate::config::SslConfig;

fn git(dir: Option<&Path>, args: &[&str]) {
    let mut cmd = Command::new("git");
    if let Some(d) = dir {
        cmd.current_dir(d);
    }
    cmd.args(args);
    let status = cmd.status().expect("git command");
    assert!(status.success());
}

fn setup_remote() -> (TempDir, PathBuf, PathBuf) {
    let temp = TempDir::new().unwrap();
    let remote_dir = temp.path().join("remote.git");
    git(None, &["init", "--bare", remote_dir.to_str().unwrap()]);

    let work_dir = temp.path().join("work");
    git(None, &["init", "-b", "master", work_dir.to_str().unwrap()]);
    git(
        Some(&work_dir),
        &["config", "user.email", "test@example.com"],
    );
    git(Some(&work_dir), &["config", "user.name", "Test"]);
    git(
        Some(&work_dir),
        &["remote", "add", "origin", remote_dir.to_str().unwrap()],
    );
    git(Some(&work_dir), &["commit", "--allow-empty", "-m", "init"]);
    git(Some(&work_dir), &["push", "-u", "origin", "master"]);

    (temp, remote_dir, work_dir)
}

fn head_commit(dir: &Path) -> String {
    let output = Command::new("git")
        .current_dir(dir)
        .args(["rev-parse", "HEAD"])
        .output()
        .unwrap();
    assert!(output.status.success());
    String::from_utf8(output.stdout).unwrap().trim().to_string()
}

fn add_commit(dir: &Path, msg: &str) -> String {
    git(Some(dir), &["commit", "--allow-empty", "-m", msg]);
    git(Some(dir), &["push"]);
    head_commit(dir)
}

#[test]
fn dry_run_skips_clone() {
    let (temp, remote, _work) = setup_remote();
    let dest = temp.path().join("dest");
    let ssl_config = SslConfig::default();

    clone_or_fetch_bare(
        &dest,
        "repo",
        remote.to_str().unwrap(),
        true,
        None,
        &ssl_config,
    );

    assert!(!dest.join("repo").exists());
}

#[test]
fn clones_repository() {
    let (temp, remote, work) = setup_remote();
    let dest = temp.path().join("dest");
    let first_commit = head_commit(&work);
    let ssl_config = SslConfig::default();

    clone_or_fetch_bare(
        &dest,
        "repo",
        remote.to_str().unwrap(),
        false,
        None,
        &ssl_config,
    );

    let repo = Repository::open_bare(dest.join("repo")).unwrap();
    let head = repo.refname_to_id("refs/remotes/origin/master").unwrap();
    assert_eq!(head.to_string(), first_commit);
}

#[test]
fn fetches_updates() {
    let (temp, remote, work) = setup_remote();
    let dest = temp.path().join("dest");
    let ssl_config = SslConfig::default();
    clone_or_fetch_bare(
        &dest,
        "repo",
        remote.to_str().unwrap(),
        false,
        None,
        &ssl_config,
    );

    let new_commit = add_commit(&work, "update");

    clone_or_fetch_bare(
        &dest,
        "repo",
        remote.to_str().unwrap(),
        false,
        None,
        &ssl_config,
    );

    let repo = Repository::open_bare(dest.join("repo")).unwrap();
    let head = repo.refname_to_id("refs/remotes/origin/master").unwrap();
    assert_eq!(head.to_string(), new_commit);
}
