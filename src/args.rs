use std::path::PathBuf;

use clap::{Error, Parser};
use std::ffi::OsString;

#[derive(Debug, PartialEq, Parser)]
#[command(author, version, about, long_about = None)]
struct CliArgs {
    /// Configuration file
    #[arg(value_parser)]
    config: PathBuf,

    /// Backup directory
    #[arg(value_parser, required_unless_present = "dest")]
    destination: Option<PathBuf>,

    /// Backup directory (deprecated flag)
    #[arg(long, hide = true)]
    dest: Option<PathBuf>,

    /// Enable verbose logging
    #[arg(short, long)]
    verbose: bool,

    /// Print actions instead of performing them
    #[arg(long)]
    dry_run: bool,

    /// Include repositories you own
    #[arg(long)]
    owned: bool,

    /// Include repositories you have starred
    #[arg(long, alias = "stars")]
    starred: bool,

    /// Include repositories you watch
    #[arg(long)]
    watched: bool,

    /// Run periodically with the given interval in seconds
    #[arg(long)]
    interval: Option<u64>,

    /// Number of worker threads to use when cloning
    #[arg(long, default_value_t = 1)]
    workers: usize,
}

#[derive(Debug, PartialEq, Clone)]
pub struct Args {
    pub config: PathBuf,
    pub destination: PathBuf,
    pub verbose: bool,
    pub dry_run: bool,
    pub owned: bool,
    pub starred: bool,
    pub watched: bool,
    pub interval: Option<u64>,
    pub workers: usize,
}

pub fn parse_args() -> Args {
    match parse_args_from(std::env::args_os()) {
        Ok(a) => a,
        Err(e) => e.exit(),
    }
}

pub fn parse_args_from<I, T>(itr: I) -> Result<Args, Error>
where
    I: IntoIterator<Item = T>,
    T: Into<OsString> + Clone,
{
    let cli = CliArgs::try_parse_from(itr)?;
    let destination = cli.dest.or(cli.destination).unwrap();
    Ok(Args {
        config: cli.config,
        destination,
        verbose: cli.verbose,
        dry_run: cli.dry_run,
        owned: cli.owned,
        starred: cli.starred,
        watched: cli.watched,
        interval: cli.interval,
        workers: cli.workers,
    })
}

#[cfg(test)]
pub fn try_parse_from<I, T>(itr: I) -> Result<Args, Error>
where
    I: IntoIterator<Item = T>,
    T: Into<OsString> + Clone,
{
    parse_args_from(itr)
}

#[cfg(test)]
pub fn parse_from<I, T>(itr: I) -> Args
where
    I: IntoIterator<Item = T>,
    T: Into<OsString> + Clone,
{
    match parse_args_from(itr) {
        Ok(a) => a,
        Err(e) => e.exit(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_from_parses_all_flags() {
        let args = parse_from([
            "gitout",
            "config.toml",
            "dest",
            "-v",
            "--dry-run",
            "--owned",
            "--stars",
            "--watched",
            "--interval",
            "60",
            "--workers",
            "4",
        ]);

        assert_eq!(args.config, PathBuf::from("config.toml"));
        assert_eq!(args.destination, PathBuf::from("dest"));
        assert!(args.verbose);
        assert!(args.dry_run);
        assert!(args.owned);
        assert!(args.starred);
        assert!(args.watched);
        assert_eq!(args.interval, Some(60));
        assert_eq!(args.workers, 4);
    }

    #[test]
    fn parse_from_missing_required_args_errors() {
        let result = try_parse_from(["gitout"]);
        assert!(result.is_err());
    }

    #[test]
    fn parse_from_dest_flag() {
        let args = parse_from(["gitout", "--dest", "out", "config.toml"]);

        assert_eq!(args.destination, PathBuf::from("out"));
    }
}
