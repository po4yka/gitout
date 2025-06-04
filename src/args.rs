use std::path::PathBuf;

use structopt::StructOpt;

#[derive(Debug, PartialEq, StructOpt)]
pub struct Args {
	/// Configuration file
	#[structopt(parse(from_os_str))]
	pub config: PathBuf,

	/// Backup directory
	#[structopt(parse(from_os_str))]
	pub destination: PathBuf,

	/// Enable verbose logging
	#[structopt(short, long)]
	pub verbose: bool,

	/// Enable experimental repository archiving
	#[structopt(long)]
	pub experimental_archive: bool,

	/// Print actions instead of performing them
	#[structopt(long)]
	pub dry_run: bool,
}

pub fn parse_args() -> Args {
        Args::from_args()
}

#[cfg(test)]
mod tests {
        use super::*;
        use structopt::StructOpt;

        #[test]
        fn from_iter_parses_all_flags() {
                let args = Args::from_iter(&[
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
