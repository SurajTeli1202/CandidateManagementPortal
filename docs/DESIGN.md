# Design

This document records the design choices reflected in the Candidate Management
project. It is a concise technical overview; setup instructions and the full
API reference are in the root [README](../README.md).

## Architecture

The application is a Java 21, Spring Boot 4.1.2 REST service. Code is organized
by feature and follows a controller → service → repository flow:

- Controllers handle HTTP binding, validation and status codes.
- Services own business rules and transaction boundaries.
- Repositories use Spring Data JPA; search predicates are composed with
  `Specification`.
- Java record DTOs form the API boundary. JPA entities are never serialized
  directly.

Constructor injection is used throughout. The service maps entities to DTOs
within transactions because lazy skill associations are used and
Open Session in View is disabled.

## Persistence and domain behavior

Flyway migrations are the only schema authority; Hibernate validates the schema
at startup (`ddl-auto: validate`) and does not create or alter it. The default
and demo profiles use the same file-backed H2 database at
`./data/candidatesdb`. No production database profile is configured.

Candidates have a many-to-many relationship with normalized, lowercase skills.
Skills are shared reference data and are resolved separately rather than
cascaded from candidate persistence. Candidate creation and updates flush
within the transaction so generated timestamps and optimistic-lock versions
are available in the response.

Deletion is intentionally soft: the service calls `markDeleted()` and saves the
candidate. Hibernate's `@SQLRestriction` hides deleted candidates from
Hibernate-generated reads. I did not use `@SQLDelete`: testing showed that
Hibernate removes the candidate's `candidate_skill` rows before running the
update, so the skill links would be lost and a future restore would bring back
an incomplete record. The unique email constraint still includes deleted
rows, so a deleted candidate's email remains reserved. Native queries used for
duplicate checks explicitly include deleted rows; native SQL does not inherit
the Hibernate restriction.

`@Version` protects updates from lost writes. PUT requires the version the
client last read; stale versions return `409 Conflict`. PUT replaces the
record's request fields rather than acting as PATCH. A null or omitted status
on update preserves the existing status; a null or omitted skills collection
clears the skills.

## Search and pagination

Search filters are combined with AND. Free-text terms are case-insensitive
substring matches across name, email, title and company; `%`, `_` and the
escape character are treated literally. Skill matching uses correlated
subqueries: `ANY` needs one requested skill, while `ALL` needs every requested
skill. Avoiding an outer collection join keeps result pagination and counts
from being multiplied by matching skills.

Sorting is restricted to an allowlist, null values sort last, and `id`
ascending is appended as a stable tie-breaker. The default is `createdAt,desc`,
then `id,asc`. Page size is capped at 100, and the zero-based offset
(`page * size`) cannot exceed 100000; larger offsets return a validation error
asking the caller to narrow the search.

## API, errors and observability

The API is rooted at `/api/v1/candidates`. Create returns `201 Created` with a
`Location` header, delete returns `204 No Content`, and missing, invalid,
duplicate and stale requests use the appropriate 4xx responses. Errors use
RFC 7807 `ProblemDetail`; validation errors include field/message entries.

`RequestIdFilter` accepts a client request ID only when it is a UUID; otherwise
it generates one. Access logs use the matched route template, or `unmatched`,
and never log the raw URI or query string. Candidate logs avoid email and
phone. Hibernate JDBC error logging is disabled because constraint diagnostics
can include submitted values; the exception handler emits a sanitized
integrity-conflict message instead.

## Web UI and tests

The UI is a single static page served by Spring Boot, implemented with plain
HTML, CSS and JavaScript. It uses relative API URLs and DOM text APIs rather
than inserting API data as HTML. Search is debounced; the candidate form uses
the server's version field for optimistic locking, and delete is confirmed
before the soft-delete request.

Tests follow the application layers: JPA slice tests cover Flyway persistence
and specifications, service tests cover business rules, MVC tests cover HTTP
behavior, and Spring Boot integration tests exercise the real repository path.
Spring Boot 4-specific test APIs are used. Test databases are isolated in
memory; the application's default runtime database remains file-backed.

## Recorded decisions and limitations

| Decision | Reason / effect |
|---|---|
| Soft delete through an explicit `markDeleted()` instead of `@SQLDelete` | Keeps the `candidate_skill` links so a restore feature remains possible. Deleted records keep their email reserved, and there is no restore endpoint yet. |
| Use optimistic locking and require `version` on PUT | Detects stale edits and lets clients reload after a conflict. |
| Use a stable default order (`createdAt` descending, `id` ascending) | Makes page boundaries deterministic when timestamps tie. |
| Keep the UI dependency-free and same-origin | Avoids frameworks, build tooling, CDNs and cross-host URL configuration. |

