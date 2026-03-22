# Semantic Repository Search — Design

**Date:** 2026-03-22
**Status:** Approved

## Overview

Add natural language search over backed-up repositories using Gemini Embedding 2
for embedding generation and Qdrant as the local vector store. Search is exposed
via a new `gitout search` CLI subcommand and a `/find` Telegram bot command.

---

## Goals

- Find repositories by natural language description (e.g., "OAuth authentication library in Kotlin")
- Index GitHub metadata (name, description, topics, language) + README content
- Run entirely on the existing Raspberry Pi 5 infrastructure
- Never block or fail the sync cycle due to indexing errors

---

## Architecture

### New Components

| Component | File | Responsibility |
|-----------|------|----------------|
| `SearchIndexService` | `SearchIndexService.kt` | Orchestrates indexing and search |
| `GeminiEmbeddingClient` | `GeminiEmbeddingClient.kt` | Calls Gemini Embedding 2 REST API |
| `QdrantClient` | `QdrantClient.kt` | Calls Qdrant REST API (upsert, search, create-collection) |
| `ReadmeExtractor` | `ReadmeExtractor.kt` | Extracts README from bare git repos via subprocess |

### Indexing Data Flow

```
sync completes (or manual `gitout index`)
  └─ for each repo:
       ├─ GitHub metadata (name, description, topics, language) — from Apollo query result
       ├─ README text — extracted via `git show HEAD:README.md` on bare repo
       ├─ combine into document text
       ├─ compute SHA-256 of document text
       ├─ skip if SHA matches value stored in Qdrant payload (no change)
       ├─ call GeminiEmbeddingClient.embed(documentText) → float array (3072-dim)
       └─ call QdrantClient.upsert(id, vector, payload)
```

### Search Data Flow

```
user query (CLI `gitout search` or Telegram `/find`)
  └─ GeminiEmbeddingClient.embed(query) → vector
  └─ QdrantClient.search(vector, topK) → scored results
  └─ format and return to user
```

---

## Configuration

New `[search]` block in `config.toml`:

```toml
[search]
enabled = true
qdrant_url = "http://qdrant:6333"
collection_name = "repositories"
top_k = 10
auto_index = true
```

Gemini API key resolution (priority order, consistent with existing token patterns):
1. `GEMINI_API_KEY` environment variable
2. `GEMINI_API_KEY_FILE` environment variable (path to file containing the key)

### Config.kt Addition

```kotlin
@Poko @Serializable
class Search(
    val enabled: Boolean = false,
    @SerialName("qdrant_url") val qdrantUrl: String = "http://localhost:6333",
    @SerialName("collection_name") val collectionName: String = "repositories",
    @SerialName("top_k") val topK: Int = 10,
    @SerialName("auto_index") val autoIndex: Boolean = true,
)
```

Add `val search: Search = Search()` to the root `Config` class.

---

## CLI Interface

New Clikt subcommands alongside the existing `GitOutCommand`:

```bash
# Re-index all repos
gitout index config.toml /backup/path

# Search with natural language
gitout search "authentication OAuth library" config.toml /backup/path
```

Both subcommands share the same `Config` parsing and `OkHttpClient` setup as the
main command.

---

## Telegram Interface

New commands added to the existing `TelegramNotificationService` dispatcher:

| Command | Description | Auth required |
|---------|-------------|---------------|
| `/find <query>` | Search repos by natural language | Yes (allowed_users) |
| `/reindex` | Trigger full re-index | Yes (allowed_users) |

Response format for `/find`:
```
Found 3 repositories matching "OAuth authentication":

1. myorg/auth-service (score: 0.91)
   Kotlin OAuth2 server with JWT support
   Language: Kotlin | Topics: oauth, jwt, security

2. myorg/passport-middleware (score: 0.87)
   ...
```

---

## Implementation Details

### GeminiEmbeddingClient

API endpoint:
```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-exp-03-07:embedContent
```

Request body:
```json
{
  "model": "models/gemini-embedding-exp-03-07",
  "content": {
    "parts": [{ "text": "..." }]
  }
}
```

Response:
```json
{ "embedding": { "values": [0.123, -0.456, ...] } }
```

- Uses existing `OkHttpClient` instance (injected, not created fresh)
- API key passed as `?key={GEMINI_API_KEY}` query parameter
- Vector dimension: 3072
- Throws `SearchException` on API error; caller logs and skips

### ReadmeExtractor

```
git --git-dir=/backup/path/{repo}.git show HEAD:README.md
```

- Tries `README.md`, `README.rst`, `README` in order
- Returns empty string if none found (does not fail)
- Truncates output to 8000 characters before embedding

