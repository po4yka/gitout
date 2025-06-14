use std::path::PathBuf;

use clap::Parser;

#[derive(Debug, PartialEq, Parser)]
pub struct Args {
        /// Configuration file
        pub config: PathBuf,

	/// Backup directory
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
	fn from_iter_parses_all_flags() {
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
}
