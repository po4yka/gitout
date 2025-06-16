use std::fs::File;
use std::io::copy;
use std::path::Path;
use std::time::Duration;
use std::{fs, thread};

use graphql_client::{GraphQLQuery, Response};
use reqwest::blocking::Client;
use reqwest::header::ACCEPT;
use serde::Deserialize;
use serde::Serialize;

#[derive(GraphQLQuery)]
#[graphql(
    schema_path = "src/github_schema.graphql",
    query_path = "src/github_repos.graphql",
    response_derives = "Debug"
)]
struct UserRepos;

const APP_USER_AGENT: &str = concat!(env!("CARGO_PKG_NAME"), "/", env!("CARGO_PKG_VERSION"));
/// Accept header required for user migrations API.
/// https://docs.github.com/rest/migrations/users#start-a-user-migration
const GITHUB_ACCEPT: &str = "application/vnd.github+json";
/// API version header for GitHub requests.
/// The migrations API only supports the 2022-11-28 version as of now.
const GITHUB_API_VERSION: &str = "2022-11-28";

pub struct GitHub {
    client: Client,
}

impl GitHub {
    pub fn new() -> Self {
        let client = Client::builder()
            .user_agent(APP_USER_AGENT)
            .build()
            .unwrap();
        GitHub { client }
    }

    pub fn user_repos(&self, user: &str, token: &str) -> Repositories {
        let mut owned_after: Option<String> = None;
        let mut owned_repos: Vec<String> = vec![];
        let mut starred_after: Option<String> = None;
        let mut starred_repos: Vec<String> = vec![];
        let mut watched_after: Option<String> = None;
        let mut watched_repos: Vec<String> = vec![];
        let mut gists_after: Option<String> = None;
        let mut gists_repos: Vec<String> = vec![];
        loop {
            let query = UserRepos::build_query(user_repos::Variables {
                login: user.to_string(),
                owner_after: owned_after.clone(),
                starred_after: starred_after.clone(),
                watched_after: watched_after.clone(),
                gists_after: gists_after.clone(),
            });
            let response = self
                .client
                .post("https://api.github.com/graphql")
                .bearer_auth(token)
                .json(&query)
                .send()
                .unwrap();

            let body: Response<user_repos::ResponseData> = response.json().unwrap();
            let user = body.data.unwrap().user.unwrap();

            let owned_response = user.repositories.edges.unwrap();
            let starred_response = user.starred_repositories.edges.unwrap();
            let watched_response = user.watching.edges.unwrap();
            let gists_response = user.gists.edges.unwrap();
            if owned_response.is_empty()
                && starred_response.is_empty()
                && watched_response.is_empty()
                && gists_response.is_empty()
            {
                break;
            }
            for repository in owned_response {
                let repository = repository.unwrap();

                owned_after = Some(repository.cursor);
                owned_repos.push(repository.node.unwrap().name_with_owner);
            }
            for repository in starred_response {
                let repository = repository.unwrap();

                starred_after = Some(repository.cursor);
                starred_repos.push(repository.node.name_with_owner);
            }
            for repository in watched_response {
                let repository = repository.unwrap();

                watched_after = Some(repository.cursor);
                watched_repos.push(repository.node.unwrap().name_with_owner);
            }
            for gist in gists_response {
                let gist = gist.unwrap();

                gists_after = Some(gist.cursor);
                gists_repos.push(gist.node.unwrap().name);
            }
        }

        Repositories {
            owned: owned_repos,
            starred: starred_repos,
            watched: watched_repos,
            gists: gists_repos,
        }
    }

