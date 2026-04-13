# Release Process

## Version Numbering

- Fork versions use `-fork` suffix (e.g., `0.4.0-fork`)
- Development snapshots use `-SNAPSHOT` suffix (e.g., `0.4.0-fork-SNAPSHOT`)

## Release Steps

1. **Update version** in `build.gradle` (remove `-SNAPSHOT`):
   ```gradle
   version = '0.4.0-fork'
   ```

2. **Update CHANGELOG.md**: move unreleased items under versioned header with date

3. **Build and test**:
   ```bash
   ./gradlew clean build
   ./build/install/gitout/bin/gitout --version
   ```

4. **Commit and tag**:
   ```bash
   git commit -am "Prepare release 0.4.0-fork"
   git tag v0.4.0-fork
   ```

5. **Bump to next snapshot** in `build.gradle`:
   ```gradle
   version = '0.5.0-fork-SNAPSHOT'
   ```
   ```bash
   git commit -am "Prepare next development snapshot"
   ```

6. **Push**:
   ```bash
   git push && git push --tags
   ```

GitHub Actions automatically: runs tests, builds distributions, publishes multi-arch Docker images (Docker Hub + GHCR), and creates a GitHub release with artifacts.

## Verify

- [Releases](https://github.com/po4yka/gitout/releases)
- [Docker Hub tags](https://hub.docker.com/r/po4yka/gitout/tags)

## Hotfix

```bash
git checkout -b hotfix/0.4.1 v0.4.0-fork
# fix, bump version to 0.4.1-fork, update changelog
git checkout master && git merge hotfix/0.4.1
git tag v0.4.1-fork && git push origin master v0.4.1-fork
```

## Notes

- Always use `-fork` suffix to distinguish from upstream releases
- Keep upstream changes synchronized when possible
