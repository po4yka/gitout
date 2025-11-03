# Gitout Metrics and Monitoring

This document describes the comprehensive metrics and monitoring system for gitout, designed to provide production observability for sync operations.

## Overview

Gitout now includes a built-in metrics collection system that tracks:
- Sync operation counts (attempts, successes, failures)
- Retry behavior and delays
- Active worker utilization
- Timing and performance statistics
- Per-repository metadata and outcomes

## Configuration

### Config File (config.toml)

Add a `[metrics]` section to your configuration:

```toml
[metrics]
# Enable or disable metrics collection (default: true)
enabled = true

# Output format: "console", "json", or "prometheus" (default: "console")
format = "console"

# Optional: Export metrics to a file
export_path = "/var/log/gitout/metrics.txt"
```

### Command Line Options

Override config file settings with CLI flags:

```bash
# Disable metrics
gitout --no-metrics config.toml /backup/dir

# Change format
gitout --metrics-format=json config.toml /backup/dir

# Export to file
gitout --metrics-path=/tmp/gitout-metrics.json config.toml /backup/dir
```

### Environment Variables

```bash
# Disable metrics
GITOUT_METRICS=false gitout config.toml /backup/dir

# Set format
GITOUT_METRICS_FORMAT=prometheus gitout config.toml /backup/dir

# Set export path
GITOUT_METRICS_PATH=/metrics/gitout.prom gitout config.toml /backup/dir
```

## Metrics Collected

### Counters

| Metric | Description |
|--------|-------------|
| `syncs_attempted` | Total number of sync operations attempted |
| `syncs_succeeded` | Total number of successful syncs |
| `syncs_failed` | Total number of failed syncs |
| `retry_attempts` | Total number of retry attempts across all operations |

### Gauges

| Metric | Description |
|--------|-------------|
| `active_workers` | Current number of active sync workers |
| `total_repositories` | Total number of repositories to sync |

### Histograms/Timers

| Metric | Description |
|--------|-------------|
| `overall_duration_ms` | Total duration of the entire sync operation |
| `average_sync_duration_ms` | Average time to sync a repository |
| `min_sync_duration_ms` | Minimum sync time observed |
| `max_sync_duration_ms` | Maximum sync time observed |
| `p50_sync_duration_ms` | Median (50th percentile) sync time |
| `p95_sync_duration_ms` | 95th percentile sync time |
| `p99_sync_duration_ms` | 99th percentile sync time |
| `average_retry_delay_ms` | Average delay between retry attempts |

### Labels/Tags

Per-repository metrics include:
- `repository`: Repository name/path
- `sync_type`: Either `CLONE` (new repository) or `UPDATE` (existing repository)
- `attempts`: Number of attempts for this repository
- `success`: Boolean success status
- `duration_ms`: Time taken for this sync
- `error`: Error message if failed

## Output Formats

### Console Format (Human-Readable)

Default format that prints a detailed summary:

```
============================================================
Sync Summary
============================================================

OVERALL STATISTICS
------------------------------------------------------------
Total repositories:    150
Succeeded:             145
Failed:                5
Total duration:        8m 23s
Retry attempts:        12

PERFORMANCE STATISTICS
------------------------------------------------------------
Average sync time:     3.2s
Min sync time:         0.5s
Max sync time:         45.2s
Median (p50):          2.1s
p95:                   12.3s
p99:                   32.1s
Avg retry delay:       7.5s

FAILED REPOSITORIES
------------------------------------------------------------
owner/large-repo
  Type: CLONE, Attempts: 6
  Error: timeout 10m

Top 5 SLOWEST SYNCS
------------------------------------------------------------
45.2s        owner/massive-repo
38.1s        owner/huge-monorepo
22.5s        owner/big-project
18.9s        owner/large-dataset
15.3s        owner/media-files

============================================================
```

### JSON Format

Structured output for log aggregation and programmatic parsing:

```json
{
  "counters": {
    "syncsAttempted": 150,
    "syncsSucceeded": 145,
    "syncsFailed": 5,
    "retryAttempts": 12
  },
  "gauges": {
    "activeWorkers": 0,
    "totalRepositories": 150
  },
  "timings": {
    "overallDurationMs": 503000,
    "averageSyncDurationMs": 3200.0,
    "minSyncDurationMs": 500,
    "maxSyncDurationMs": 45200,
    "p50SyncDurationMs": 2100,
    "p95SyncDurationMs": 12300,
    "p99SyncDurationMs": 32100,
    "averageRetryDelayMs": 7500.0
  },
  "repositories": [
    {
      "name": "owner/repo",
      "syncType": "UPDATE",
      "attempts": 1,
      "success": true,
      "durationMs": 2100,
      "error": null
    },
    ...
  ]
}
```

### Prometheus Format

Text exposition format compatible with Prometheus:

```prometheus
# HELP gitout_syncs_attempted_total Total number of sync operations attempted
# TYPE gitout_syncs_attempted_total counter
gitout_syncs_attempted_total 150

# HELP gitout_syncs_succeeded_total Total number of sync operations that succeeded
# TYPE gitout_syncs_succeeded_total counter
gitout_syncs_succeeded_total 145

# HELP gitout_syncs_failed_total Total number of sync operations that failed
# TYPE gitout_syncs_failed_total counter
gitout_syncs_failed_total 5

# HELP gitout_retry_attempts_total Total number of retry attempts
# TYPE gitout_retry_attempts_total counter
gitout_retry_attempts_total 12

# HELP gitout_sync_duration_seconds Histogram of sync durations per repository
# TYPE gitout_sync_duration_seconds histogram
gitout_sync_duration_seconds_bucket{le="0.5"} 15
gitout_sync_duration_seconds_bucket{le="1.0"} 42
gitout_sync_duration_seconds_bucket{le="2.5"} 98
gitout_sync_duration_seconds_bucket{le="5.0"} 125
gitout_sync_duration_seconds_bucket{le="10.0"} 138
gitout_sync_duration_seconds_bucket{le="30.0"} 143
gitout_sync_duration_seconds_bucket{le="+Inf"} 145
gitout_sync_duration_seconds_sum 464.5
gitout_sync_duration_seconds_count 145

# HELP gitout_repository_sync_duration_seconds Sync duration per repository
# TYPE gitout_repository_sync_duration_seconds gauge
gitout_repository_sync_duration_seconds{repository="owner/repo",sync_type="UPDATE"} 2.1

# HELP gitout_repository_success Repository sync success status (1=success, 0=failure)
# TYPE gitout_repository_success gauge
gitout_repository_success{repository="owner/repo",sync_type="UPDATE"} 1
```

## Integration Points

The metrics system integrates with gitout at the following points:

### 1. Engine.performSync()
- Tracks overall sync start/end times
- Records total repository count
- Measures total operation duration

### 2. Engine.executeSyncTasksInParallel()
- Increments/decrements active worker count
- Records sync attempts with type (CLONE/UPDATE)
- Measures per-repository sync duration
- Records success or failure with error details

### 3. RetryPolicy.execute()
- Counts retry attempts
- Records retry delay durations
- Associates retries with specific operations

## Usage Examples

### Basic Usage

```bash
# Use default settings (console output)
gitout config.toml /backup/dir

# Export JSON to file
gitout --metrics-format=json --metrics-path=/logs/metrics.json config.toml /backup/dir

# Prometheus metrics to stdout
gitout --metrics-format=prometheus config.toml /backup/dir > /var/lib/prometheus/textfile_collector/gitout.prom
```

### With Cron Jobs

```bash
# Export metrics after each scheduled sync
GITOUT_METRICS_FORMAT=json GITOUT_METRICS_PATH=/var/log/gitout/metrics-$(date +%Y%m%d-%H%M%S).json \
  gitout --cron="0 */6 * * *" config.toml /backup/dir
```

### Prometheus Node Exporter Integration

```bash
# Configure gitout to export to Prometheus textfile collector directory
gitout --metrics-format=prometheus \
  --metrics-path=/var/lib/prometheus/node_exporter/textfile_collector/gitout.prom \
  config.toml /backup/dir
```