    pub fn archive_repo(&self, dir: &Path, repository: &str, token: &str) {
        let migration_request = MigrationRequest {
            repositories: vec![repository.to_owned()],
        };
        let create_response: MigrationResponse = match self
            .client
            .post("https://api.github.com/user/migrations")
            .bearer_auth(token)
            .header(ACCEPT, GITHUB_ACCEPT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .json(&migration_request)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
        {
            Ok(resp) => match resp.json() {
                Ok(json) => json,
                Err(e) => {
                    eprintln!(
                        "Failed to parse migration creation response for {repo}: {error}",
                        repo = repository,
                        error = e
                    );
                    return;
                }
            },
            Err(e) => {
                eprintln!(
                    "Failed to create migration for {repo}: {error}",
                    repo = repository,
                    error = e
                );
                if let Some(status) = e.status() {
                    eprintln!("GitHub API status: {status}");
                }
                if let Some(url) = e.url() {
                    eprintln!("GitHub API URL: {url}");
                }
                return;
            }
        };
        let migration_id = create_response.id;
        let mut migration_state = create_response.state;

        let mut wait = Duration::from_secs(2);
        loop {
            if migration_state == "exported" {
                break;
            }
            if migration_state == "failed" {
                panic!("Creating migration for {} failed", &repository);
            }

            thread::sleep(wait);
            if wait < Duration::from_secs(64) {
                wait *= 2
            }

            let status_url = format!("https://api.github.com/user/migrations/{0}", migration_id);
            let status_response: MigrationResponse = match self
                .client
                .get(&status_url)
                .bearer_auth(token)
                .header(ACCEPT, GITHUB_ACCEPT)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .send()
                .and_then(reqwest::blocking::Response::error_for_status)
            {
                Ok(resp) => match resp.json() {
                    Ok(json) => json,
                    Err(e) => {
                        eprintln!(
                            "Failed to parse migration status for {repo}: {error}",
                            repo = repository,
                            error = e
                        );
                        return;
                    }
                },
                Err(e) => {
                    eprintln!(
                        "Failed to check migration status for {repo}: {error}",
                        repo = repository,
                        error = e
                    );
                    if let Some(status) = e.status() {
                        eprintln!("GitHub API status: {status}");
                    }
                    if let Some(url) = e.url() {
                        eprintln!("GitHub API URL: {url}");
                    }
                    return;
                }
            };
            migration_state = status_response.state;
        }

        // In order to never lose data if we crash we must perform a dance to update archives:
        // 1. Download the new archive to repo.zip.new.
        // 2. Delete the old archive repo.zip.
        // 3. Rename the new archive from repo.zip.new to repo.zip.

        let mut archive_old = dir.to_path_buf();
        archive_old.push(format!("{0}.zip", &repository));
        let mut archive_new = dir.to_path_buf();
        archive_new.push(format!("{0}.zip.new", &repository));

        let mut archive_dir = archive_old.clone();
        archive_dir.pop();
        if !fs::metadata(&archive_dir).map_or_else(|_| false, |m| m.is_dir()) {
            fs::create_dir_all(&archive_dir).unwrap();
        }

        let archive_old_exists = fs::metadata(&archive_old).map_or_else(|_| false, |m| m.is_file());
        let archive_new_exists = fs::metadata(&archive_new).map_or_else(|_| false, |m| m.is_file());

        if archive_new_exists {
            fs::remove_file(&archive_new).unwrap();
        }

        // Step 1:
        let download_url = format!(
            "https://api.github.com/user/migrations/{0}/archive",
            migration_id
        );
        let mut download_request = match self
            .client
            .get(&download_url)
            .bearer_auth(token)
            .header(ACCEPT, GITHUB_ACCEPT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
        {
            Ok(resp) => resp,
            Err(e) => {
                eprintln!(
                    "Failed to download archive for {repo}: {error}",
                    repo = repository,
                    error = e
                );
                if let Some(status) = e.status() {
                    eprintln!("GitHub API status: {status}");
                }
                if let Some(url) = e.url() {
                    eprintln!("GitHub API URL: {url}");
                }
                return;
            }
        };

        let mut archive_file = File::create(&archive_new).unwrap();
        copy(&mut download_request, &mut archive_file).unwrap();

        // Step 2:
        if archive_old_exists {
            fs::remove_file(&archive_old).unwrap();
        }

        // Step 3:
        fs::rename(&archive_new, &archive_old).unwrap();
    }
}

#[derive(Debug, PartialEq)]
pub struct Repositories {
    pub owned: Vec<String>,
    pub starred: Vec<String>,
    pub watched: Vec<String>,
    pub gists: Vec<String>,
}

#[derive(Serialize)]
struct MigrationRequest {
    repositories: Vec<String>,
}

#[derive(Deserialize)]
struct MigrationResponse {
    id: u64,
    state: String,
}
