# CI/CD Setup and Configuration

This document describes the complete CI/CD setup for the gitout project after the Kotlin migration.

## Overview

The project uses GitHub Actions for continuous integration, automated testing, security scanning, and deployment. All workflows are designed to support the Kotlin/Gradle stack and new features including parallel synchronization, metrics, and performance optimizations.

## Workflows

### 1. Build & Test Workflow (`build.yaml`)

**Purpose**: Comprehensive testing and validation for pull requests and commits

**Triggers**:
- Pull requests to any branch
- Pushes to master branch
- Manual dispatch via workflow_dispatch

**Jobs**:

#### Test Suite
- Runs all test suites using `./gradlew test --continue`
- Tests included:
  - ConfigTest: Configuration parsing and validation (7 tests)
  - EngineTest: Core engine operations (7 tests)
  - RetryPolicyTest: Retry mechanism and backoff (5 tests)
  - ParallelSyncTest: Parallel synchronization with coroutines (8 tests)
  - IntegrationTest: End-to-end scenarios (14+ tests)
- Publishes JUnit test reports with detailed results
- Uploads test artifacts for debugging failures
- Uses mikepenz/action-junit-report for test result visualization

#### Build Distribution
- Builds the project with `./gradlew build -x test`
- Creates distribution packages: `./gradlew distZip distTar`
- Uploads both ZIP and TAR artifacts
- Runs only after tests pass
- Uploads build reports on failure

#### Docker Build Test
- Validates Docker image builds using buildx
- Tests for both amd64 and arm64 architectures
- Runs `--help` and `--version` commands to verify functionality
- Uses GitHub Actions cache for Docker layers
- Runs only after tests pass

#### Code Quality
- Runs `./gradlew check -x test` for code formatting and quality
- Warnings allowed (non-blocking)
- Can be extended with ktlint, detekt, or other tools

**Features**:
- Gradle dependency caching (read-only for non-master branches)
- Parallel job execution where possible
- Automatic artifact retention for 90 days
- Final status check ensures all jobs pass

**Performance**:
- Typical runtime: 5-8 minutes (with cache)
- First run: 10-15 minutes (without cache)
- Tests run in parallel where possible

### 2. Publish Workflow (`publish.yaml`)

**Purpose**: Automated publishing of Docker images and GitHub releases

**Triggers**:
- Pushes to master branch (publishes latest)
- Version tags matching `v*.*.*` pattern (e.g., v0.4.0)
- Manual dispatch via workflow_dispatch

**Jobs**:

#### Build Distributions
- Runs all tests first to ensure quality
- Builds ZIP and TAR distribution packages
- Uploads artifacts for use in release creation
- Creates separate artifacts for easier download

#### Publish Docker
- Builds multi-architecture Docker images (amd64, arm64)
- Publishes to two registries:
  - Docker Hub: `po4yka/gitout`
  - GitHub Container Registry: `ghcr.io/po4yka/gitout`
- Uses docker/metadata-action for intelligent tagging:
  - `latest` for master branch
  - `v0.4.0`, `0.4`, `0` for version tags
  - `master-abc1234` for commit SHAs
- Requires secrets:
  - `DOCKER_HUB_TOKEN` for Docker Hub
  - `GITHUB_TOKEN` (automatic) for GHCR
- Uses GitHub Actions cache for faster builds
- Sets OCI image labels for metadata

#### Create Release
- Only runs for version tags (v*.*.*)
- Downloads distribution artifacts
- Extracts release notes from CHANGELOG.md
- Creates GitHub release with:
  - Release notes from CHANGELOG
  - Docker pull commands
  - Installation instructions
  - ZIP and TAR distribution files
  - Auto-generated commit notes
- Release is published immediately (not draft)

**Docker Image Tags**:
```
# For v0.4.0 tag:
po4yka/gitout:latest
po4yka/gitout:v0.4.0
po4yka/gitout:0.4
po4yka/gitout:0
ghcr.io/po4yka/gitout:latest
ghcr.io/po4yka/gitout:v0.4.0
ghcr.io/po4yka/gitout:0.4
ghcr.io/po4yka/gitout:0

# For master branch commit abc1234:
po4yka/gitout:master
po4yka/gitout:master-abc1234
ghcr.io/po4yka/gitout:master
ghcr.io/po4yka/gitout:master-abc1234
```