### Document Text Format

Sent to Gemini for embedding:

```
{repo name}
{description}
topics: {topic1, topic2, topic3}
language: {Kotlin}
---
{README first 8000 chars}
```

### QdrantClient

Three operations, all via OkHttp + kotlinx.serialization.json:

1. **`ensureCollection(name, vectorSize)`** — creates collection if not exists
   ```
   PUT /collections/{name}
   Body: { "vectors": { "size": 3072, "distance": "Cosine" } }
   ```

2. **`upsert(collectionName, id, vector, payload)`** — insert or update a point
   ```
   PUT /collections/{name}/points
   Body: { "points": [{ "id": "{uuid}", "vector": [...], "payload": {...} }] }
   ```

3. **`search(collectionName, vector, topK)`** — nearest-neighbour search
   ```
   POST /collections/{name}/points/search
   Body: { "vector": [...], "limit": 10, "with_payload": true }
   ```

Point ID: deterministic UUID derived from repo name (UUID v5 with a fixed namespace).

Payload stored per point:
```json
{
  "name": "myorg/auth-service",
  "description": "Kotlin OAuth2 server",
  "language": "Kotlin",
  "topics": ["oauth", "jwt"],
  "url": "https://github.com/myorg/auth-service",
  "content_sha": "abc123",
  "indexed_at": "2026-03-22T10:00:00Z"
}
```

`content_sha` is SHA-256 of the document text. On re-index, fetch existing payload,
compare SHA, skip embedding call if unchanged.

### Rate Limiting

- Gemini Embedding API free tier: 1500 requests/day, 100 RPM
- Indexing loop: 100ms delay between embedding calls (`delay(100)` in coroutine)
- Skip unchanged repos (SHA comparison) to minimise API calls on re-index runs

### Error Handling

- Any indexing error (Gemini API, Qdrant, git subprocess) is logged as a warning
- Indexing failures never propagate to the sync cycle
- Search errors return a user-facing error message ("Search unavailable: ...")
- If `search.enabled = false`, all search operations are no-ops

---

## Docker Changes

Add to `docker-compose.yml`:

```yaml
qdrant:
  image: qdrant/qdrant:latest
  restart: unless-stopped
  ports:
    - "6333:6333"
  volumes:
    - /mnt/nvme/gitout/qdrant:/qdrant/storage
```

Data stored at `/mnt/nvme/gitout/qdrant/` (consistent with NVMe layout).

---

## Dependencies

No new Gradle dependencies required. All implementation uses:
- `OkHttp` (already present) — HTTP calls to Gemini and Qdrant
- `kotlinx.serialization.json` (already present) — JSON serialisation
- `kotlinx.coroutines` (already present) — async indexing, rate-limit delay
- Standard library `MessageDigest` — SHA-256 content hashing
- Standard library `UUID` — deterministic point IDs

---

## File Structure

```
src/main/kotlin/com/jakewharton/gitout/
├── search/
│   ├── SearchIndexService.kt
│   ├── GeminiEmbeddingClient.kt
│   ├── QdrantClient.kt
│   └── ReadmeExtractor.kt
├── SearchCommand.kt       # `gitout search` Clikt subcommand
└── IndexCommand.kt        # `gitout index` Clikt subcommand
```

---

## Testing Strategy

| Test | Approach |
|------|----------|
| `GeminiEmbeddingClientTest` | MockWebServer, verify request shape and response parsing |
| `QdrantClientTest` | MockWebServer, verify upsert/search/create-collection calls |
| `ReadmeExtractorTest` | Temporary bare git repo, assert README extraction and fallback |
| `SearchIndexServiceTest` | Mock both clients, verify SHA deduplication and error isolation |

---

## Integration Checklist

- [ ] Add `Search` config class and field to `Config.kt`
- [ ] Add `[search]` validation to `Config.validate()`
- [ ] Implement `ReadmeExtractor`
- [ ] Implement `GeminiEmbeddingClient`
- [ ] Implement `QdrantClient`
- [ ] Implement `SearchIndexService` (index + search methods)
- [ ] Add `IndexCommand` and `SearchCommand` Clikt subcommands
- [ ] Add `/find` and `/reindex` Telegram commands to `TelegramNotificationService`
- [ ] Call `searchIndexService.indexAll()` at end of `Engine.performSync()` when `autoIndex = true`
- [ ] Add Qdrant service to `docker-compose.yml`
- [ ] Add `GEMINI_API_KEY` / `GEMINI_API_KEY_FILE` to `docker-entrypoint.sh` passthrough
- [ ] Write tests for all new components
- [ ] Update README with `[search]` config documentation
