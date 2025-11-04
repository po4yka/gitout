package com.jakewharton.gitout

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.network.http.DefaultHttpEngine
import dev.drewhamilton.poko.Poko
import okhttp3.OkHttpClient

internal class GitHub(
	private val user: String,
	private val token: String,
	private val client: OkHttpClient,
	private val logger: Logger,
) {
	@Poko
	class UserRepositories(
		val owned: Set<String>,
		val starred: Set<String>,
		val watching: Set<String>,
		val gists: Set<String>,
	)

	suspend fun loadRepositories(): UserRepositories {
		val owned = mutableSetOf<String>()
		val starred = mutableSetOf<String>()
		val watching = mutableSetOf<String>()
		val gists = mutableSetOf<String>()

		ApolloClient.Builder()
			.serverUrl("https://api.github.com/graphql")
			.httpEngine(DefaultHttpEngine(client))
			.addHttpHeader("Authorization", "Bearer $token")
			.addHttpHeader("User-Agent", "gitout/$version")
			.build()
			.use { client ->
				var ownedAfter: Optional<String?> = Optional.Absent
				var starredAfter: Optional<String?> = Optional.Absent
				var watchingAfter: Optional<String?> = Optional.Absent
				var gistsAfter: Optional<String?> = Optional.Absent

				while (true) {
					val query = UserReposQuery(
						login = user,
						ownerAfter = ownedAfter,
						starredAfter = starredAfter,
						watchingAfter = watchingAfter,
						gistsAfter = gistsAfter,
					)
					logger.debug { "Requesting page $query" }

					val response = client.query(query).execute().dataAssertNoErrors
					logger.trace { response.toString() }

					val user = response.user
			?: throw IllegalStateException("GitHub user not found: $user. Check username and token permissions.")

					val ownedEdges = user.ownedRepositories.ownedEdges.orEmpty()
					val starredEdges = user.starredRepositories.starredEdges.orEmpty()
					val watchingEdges = user.watchingRepositories.watchingEdges.orEmpty()
					val gistEdges = user.gistRepositories.gistEdges.orEmpty()
					if (ownedEdges.isEmpty() && starredEdges.isEmpty() && watchingEdges.isEmpty() && gistEdges.isEmpty()) {
						break // All cursors exhausted!
					}

					for (ownedEdge in ownedEdges) {
						val node = ownedEdge?.node
						if (node != null) {
							owned += node.nameWithOwner
							ownedAfter = Optional.present(ownedEdge.cursor)
						} else {
							logger.warn("Skipping owned repository with null node")
						}
					}
					for (starredEdge in starredEdges) {
						val node = starredEdge?.node
						if (node != null) {
							starred += node.nameWithOwner
							starredAfter = Optional.present(starredEdge.cursor)
						} else {
							logger.warn("Skipping starred repository with null node")
						}
					}
					for (watchingEdge in watchingEdges) {
						val node = watchingEdge?.node
						if (node != null) {
							watching += node.nameWithOwner
							watchingAfter = Optional.present(watchingEdge.cursor)
						} else {
							logger.warn("Skipping watching repository with null node")
						}
					}
					for (gistEdge in gistEdges) {
						val node = gistEdge?.node
						if (node != null) {
							gists += node.name
							gistsAfter = Optional.present(gistEdge.cursor)
						} else {
							logger.warn("Skipping gist with null node")
						}
					}
				}
			}

		return UserRepositories(
			owned = owned,
			starred = starred,
			watching = watching,
			gists = gists,
		)
	}
}