**Performance**:
- Build distributions: 5-8 minutes
- Multi-arch Docker build: 10-15 minutes
- Total runtime: 15-25 minutes

### 3. CodeQL Security Workflow (`codeql.yaml`)

**Purpose**: Automated security scanning and vulnerability detection

**Triggers**:
- Pushes to master branch
- Pull requests to master branch
- Weekly schedule (Mondays at 00:00 UTC)
- Manual dispatch via workflow_dispatch

**Features**:
- Analyzes Java/Kotlin code for security vulnerabilities
- Uses security-extended and security-and-quality query suites
- Integrates with GitHub Security tab
- Results available in pull request checks
- Weekly scans catch new vulnerabilities

**Query Suites**:
- `security-extended`: Extended security patterns
- `security-and-quality`: Security and code quality rules

**Performance**:
- Typical runtime: 5-10 minutes
- Scheduled runs don't block development

## Dependency Management

### Renovate (`renovate.json5`)

**Configuration**:
- Extends `config:recommended` preset
- Groups related updates together
- Auto-merges minor and patch updates
- Weekly schedule (Mondays at 3 AM)

**Package Groups**:
1. **Kotlin**: All kotlin packages (compiler, stdlib, coroutines)
2. **GitHub Actions**: All action version updates
3. **Test Dependencies**: junit, assertk, mockwebserver

**Features**:
- Concurrent PR limit: 10
- Auto-merge with squash strategy for minor/patch updates
- Automatic labeling with `dependencies`
- Gradle support enabled

### Dependabot (`dependabot.yml`)

**Configuration**:
- Monitors Gradle, GitHub Actions, and Docker
- Weekly schedule (Mondays at 3 AM)
- Groups updates by type

**Ecosystems Monitored**:
1. **Gradle**: All Gradle dependencies in root directory
2. **GitHub Actions**: Action version updates
3. **Docker**: Base image updates (alpine:3.22.1)

**Features**:
- Open PR limits: Gradle (10), Actions (5), Docker (2)
- Automatic labeling by ecosystem
- Groups minor/patch updates within categories

**Why Both?**

Renovate and Dependabot complement each other:
- Renovate: More flexible, auto-merge capabilities
- Dependabot: Native GitHub integration, simpler configuration
- If one fails or is disabled, the other provides coverage

## Secrets Configuration

### Required Secrets

Set these in GitHub repository settings (Settings → Secrets and variables → Actions):

1. **DOCKER_HUB_TOKEN**
   - Type: Repository secret
   - Purpose: Authenticate to Docker Hub for image publishing
   - How to create:
     1. Log in to hub.docker.com
     2. Account Settings → Security → New Access Token
     3. Set description: "GitHub Actions - gitout"
     4. Copy token and add to GitHub secrets

### Automatic Secrets

These are provided by GitHub Actions automatically:

1. **GITHUB_TOKEN**
   - Purpose: Authenticate to GHCR, create releases, write to Security tab
   - Permissions: Configured per workflow
   - No setup required

## Branch Protection Rules

### Recommended Settings for `master` Branch

Go to Settings → Branches → Add branch protection rule:

**Required**:
- ✓ Require a pull request before merging
- ✓ Require status checks to pass before merging
  - Required checks:
    - `Test Suite`
    - `Build Distribution`
    - `Docker Build Test`
    - `Code Quality`
- ✓ Require conversation resolution before merging

**Recommended**:
- ✓ Require linear history (keeps clean git history)
- ✓ Include administrators (enforce rules for all)
- ✓ Allow force pushes: Off
- ✓ Allow deletions: Off

**Optional**:
- Require approvals: 1-2 reviewers
- Dismiss stale pull request approvals when new commits are pushed
- Require review from Code Owners

## Release Process

### Creating a New Release

1. **Update Version**
   ```bash
   # Edit build.gradle
   version = '0.4.0-fork'  # Remove -SNAPSHOT
   ```

2. **Update Changelog**
   ```bash
   # Edit CHANGELOG.md
   # Move "Unreleased" content to new version section
   ## [0.4.0-fork] - 2025-11-04
   ```

