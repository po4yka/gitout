use std::path::PathBuf;

use clap::Parser;

#[derive(Debug, PartialEq, Parser)]
#[command(author, version, about, long_about = None)]
pub struct Args {
	/// Configuration file
        #[arg()]
        pub config: PathBuf,

	/// Backup directory
        #[arg()]
        pub destination: PathBuf,

	/// Enable verbose logging
        #[arg(short, long)]
        pub verbose: bool,

	/// Enable experimental repository archiving
        #[arg(long)]
        pub experimental_archive: bool,

	/// Print actions instead of performing them
        #[arg(long)]
        pub dry_run: bool,
}

pub fn parse_args() -> Args {
        Args::parse()
}

#[cfg(test)]
mod tests {
        use super::*;
        use clap::Parser;

	#[test]
        fn parse_from_parses_all_flags() {
                let args = Args::parse_from([
                        "gitout",
                        "config.toml",
                        "dest",
                        "-v",
                        "--experimental-archive",
                        "--dry-run",
                ]);

		assert_eq!(args.config, PathBuf::from("config.toml"));
		assert_eq!(args.destination, PathBuf::from("dest"));
		assert!(args.verbose);
		assert!(args.experimental_archive);
                assert!(args.dry_run);
        }

        #[test]
        fn parse_from_missing_required_args_errors() {
                let result = Args::try_parse_from(["gitout"]); 
                assert!(result.is_err());
        }
}
