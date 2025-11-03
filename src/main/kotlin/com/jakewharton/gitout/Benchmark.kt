package com.jakewharton.gitout

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A simple benchmarking utility for measuring performance of operations.
 *
 * This class provides utilities to measure execution time and track performance metrics.
 * It's designed to help identify bottlenecks and measure the impact of optimizations.
 *
 * Example usage:
 * ```
 * val benchmark = Benchmark("Repository Sync")
 * benchmark.measure("Clone operation") {
 *     syncRepository(url, destination)
 * }
 * benchmark.printSummary()
 * ```
 */
internal class Benchmark(
	private val name: String,
	private val logger: Logger? = null,
) {
	private val measurements = mutableListOf<Measurement>()
	private val startTime = TimeSource.Monotonic.markNow()

	data class Measurement(
		val operation: String,
		val duration: Duration,
		val timestamp: Duration,
	)

	/**
	 * Measures the execution time of a block of code.
	 *
	 * @param operation Name of the operation being measured
	 * @param block The code block to measure
	 * @return The result of executing the block
	 */
	inline fun <T> measure(operation: String, block: () -> T): T {
		val start = TimeSource.Monotonic.markNow()
		try {
			return block()
		} finally {
			val duration = start.elapsedNow()
			val timestamp = startTime.elapsedNow()
			measurements.add(Measurement(operation, duration, timestamp))
			logger?.debug { "BENCHMARK [$name] $operation: $duration" }
		}
	}

	/**
	 * Measures the execution time of a suspend block of code.
	 *
	 * @param operation Name of the operation being measured
	 * @param block The suspend block to measure
	 * @return The result of executing the block
	 */
	suspend inline fun <T> measureSuspend(operation: String, crossinline block: suspend () -> T): T {
		val start = TimeSource.Monotonic.markNow()
		try {
			return block()
		} finally {
			val duration = start.elapsedNow()
			val timestamp = startTime.elapsedNow()
			measurements.add(Measurement(operation, duration, timestamp))
			logger?.debug { "BENCHMARK [$name] $operation: $duration" }
		}
	}

	/**
	 * Records a timing measurement without executing code.
	 * Useful for measuring external operations.
	 */
	fun record(operation: String, duration: Duration) {
		val timestamp = startTime.elapsedNow()
		measurements.add(Measurement(operation, duration, timestamp))
		logger?.debug { "BENCHMARK [$name] $operation: $duration" }
	}

	/**
	 * Returns the total elapsed time since the benchmark was created.
	 */
	fun totalElapsed(): Duration = startTime.elapsedNow()

	/**
	 * Returns all measurements recorded so far.
	 */
	fun getMeasurements(): List<Measurement> = measurements.toList()

	/**
	 * Prints a summary of all measurements to the logger.
	 */
	fun printSummary() {
		if (measurements.isEmpty()) {
			logger?.info { "BENCHMARK [$name]: No measurements recorded" }
			return
		}

		val totalTime = totalElapsed()
		logger?.info { "BENCHMARK [$name] Summary:" }
		logger?.info { "  Total time: $totalTime" }
		logger?.info { "  Operations: ${measurements.size}" }
		logger?.info { "" }

		// Print individual measurements
		measurements.forEachIndexed { index, measurement ->
			val percentage = (measurement.duration.inWholeMilliseconds.toDouble() /
				totalTime.inWholeMilliseconds.toDouble() * 100).toInt()
			logger?.info {
				"  ${index + 1}. ${measurement.operation}: ${measurement.duration} (${percentage}%)"
			}
		}

		// Calculate and print statistics
		val durations = measurements.map { it.duration.inWholeMilliseconds }
		val sum = durations.sum()
		val avg = if (durations.isNotEmpty()) sum / durations.size else 0
		val min = durations.minOrNull() ?: 0
		val max = durations.maxOrNull() ?: 0

		logger?.info { "" }
		logger?.info { "  Statistics:" }
		logger?.info { "    Average: ${avg}ms" }
		logger?.info { "    Min: ${min}ms" }
		logger?.info { "    Max: ${max}ms" }
	}

	/**
	 * Returns a formatted string summary of the benchmark results.
	 */
	override fun toString(): String {
		val totalTime = totalElapsed()
		return buildString {
			appendLine("Benchmark: $name")
			appendLine("Total time: $totalTime")
			appendLine("Operations: ${measurements.size}")
			measurements.forEach { measurement ->
				appendLine("  - ${measurement.operation}: ${measurement.duration}")
			}
		}
	}
}

/**
 * Performance statistics for tracking sync operations.
 */
internal data class PerformanceStats(
	val totalRepositories: Int,
	val successfulSyncs: Int,
	val failedSyncs: Int,
	val totalDuration: Duration,
	val averageSyncTime: Duration,
	val fastestSync: Duration,
	val slowestSync: Duration,
	val parallelWorkers: Int,
) {
	/**
	 * Calculates the theoretical speedup from parallelization.
	 * Speedup = (total sequential time) / (total parallel time)
	 */
	fun calculateSpeedup(): Double {
		if (totalDuration.inWholeMilliseconds == 0L) return 1.0
		val sequentialTime = averageSyncTime.inWholeMilliseconds * totalRepositories
		return sequentialTime.toDouble() / totalDuration.inWholeMilliseconds.toDouble()
	}

	/**
	 * Calculates the parallel efficiency.
	 * Efficiency = Speedup / Number of Workers
	 */
	fun calculateEfficiency(): Double {
		return calculateSpeedup() / parallelWorkers
	}

	override fun toString(): String {
		return buildString {
			appendLine("Performance Statistics:")
			appendLine("  Total repositories: $totalRepositories")
			appendLine("  Successful syncs: $successfulSyncs")
			appendLine("  Failed syncs: $failedSyncs")
			appendLine("  Total duration: $totalDuration")
			appendLine("  Average sync time: $averageSyncTime")
			appendLine("  Fastest sync: $fastestSync")
			appendLine("  Slowest sync: $slowestSync")
			appendLine("  Parallel workers: $parallelWorkers")
			appendLine("  Speedup: ${"%.2f".format(calculateSpeedup())}x")
			appendLine("  Efficiency: ${"%.1f".format(calculateEfficiency() * 100)}%")
		}
	}
}
