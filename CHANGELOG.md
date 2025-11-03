# Change Log

## [Unreleased]
[Unreleased]: https://github.com/po4yka/gitout/compare/0.3.0...HEAD

### Fork Enhancements (po4yka/gitout)

New:
- SSL/TLS certificate handling improvements with automatic detection of common certificate locations
- Configurable SSL certificate path via `[ssl]` config section
- Support for disabling SSL certificate verification (for testing)
- Automatic retry mechanism with exponential backoff (up to 6 attempts)
- Better error handling and logging for network failures
- User/Group ID mapping support via `PUID` and `PGID` environment variables for Docker
- Enhanced Docker entrypoint script for proper permission handling

Changed:
- Updated Dockerfile to use Alpine Linux with improved SSL support
- Added ca-certificates and proper certificate configuration
- Improved git operation reliability with staggered retry delays


## [0.3.0] - 2025-09-08
[0.3.0]: https://github.com/JakeWharton/gitout/releases/tag/0.3.0

New:
- Rewrite the app in Kotlin (from Rust). Sorry (not sorry).
- The binary distribution (which now requires a JVM) supports scheduled sync with the `--cron` option.
- Add `--timeout` option / `GITOUT_TIMEOUT` env var to control limit on git operations. Default is 10 minutes.

Changed:
- The `CRON`, `HEALTHCHECK_ID`, and `HEALTHCHECK_HOST` Docker container environment variables are now named `GITOUT_CRON`, `GITOUT_HC_ID`, and `GITOUT_HC_HOST`, respectively. These will also be honored by the standalone binary.


## [0.2.0] - 2020-05-23
[0.2.0]: https://github.com/JakeWharton/gitout/releases/tag/0.2.0

 * New: Output will now only print repositories which have updates. A counter is included to display overall progress.
 * Fix: Do not ping healthcheck if command fails in Docker container.


## [0.1.0]
[0.1.0]: https://github.com/JakeWharton/gitout/releases/tag/0.1.0

Initial release.