3. **Commit Changes**
   ```bash
   git add build.gradle CHANGELOG.md
   git commit -m "Prepare release 0.4.0-fork"
   git push origin master
   ```

4. **Create and Push Tag**
   ```bash
   git tag v0.4.0-fork
   git push origin v0.4.0-fork
   ```

5. **Monitor Workflow**
   - Go to Actions tab
   - Watch "Publish" workflow
   - Verify all jobs complete successfully

6. **Verify Release**
   - Check Releases page for new release
   - Verify Docker images are published:
     ```bash
     docker pull po4yka/gitout:v0.4.0-fork
     docker pull ghcr.io/po4yka/gitout:v0.4.0-fork
     ```
   - Download and test distribution artifacts

7. **Prepare Next Version**
   ```bash
   # Edit build.gradle
   version = '0.5.0-fork-SNAPSHOT'

   git add build.gradle
   git commit -m "Prepare next development snapshot"
   git push origin master
   ```

### What Happens Automatically

When you push a version tag:

1. **Tests Run**: All test suites execute
2. **Distributions Built**: ZIP and TAR packages created
3. **Docker Images Published**:
   - Built for amd64 and arm64
   - Pushed to Docker Hub and GHCR
   - Tagged with version, latest, and commit SHA
4. **GitHub Release Created**:
   - Release notes extracted from CHANGELOG.md
   - Distribution files attached
   - Docker pull commands included
   - Installation instructions added

### Hotfix Releases

For urgent fixes:

1. Create hotfix branch from tag:
   ```bash
   git checkout -b hotfix/0.4.1 v0.4.0-fork
   ```

2. Make fixes and commit:
   ```bash
   git add .
   git commit -m "Fix critical bug in SSL handling"
   ```

3. Update version and changelog:
   ```bash
   # build.gradle: version = '0.4.1-fork'
   # CHANGELOG.md: Add [0.4.1-fork] section
   git add build.gradle CHANGELOG.md
   git commit -m "Prepare hotfix release 0.4.1-fork"
   ```

4. Merge to master and tag:
   ```bash
   git checkout master
   git merge hotfix/0.4.1
   git push origin master
   git tag v0.4.1-fork
   git push origin v0.4.1-fork
   ```

## Monitoring and Maintenance

### Monitoring Build Status

**GitHub Actions Tab**:
- View all workflow runs: https://github.com/po4yka/gitout/actions
- Filter by workflow, branch, or status
- Download artifacts from completed runs

**README Badges**:
- Build & Test: Shows current master branch status
- Publish: Shows last publish status
- CodeQL: Shows security scan status

**Email Notifications**:
- Configure in GitHub account settings
- Notifications → Actions
- Get emails for workflow failures

### Troubleshooting Workflows

**Test Failures**:
1. Click on failed workflow run
2. Expand failed job
3. View logs for specific step
4. Download test-results artifact for detailed reports

**Build Failures**:
1. Check build-reports artifact
2. Review Gradle output in logs
3. Verify Java version is correct (24)
4. Check for dependency conflicts

**Docker Build Failures**:
1. Check if Dockerfile syntax is correct
2. Verify base images are accessible
3. Check if QEMU setup succeeded for arm64
4. Review build context size

**Publishing Failures**:
1. Verify secrets are configured correctly
2. Check Docker Hub token is valid
3. Verify GITHUB_TOKEN has required permissions
4. Check registry connectivity

### Maintenance Tasks

**Weekly**:
- Review and merge Renovate/Dependabot PRs
- Check CodeQL security findings
- Monitor workflow run times

**Monthly**:
- Review workflow efficiency and optimize
- Update GitHub Actions versions
- Check artifact storage usage

**Quarterly**:
- Review and update branch protection rules
- Audit secrets and rotate tokens
- Review and optimize Docker image sizes

## Workflow Optimization

### Caching Strategy

**Gradle Caching**:
```yaml
- uses: gradle/actions/setup-gradle@v4
  with:
    cache-read-only: ${{ github.ref != 'refs/heads/master' }}
```
- Master branch: Write to cache
- Other branches: Read-only (faster, avoids conflicts)

**Docker Caching**:
```yaml
cache-from: type=gha
cache-to: type=gha,mode=max
```
- Uses GitHub Actions cache
- `mode=max`: Caches all layers
- Significantly speeds up rebuilds

