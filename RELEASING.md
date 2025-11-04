# Release Process

This document describes the release process for the Kotlin-based gitout.

## Prerequisites

- JDK 8 or later installed for building
- Write access to the repository
- Docker Hub credentials configured (for Docker image push)
- GitHub Actions configured with secrets for automated releases

## Version Numbering

- **Fork versions**: Use `-fork` suffix (e.g., `0.4.0-fork`)
- **Snapshots**: Use `-SNAPSHOT` suffix during development (e.g., `0.4.0-fork-SNAPSHOT`)
- **Releases**: Remove `-SNAPSHOT` for releases (e.g., `0.4.0-fork`)

## Release Steps

### 1. Update Version

Update the `version` in `build.gradle` to the release version (remove `-SNAPSHOT`):

```gradle
version = '0.4.0-fork'
```

### 2. Update CHANGELOG.md

1. Change the `[Unreleased]` or `[X.Y.Z-SNAPSHOT]` header to the release version with date:
   ```markdown
   ## [0.4.0-fork] - 2025-11-04
   ```

2. Add a link URL at the bottom of the file:
   ```markdown
   [0.4.0-fork]: https://github.com/po4yka/gitout/releases/tag/0.4.0-fork
   ```

3. Add a new `Unreleased` section to the top:
   ```markdown
   ## [Unreleased]
   [Unreleased]: https://github.com/po4yka/gitout/compare/0.4.0-fork...HEAD
   ```

4. Review all changes are documented accurately

### 3. Build and Test

Build the project and run all tests to ensure everything works:

```bash
# Clean build
./gradlew clean build

# Run all tests
./gradlew test

# Create distribution
./gradlew installDist

# Test the binary
./build/install/gitout/bin/gitout --version
```

Expected output: `gitout 0.4.0-fork`

### 4. Commit Release

```bash
git commit -am "Prepare version 0.4.0-fork"
```

### 5. Create Git Tag

```bash
git tag -am "Version 0.4.0-fork" 0.4.0-fork
```

### 6. Update to Next Snapshot Version

Update the `version` in `build.gradle` to the next snapshot version:

```gradle
version = '0.5.0-fork-SNAPSHOT'
```

### 7. Update CHANGELOG for Next Version

Add a section for the next version in `CHANGELOG.md`:

```markdown
## [Unreleased]
[Unreleased]: https://github.com/po4yka/gitout/compare/0.4.0-fork...HEAD

## [0.5.0-fork-SNAPSHOT] - In Development

(No changes yet)
```

### 8. Commit Snapshot Version

```bash
git commit -am "Prepare next development snapshot"
```

### 9. Push to GitHub

Push both the commits and the tag:

```bash
git push && git push --tags
```

This will trigger GitHub Action workflows that:
- Create a GitHub release with the version tag
- Build and attach the binary distribution zip
- Build and push the Docker image to Docker Hub
- Build and push to GitHub Container Registry (ghcr.io)

### 10. Verify Release

1. Check GitHub releases page: https://github.com/po4yka/gitout/releases
2. Verify the binary zip is attached
3. Check Docker Hub: https://hub.docker.com/r/po4yka/gitout/tags
4. Verify the Docker image is available:
   ```bash
   docker pull po4yka/gitout:0.4.0-fork
   docker pull po4yka/gitout:latest
   ```

## Manual Release (If Automation Fails)

If GitHub Actions fail, you can create a manual release:

### Build Distribution Zip

```bash
./gradlew distZip
```

The zip will be at: `build/distributions/gitout-0.4.0-fork.zip`

### Create GitHub Release

1. Go to: https://github.com/po4yka/gitout/releases/new
2. Choose the tag you created
3. Set release title: "0.4.0-fork"
4. Copy relevant sections from CHANGELOG.md to the release description
5. Upload the distribution zip
6. Mark as pre-release if it's a beta/RC
7. Publish the release

### Build and Push Docker Image

```bash
# Build the image
docker build -t po4yka/gitout:0.4.0-fork .
docker tag po4yka/gitout:0.4.0-fork po4yka/gitout:latest

# Login to Docker Hub
docker login

# Push images
docker push po4yka/gitout:0.4.0-fork
docker push po4yka/gitout:latest
```

## Hotfix Releases

For urgent fixes on a released version:

1. Create a branch from the release tag:
   ```bash
   git checkout -b hotfix-0.4.1-fork 0.4.0-fork
   ```

2. Make the necessary fixes and commit

3. Update version to hotfix version (e.g., `0.4.1-fork`)

4. Update CHANGELOG.md with hotfix details

5. Follow normal release process from step 3

## Fork-Specific Notes

- Always use `-fork` suffix to distinguish from upstream releases
- Keep upstream changes synchronized when possible
- Document all fork-specific enhancements in CHANGELOG.md
- Maintain compatibility with upstream configuration format
- Test Docker image thoroughly before releasing

## Rollback Process

If a release has critical issues:

1. Delete the GitHub release (but keep the tag)
2. Remove the Docker image tags from Docker Hub
3. Push a hotfix release following the hotfix process above
4. Update README.md and CHANGELOG.md to document the issue

## Communication

After releasing:

1. Update README.md if installation instructions changed
2. Announce the release on relevant channels
3. Update Docker Hub description if needed
4. Consider creating a blog post for major releases