Known limitations:

- Authentication and authorization are not implemented, so anyone who can reach
  the API can read and modify candidate data.
- Status values are enforced by the application (`@Enumerated(STRING)` and
  request validation) and not by a database CHECK constraint. A CHECK is
  omitted because of an H2 bug, and the migration contains a TODO to add it
  back in a later migration.
- No request-body size limit is configured.
- Concurrent requests that use the same new email or create the same new skill
  can fail with a generic `409 Conflict` instead of a specific message. No
  invalid data is stored.
- H2 is the only configured database; a PostgreSQL profile and Docker Compose
  setup are absent. The migration has been run on H2, not verified against
  another database, and MySQL may require a vendor-specific migration.
- Swagger UI is enabled by the application configuration; the demo profile
  also enables the H2 console.

## Scaling to more users and records

Today the application is a single instance with a file-based H2 database,
offset pagination and `LIKE` search. That is right for its current size. Below
is what I would change as data and traffic grow, in the order things would
break.

1. **Database.** Move to PostgreSQL (managed, with backups and a read replica),
   keep Flyway, and verify `V1` on it first. Tune the connection pool. Run
   integration tests against PostgreSQL with Testcontainers. Switch ID
   generation from `IDENTITY` to sequences, because `IDENTITY` prevents JDBC
   insert batching during bulk imports.
2. **Search.** `lower(column) LIKE '%term%'` cannot use a B-tree index, so every
   search scans the table. First step: PostgreSQL `pg_trgm` GIN indexes on the
   searched text, plus an index on `lower(location)` (or a normalized column).
   Next: full-text search for relevance ranking. At very large scale: a search
   engine (OpenSearch or Elasticsearch) fed from the database through an outbox
   or change-data-capture, with the database remaining the source of truth and
   search results being eventually consistent.
3. **Pagination and counts.** Offset paging gets slower with depth; the
   application already caps `page * size` at 100,000. At scale I would use
   keyset pagination on `(created_at, id)` and drop the exact total count (or
   make it optional or approximate), since `COUNT` scans the matching rows.
4. **Indexes.** Add composite indexes that match real filter and sort
   combinations (for example status plus created_at), chosen with
   `EXPLAIN ANALYZE` on realistic data (millions of rows) and not guessed,
   because every index slows writes. Add a reverse index on
   `candidate_skill(skill_id, candidate_id)` for skill-first lookups.
5. **Application tier.** The service is stateless, so it can run as several
   instances behind a load balancer, with configuration from environment
   variables and the existing health endpoint for orchestration. Add rate
   limiting at the gateway.
6. **Caching.** Cache skill reference data and hot reads (for example Redis).
   The existing `version` field can drive ETag / conditional GET and cache
   invalidation.
7. **Write contention.** Optimistic locking stays. Replace the current
   find-or-create for skills with an atomic upsert
   (`INSERT ... ON CONFLICT DO NOTHING`) so concurrent writers stop seeing
   conflicts, and report a duplicate email on concurrent inserts as a clean
   "duplicate email" error.
8. **Soft-deleted data.** Deleted rows accumulate. Add a retention job that
   purges old ones (also needed for privacy erasure requests), and on
   PostgreSQL consider a partial unique index (`WHERE deleted = false`) so a
   deleted candidate's email can be reused if the business wants that.
9. **Multi-tenancy and security.** A recruitment product serves many firms:
   add `tenant_id` to the tables, indexes and every query (or use row-level
   security), add authentication and authorization (OAuth2/OIDC with JWT), and
   record who changed what (`created_by` / `updated_by` through Spring Data
   auditing).
10. **Observability and delivery.** Micrometer metrics with dashboards and
    alerts, OpenTelemetry tracing alongside the existing request ID, a
    slow-query log, a CI pipeline, load tests (k6 or Gatling) with explicit
    targets, and zero-downtime migrations (expand, then contract).
11. **Service boundaries.** Stay a modular monolith for now. Split search and
    indexing into their own service, with asynchronous processing for imports,
    only when team size or load requires it.

Before any of this I would measure: query plans and latency for search at
realistic data volumes, query counts per request, and connection-pool usage.