### Parallel Execution

**Test Suite**:
- Tests run with `--continue` flag
- Multiple test classes run in parallel
- Gradle worker threads used efficiently

**Docker Builds**:
- Multi-arch builds run in parallel
- QEMU setup enables arm64 emulation
- Buildx manages parallel platform builds

### Artifact Management

**Retention**:
- Test results: 90 days (default)
- Distributions: 90 days (default)
- Build reports: 30 days (failures only)

**Size Optimization**:
- Upload only necessary files
- Use .dockerignore to reduce context
- Compress artifacts when possible

## Security Considerations

### Secret Management

**Best Practices**:
- Rotate tokens regularly (every 90 days)
- Use fine-grained tokens when possible
- Never commit secrets to repository
- Use environment-specific secrets

**Token Scopes**:
- Docker Hub: Read, Write, Delete for po4yka/gitout
- GitHub: Read packages, Write packages, Write releases

### Docker Image Security

**Practices**:
- Use official base images (Alpine)
- Pin base image versions (alpine:3.22.1)
- Regular updates via Dependabot
- Scan images with CodeQL and Docker Scout

**Runtime Security**:
- Run as non-root user (PUID/PGID support)
- Minimal attack surface (only necessary packages)
- Regular security updates

### Code Security

**Scanning**:
- CodeQL runs on every PR and push
- Weekly scheduled scans
- Security findings in GitHub Security tab

**Dependencies**:
- Automated updates via Renovate/Dependabot
- Security advisories monitored
- Dependency vulnerability scanning

## Integration with External Services

### Docker Hub

**Setup**:
1. Create account at hub.docker.com
2. Create repository: po4yka/gitout
3. Generate access token
4. Add token to GitHub secrets

**Monitoring**:
- View pulls and stars at hub.docker.com
- Check image sizes and tags
- Review automated build status

### GitHub Container Registry

**Setup**:
- Automatically enabled for repository
- Uses GITHUB_TOKEN for authentication
- Public access configured automatically

**Access**:
```bash
# Public pull (no auth)
docker pull ghcr.io/po4yka/gitout:latest

# With authentication (for private images)
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
docker pull ghcr.io/po4yka/gitout:latest
```

### Healthchecks.io

**Optional Integration**:
- Set GITOUT_HC_ID environment variable
- Tool pings on successful sync
- Monitor via healthchecks.io dashboard

## Future Enhancements

### Planned Improvements

1. **Performance Testing Workflow**
   - Benchmark parallel vs sequential sync
   - Track performance regressions
   - Generate performance reports

2. **Documentation Deployment**
   - Auto-generate API docs from KDoc
   - Deploy to GitHub Pages
   - Version documentation per release

3. **Integration Testing**
   - Test against real GitHub API (with rate limits)
   - Test Docker container in various environments
   - Validate all environment variables

4. **Release Automation**
   - Automatic version bumping
   - Changelog generation from commits
   - Release notes from PRs

5. **Quality Gates**
   - Code coverage reporting
   - Complexity analysis
   - Performance benchmarks

### Experimental Features

1. **Matrix Testing**
   - Test on multiple Java versions (8, 11, 17, 21)
   - Test on multiple OS (Ubuntu, macOS, Windows)
   - Test with different Gradle versions

2. **Canary Releases**
   - Deploy to test environment first
   - Gradual rollout to production
   - Automatic rollback on failures

3. **Performance Monitoring**
   - Track workflow run times
   - Alert on significant slowdowns
   - Optimize slow steps

## Conclusion

This CI/CD setup provides:
- Automated testing for all changes
- Multi-architecture Docker image publishing
- Automated GitHub releases
- Security scanning and vulnerability detection
- Dependency management automation
- Comprehensive monitoring and alerting

The workflows are designed to be:
- **Fast**: Parallel execution and caching
- **Reliable**: Multiple validation steps
- **Secure**: Secret management and scanning
- **Maintainable**: Clear structure and documentation
- **Scalable**: Can handle increased complexity

For questions or issues, refer to:
- GitHub Actions documentation
- Workflow files in `.github/workflows/`
- This document
- Project README.md
