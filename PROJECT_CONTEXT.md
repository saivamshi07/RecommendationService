# Project Context & Engineering Architecture: Recommendation Service

This document captures the design context, architectural decisions, domain models, and operational patterns of the **Recommendation Service**. It serves as the primary technical context for engineers and AI agents working on this codebase.

---

## 1. System Landscape & Microservice Ecosystem

The Recommendation Service is a decoupled, pluggable microservice designed to serve as the recommendation engine for a multi-service platform.

```
+-------------------------------------------------------------------+
|                        Client Applications                        |
+-------------------------------------------------------------------+
                               |
               +---------------+---------------+
               |                               |
       [HTTP / REST]                   [HTTP / REST]
               |                               |
               v                               v
    +--------------------+           +--------------------+
    |    user-service    |           |   movie-service    |
    |  - User profiles   |           |  - Movie metadata  |
    |  - Authentication  |           |  - Catalog search  |
    +--------------------+           +--------------------+
               |                               |
               |                               | Submit Rating /
               |                               | Get Recommended Item IDs
               |                               v
+-------------------------------------------------------------------+
|                      RECOMMENDATION-SERVICE                       |
|                                                                   |
|  - Ingestion: (userId, itemId, rating)                            |
|  - Computation: User-Based Collaborative Filtering (UBCF)         |
|  - Storage: Redis (Cache) + PostgreSQL (Permanent Store)          |
|  - Output: List of recommended itemId strings                     |
+-------------------------------------------------------------------+
```

### Ecosystem Contracts:
* **`user-service`** maintains user accounts and authentication. User IDs are UUIDs (e.g. `d8142340-9a29-45bc-8a71-123456789abc`).
* **`movie-service`** maintains movie catalogs, titles, posters, and cast.
* **`recommendation-service`** is **completely domain-agnostic**:
  * It does **not** know titles, genres, descriptions, or user demographics.
  * It communicates purely with string identifiers (`userId`, `itemId`) and numerical ratings.
  * When `movie-service` requests recommendations for user $U$, the recommendation service returns `["item-101", "item-204"]`. The `movie-service` then hydrates these IDs with movie titles and poster URLs.
  * The exact same service can be plugged into a `book-service`, `music-service`, or `course-service` without code changes.

---

## 2. Architecture Decision Records (ADRs)

### ADR-1: String-Based Identifier Model
* **Context:** `user-service` uses Postgres `UUID`, while catalog services (`movie-service`, `product-service`) may use `Long`, `UUID`, or slug strings.
* **Decision:** Model `userId` and `itemId` as generic `String` (length 64) in Java and `VARCHAR(64)` in PostgreSQL.
* **Rationale:** Maximizes pluggability across diverse calling services without schema migrations or adapters.

### ADR-2: Rating Scale with 2-Digit Fractions
* **Context:** Ratings can be non-integer values (e.g., 3.5, 3.75, 4.25).
* **Decision:** Use Java `BigDecimal` with scale 2 and PostgreSQL `NUMERIC(3, 2)` constrained from `0.00` to `5.00`.
* **Rationale:** Eliminates floating-point rounding errors and preserves exact rating precision submitted by users.

### ADR-3: Dual-Store Strategy (Redis + PostgreSQL)
* **Context:** Redis offers sub-millisecond retrieval, but Redis downtime or eviction policies could cause loss of precomputed recommendations. Recomputing recommendations from scratch for every cache miss under heavy traffic degrades throughput.
* **Decision:**
  * **Redis (Hot Cache):** Stores user recommendations (`recommendations:user:{userId}`) and fallback (`recommendations:fallback`) with a 24-hour TTL.
  * **PostgreSQL (Permanent Store):** Every recommendation calculation performs an atomic write-through to `user_recommendations` and `fallback_recommendations` tables.
  * **Resilience:** All Redis calls are wrapped in exception shields. If Redis is down, reads fall back seamlessly to PostgreSQL or on-demand calculation, and writes continue to persist in PostgreSQL.

### ADR-4: Scalable Viewer Refresh Pattern ("Read-Driven On-Demand")
* **Context:** Collaborative Filtering is a 2-sided graph: user $A$'s recommendations depend on what similar neighbors rate, even if user $A$ hasn't rated anything new recently. Running a nightly batch recalculating all 10M+ registered users wastes 90% of CPU cycles on dormant users.
* **Decision:**
  1. On rating submission (`POST /ratings`): Asynchronously recalculate for the rating user.
  2. In Redis: User recommendations expire after a TTL (24 hours).
  3. When an active viewer requests recommendations (`GET /recommendations`):
     * If Redis HIT $\to$ return instantly.
     * If Redis MISS (cache expired or missing) $\to$ recalculate on-demand using latest neighbor ratings, return immediately, and asynchronously update Redis and PostgreSQL.
* **Rationale:** Computes fresh recommendations only for active viewers who actually open the app, spending zero compute on inactive accounts.

### ADR-5: Configurable Fallback Policy (Default: NO)
* **Context:** Some clients prefer an empty recommendation feed if no personalized match exists; other clients prefer falling back to globally popular items.
* **Decision:** Introduce `recommendation.fallback.enabled=false` by default.
  * If `false`: If CF yields 0 recommendations, returns empty list `[]`.
  * If `true`: Returns global top-rated items, **strictly excluding any items the user has already rated**.
  * A `@Scheduled` cron job updates the global fallback recommendations every 24 hours at midnight.

