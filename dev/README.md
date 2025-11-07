# Local debug configuration

This directory contains everything you need to spin up a disposable GitOut
instance for local testing:

- `config.local.toml` – baseline configuration that clones the upstream
  repository and keeps Telegram disabled until you supply credentials.
- `docker-compose.local.yml` – builds the project from the current workspace
  and runs it once with verbose logging.
- `secrets/` – drop `github_token.txt` and `telegram_bot_token.txt` here.
  The directory is ignored by Git so the files stay on your machine.
- `data/` – receives the bare repositories that are cloned during a test run.

## Quick start

1. Copy your personal access tokens into the secrets directory:
   ```bash
   cp ~/secrets/github_token.txt dev/secrets/github_token.txt
   cp ~/secrets/telegram_bot_token.txt dev/secrets/telegram_bot_token.txt
   ```
2. (Optional) edit `config.local.toml` to set your GitHub username, tweak
   repositories, or enable Telegram notifications.
3. Launch the stack:  
   ```bash
   docker compose -f dev/docker-compose.local.yml up --build
   ```
4. Re-run the command whenever you need to iterate. Because `GITOUT_CRON` is
   empty the container performs a single sync and exits, making debugging
   straightforward.

