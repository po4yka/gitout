# Release Process

## Version Numbering

- Versions use a PEP 440 local label `+fork` (e.g., `0.4.0+fork`)
- Development versions use `+fork.dev` (e.g., `0.4.0+fork.dev`)
- (Local-version labels are not PyPI-publishable, which is fine — distribution is via Docker images and GitHub release artifacts.)

## Release Steps

1. **Update version** in `pyproject.toml` (and `gitout/__init__.py` `__version__`), dropping `.dev`:
   ```toml
   version = "0.4.0+fork"
   ```

2. **Update CHANGELOG.md**: move unreleased items under a versioned header with date.

3. **Build and test**:
   ```bash
   ruff check gitout tests && mypy gitout && python -m pytest -q
   python -m build           # produces dist/*.whl and dist/*.tar.gz
   python -m gitout --version
   ```

4. **Commit and tag**:
   ```bash
   git commit -am "Prepare release 0.4.0-fork"
   git tag v0.4.0-fork
   ```

5. **Bump to next dev version** in `pyproject.toml` / `gitout/__init__.py`:
   ```toml
   version = "0.5.0+fork.dev"
   ```
   ```bash
   git commit -am "Prepare next development version"
   ```

6. **Push**:
   ```bash
   git push && git push --tags
   ```

GitHub Actions (`publish.yaml`) automatically: runs tests, builds the sdist/wheel, publishes multi-arch Docker images (Docker Hub + GHCR), and creates a GitHub release with the distribution artifacts.

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