### JSON Log Aggregation

```bash
# Export JSON for ingestion by log aggregators (ELK, Splunk, etc.)
gitout --metrics-format=json --metrics-path=/var/log/gitout/metrics.json config.toml /backup/dir
```

## Alerting and Monitoring

### Prometheus Alert Rules

Example alert rules for Prometheus:

```yaml
groups:
  - name: gitout
    rules:
      - alert: GitoutSyncFailures
        expr: gitout_syncs_failed_total > 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Gitout sync failures detected"
          description: "{{ $value }} repositories failed to sync"

      - alert: GitoutHighRetryRate
        expr: rate(gitout_retry_attempts_total[5m]) > 0.1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High retry rate in gitout"
          description: "Retry rate is {{ $value }} per second"

      - alert: GitoutSlowSyncs
        expr: gitout_sync_duration_seconds{quantile="0.95"} > 60
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "Gitout syncs are slow"
          description: "95th percentile sync time is {{ $value }}s"
```

### Monitoring Dashboards

Key metrics to visualize:
1. **Success Rate**: `gitout_syncs_succeeded_total / gitout_syncs_attempted_total`
2. **Failure Rate**: `gitout_syncs_failed_total / gitout_syncs_attempted_total`
3. **Sync Duration Percentiles**: p50, p95, p99
4. **Active Workers**: Track parallelism utilization
5. **Retry Rate**: `rate(gitout_retry_attempts_total[5m])`

## Performance Impact

The metrics system is designed to be lightweight:
- Thread-safe atomic counters for minimal overhead
- Optional - can be completely disabled
- Concurrent data structures for parallel collection
- Metrics computation only happens at the end of sync
- No network calls or external dependencies

## Best Practices

1. **Enable in Production**: Always enable metrics in production for observability
2. **Choose Appropriate Format**:
   - Use `console` for manual runs and debugging
   - Use `json` for log aggregation systems
   - Use `prometheus` for Prometheus monitoring
3. **Export to Files**: Use `export_path` to retain metrics history
4. **Monitor Trends**: Track metrics over time to identify performance degradation
5. **Alert on Failures**: Set up alerts for sync failures and high retry rates
6. **Review Slow Syncs**: Regularly review the slowest repositories to optimize

## Troubleshooting

### Metrics Not Appearing

1. Check that metrics are enabled in config:
   ```toml
   [metrics]
   enabled = true
   ```

2. Verify CLI flags aren't disabling metrics:
   ```bash
   # Don't use --no-metrics
   gitout config.toml /backup/dir
   ```

3. Check file permissions if using `export_path`

### Incorrect Metric Values

1. Metrics are reset for each sync operation
2. Scheduled syncs (`--cron`) output metrics after each run
3. Failed syncs still record metrics before throwing exceptions

### File Export Issues

1. Ensure parent directory exists
2. Check write permissions
3. Verify disk space available
4. Check path is absolute or relative to execution directory

## API Reference

### Metrics Class

```kotlin
class Metrics {
    // Start overall sync tracking
    fun startOverallSync(repositoryCount: Int)

    // End overall sync tracking
    fun endOverallSync()

    // Record sync attempt
    fun recordSyncAttempt(repoName: String, syncType: SyncType)

    // Record successful sync
    fun recordSyncSuccess(repoName: String, duration: Duration)

    // Record failed sync
    fun recordSyncFailure(repoName: String, error: Throwable)

    // Record retry attempt
    fun recordRetry(repoName: String, delay: Duration)

    // Worker tracking
    fun incrementActiveWorkers(): Int
    fun decrementActiveWorkers(): Int

    // Export metrics
    fun exportConsole(): String
    fun exportJson(): String
    fun exportPrometheus(): String
    fun exportToFile(path: Path, format: ExportFormat)
}
```

## Future Enhancements

Potential improvements for the metrics system:
- Grafana dashboard templates
- Integration with StatsD/DataDog
- Custom metric collectors via plugins
- Real-time metrics streaming
- Metric retention and rotation
- Detailed git operation metrics (fetch, pack, etc.)
