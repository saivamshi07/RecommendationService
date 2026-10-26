# Recommendation Service Architecture & API Guide

## 1. System Overview
A generic, item-agnostic recommendation microservice built with **Spring Boot 4**, **PostgreSQL** (Permanent Store), and **Redis** (Cache). It implements **User-Based Collaborative Filtering (UBCF)** with Cosine Similarity and strict item exclusion.

---

## 2. Recommendation & Caching Flow

```mermaid
flowchart TD
    Start([GET /api/v1/recommendations/userId]) --> RedisCheck{Redis Cache Hit?}
    
    RedisCheck -->|HIT| ReturnRedis([Return Cached Item IDs])
    RedisCheck -->|MISS or Redis Down| Recalc[Recalculate on-demand via CF]
    
    Recalc --> CheckCount{Calculated Items > 0?}
    CheckCount -->|YES| ReturnCalculated([Return Recommended Item IDs])
    ReturnCalculated -.->|Asynchronously| AsyncSave[Atomic Upsert to PostgreSQL & Redis with TTL]
    
    CheckCount -->|NO: 0 items| CheckPolicy{Fallback Policy Enabled?}
    CheckPolicy -->|NO / default| ReturnEmpty([Return empty []])
    CheckPolicy -->|YES| FetchFallback[Fetch Fallback from Redis or DB]
    FetchFallback --> ExcludeRated[Strictly filter out items user already rated]
    ExcludeRated --> ReturnFallback([Return Fallback Item IDs])
```

---

## 3. Configuration (`application.properties`)

```properties
# PostgreSQL Permanent Store
spring.datasource.url=jdbc:postgresql://localhost:5432/recommendationServiceDB
spring.datasource.username=postgres
spring.datasource.password=password

# Redis Cache
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Engine & Cache Settings
recommendation.engine.top-n=10
recommendation.engine.neighbor-k=20
recommendation.cache.ttl-hours=24

# Fallback Policy: default is false (NO fallback: returns empty [])
recommendation.fallback.enabled=false
recommendation.fallback.cron=0 0 0 * * *
recommendation.fallback.limit=20
```

---

## 4. API Specification

### 4.1 Record / Update Rating
* **Endpoint:** `POST /api/v1/ratings`
* **Request:**
```json
{
  "userId": "d8142340-9a29-45bc-8a71-123456789abc",
  "itemId": "movie-101",
  "rating": 3.75
}
```
* **Validation:** 
  * `userId`: `@NotBlank`
  * `itemId`: `@NotBlank`
  * `rating`: 5-point scale with up to 2 decimal digits (`0.00` to `5.00`).
* **Response:** `201 Created`

### 4.2 Get Recommendations
* **Endpoint:** `GET /api/v1/recommendations/{userId}?limit=10`
* **Response:** `200 OK`
```json
{
  "userId": "d8142340-9a29-45bc-8a71-123456789abc",
  "itemIds": [
    "movie-204",
    "movie-305",
    "movie-401"
  ],
  "count": 3,
  "source": "REDIS_CACHE_HIT"
}
```