### ADR-6: Atomic PostgreSQL Upserts via Native `ON CONFLICT`
* **Context:** Concurrently running async recommendation threads for the same user could cause race conditions (`duplicate key value violates unique constraint "user_recommendations_pkey"`).
* **Decision:** Use PostgreSQL native `INSERT INTO ... ON CONFLICT (user_id) DO UPDATE` in repository queries.
* **Rationale:** Completely thread-safe, atomic, and prevents constraint violation exceptions during concurrent execution.

---

## 3. Package Structure & Module Responsibilities

```
src/main/java/com/platform/recommendationservice/
├── RecommendationServiceApplication.java      # Main entry point (@EnableAsync, @EnableScheduling)
├── config/
│   ├── AsyncConfig.java                       # ThreadPoolTaskExecutor configuration
│   ├── RecommendationProperties.java          # Type-safe @ConfigurationProperties binding
│   └── RedisConfig.java                       # RedisTemplate & ObjectMapper setup
├── controller/
│   ├── RatingController.java                  # POST /api/v1/ratings
│   └── RecommendationController.java          # GET /api/v1/recommendations/{userId}
├── dto/
│   ├── RatingRequest.java                     # Input validation (@DecimalMin, @DecimalMax, @Digits)
│   ├── RatingResponse.java                    # Output model for submitted rating
│   └── RecommendationResponse.java            # Output model for recommendations + source metadata
├── entity/
│   ├── FallbackRecommendation.java            # Entity for global fallback items
│   ├── StringListJsonConverter.java           # JPA AttributeConverter for List<String> <-> JSON TEXT
│   ├── UserRating.java                        # Entity for individual user-item ratings
│   └── UserRecommendation.java                # Entity for user precomputed recommendation list
├── exception/
│   └── GlobalExceptionHandler.java            # Standardized JSON error response handler
├── repository/
│   ├── FallbackRecommendationRepository.java  # Native atomic upsert for fallback items
│   ├── UserRatingRepository.java              # Candidate neighbor queries & top popular queries
│   └── UserRecommendationRepository.java      # Native atomic upsert for user recommendations
└── service/
    ├── AsyncRecommendationService.java        # Background recalculation & DB/Redis write-through
    ├── FallbackService.java                   # Fallback caching, retrieval, and user item exclusion
    ├── RatingService.java                     # Synchronous rating persistence + async trigger
    ├── RecommendationService.java             # Core GET flow (Redis -> Recalculate -> Fallback)
    ├── RedisCacheService.java                 # Fault-tolerant Redis read/write operations
    ├── algorithm/
    │   └── CollaborativeFilteringEngine.java  # UBCF Cosine Similarity and candidate ranking
    └── scheduler/
        └── FallbackScheduler.java             # Daily cron job (0 0 0 * * *)
```

---

## 4. Key Database Queries & Optimizations

### Candidate Neighbor Query (`UserRatingRepository`)
To avoid loading the entire database of all users during recommendation calculation, the engine uses a 2-tier subquery:
```sql
SELECT r FROM UserRating r 
WHERE r.userId IN (
    SELECT DISTINCT r2.userId FROM UserRating r2 
    WHERE r2.itemId IN (
        SELECT r1.itemId FROM UserRating r1 WHERE r1.userId = :userId
    )
)
```
This isolates the calculation strictly to users who share at least one co-rated item with the target user, backed by btree indexes on `(user_id)` and `(item_id)`.

### Atomic Upserts
```sql
INSERT INTO user_recommendations (user_id, recommended_item_ids, calculated_at)
VALUES (:userId, :itemIds, :calculatedAt)
ON CONFLICT (user_id) 
DO UPDATE SET recommended_item_ids = EXCLUDED.recommended_item_ids, calculated_at = EXCLUDED.calculated_at;
```

---

## 5. Testing Strategy

The test suite covers unit, integration, and web layer contracts:

1. **`CollaborativeFilteringEngineTest`**:
   * Mathematical verification of Cosine Similarity on fractional ratings (`3.75`, `4.50`).
   * Verifies strict exclusion: already rated items **never** appear in recommendations.
   * Verifies ranking order based on neighbor rating weights.
   * Edge case: Cold user with 0 ratings returns empty list.
2. **`RatingAndRecommendationControllerTest`**:
   * Verifies REST contract for `POST /api/v1/ratings` with fractional rating.
   * Verifies validation constraints: rating $> 5.00$ or rating with $> 2$ decimal digits returns `400 Bad Request`.
   * Verifies `GET /api/v1/recommendations/{userId}` with empty state.
3. **`RecommendationIntegrationTest`**:
   * End-to-end integration with PostgreSQL and Redis.
   * Tests rating upsert behavior.
   * Tests cache miss followed by cache hit.
   * Tests fallback toggle (`false` vs `true`).

---

## 6. Future Scale & Evolution Roadmap

1. **Item-to-Item Collaborative Filtering (IBCF):**
   * When user base reaches millions and item catalog remains under $100,000$, transition the heavy batch computation to an **Item-Item Similarity Matrix**. User scoring then becomes a sub-millisecond dot product.
2. **Kafka Event Streaming:**
   * Replace Spring `@Async` in-memory thread pool with a Kafka topic `user-ratings-topic`. This enables horizontal scaling of recommendation worker instances independently from the REST API.
3. **Vector Embeddings & ANN Search:**
   * Transition to dense user and item embeddings using PGVector or Milvus/Qdrant for approximate nearest neighbor (ANN) search at massive scale.
