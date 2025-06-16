use serde::Deserialize;
use toml::de::Error;
use toml::value::Table;

#[derive(Debug, Deserialize, PartialEq)]
pub struct Config {
    pub version: u32,
    pub github: Option<GitHub>,
    pub git: Option<Git>,
    #[serde(default)]
    pub ssl: SslConfig,
}

#[derive(Debug, Deserialize, PartialEq)]
pub struct GitHub {
    pub user: String,
    pub token: String,
    #[serde(default)]
    pub clone: GitHubClone,
}

#[derive(Debug, Deserialize, PartialEq)]
pub struct GitHubClone {
    #[serde(default)]
    pub starred: bool,
    #[serde(default)]
    pub watched: bool,
    #[serde(default)]
    pub gists: bool,
    #[serde(default)]
    pub repos: Vec<String>,
    #[serde(default)]
    pub ignored: Vec<String>,
}

impl Default for GitHubClone {
    fn default() -> Self {
        GitHubClone {
            starred: false,
            watched: false,
            gists: true,
            repos: vec![],
            ignored: vec![],
        }
    }
}

#[derive(Debug, Deserialize, PartialEq)]
pub struct Git {
    #[serde(default)]
    pub repos: Table,
}

#[derive(Debug, Deserialize, PartialEq)]
pub struct SslConfig {
    #[serde(default)]
    pub verify_certificates: bool,
    #[serde(default)]
    pub cert_file: Option<String>,
}

impl Default for SslConfig {
    fn default() -> Self {
        SslConfig {
            verify_certificates: true,
            cert_file: None,
        }
    }
}

pub fn parse_config(s: &str) -> Result<Config, Error> {
    toml::from_str(s)
}

#[cfg(test)]
mod test {
    use super::*;
    use toml::value::Value;

    #[test]
    fn empty() {
        let actual = parse_config(
            r#"
			version = 0
			"#,
        )
        .unwrap();
        let expected = Config {
            version: 0,
            github: None,
            git: None,
            ssl: SslConfig {
                verify_certificates: true,
                cert_file: None,
            },
        };
        assert_eq!(actual, expected)
    }

    #[test]
    fn full() {
        let actual = parse_config(
            r#"
			version = 0

			[github]
			user = "user"
			token = "token"

			[github.clone]
			starred = true
			watched = true
			gists = false
			repos = [
				"example/two",
			]

			[git.repos]
			example = "https://example.com/example.git"
			"#,
        )
        .unwrap();
        let mut repos = Table::new();
        repos.insert(
            "example".to_string(),
            Value::from("https://example.com/example.git"),
        );
        let expected = Config {
            version: 0,
            github: Some(GitHub {
                user: "user".to_string(),
                token: "token".to_string(),
                clone: GitHubClone {
                    starred: true,
                    watched: true,
                    gists: false,
                    repos: vec!["example/two".to_string()],
                    ignored: vec![],
                },
            }),
            git: Some(Git { repos }),
            ssl: SslConfig {
                verify_certificates: true,
                cert_file: None,
            },
        };
        assert_eq!(actual, expected)
    }
}
