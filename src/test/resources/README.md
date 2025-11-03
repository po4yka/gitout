# Test Resources

This directory contains test fixtures and resources for the gitout test suite.

## Directory Structure

```
test/resources/
├── fixtures/              # Test configuration files
│   ├── minimal-config.toml      # Minimal valid configuration
│   ├── full-config.toml         # Full configuration with all options
│   ├── ssl-disabled-config.toml # Configuration with SSL disabled
│   └── parallel-config.toml     # Configuration for parallel sync tests
└── README.md             # This file
```

## Test Fixtures

### minimal-config.toml
The simplest valid configuration file. Used for testing basic parsing and defaults.

### full-config.toml
A comprehensive configuration file that includes:
- GitHub integration with user and token
- Repository filtering (starred, watched, gists)
- Explicit repository list with ignore patterns
- Custom git repositories
- SSL certificate configuration
- Parallel worker configuration

### ssl-disabled-config.toml
Configuration for testing SSL certificate verification disabled mode.
**Warning**: This is for testing only and should never be used in production.

### parallel-config.toml
Configuration for testing parallel synchronization with multiple repositories
and worker pool management.

## Usage in Tests

These fixtures can be loaded in tests like this:

```kotlin
val configPath = Paths.get("src/test/resources/fixtures/minimal-config.toml")
val config = Config.parse(configPath.readText())
```

Or for integration tests:

```kotlin
val fixtureContent = this::class.java.classLoader
    .getResource("fixtures/full-config.toml")!!
    .readText()
```

## Adding New Fixtures

When adding new test fixtures:
1. Create the file in the `fixtures/` directory
2. Use `.toml` extension for configuration files
3. Document the purpose in this README
4. Add corresponding tests that use the fixture
5. Keep fixtures minimal and focused on specific test scenarios
