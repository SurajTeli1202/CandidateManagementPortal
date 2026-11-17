# Candidate Management API

A project for a REST API to manage candidate records, including CRUD,
search, filtering, pagination, and sorting on a Flyway-managed schema.

## Technology

- Java 21
- Spring Boot 4.1.2
- Maven
- Spring MVC, Spring Data JPA, Bean Validation, Flyway, Actuator
- H2 database

## Requirements

- JDK 21
- No separate Maven installation is needed; the project includes the Maven
  Wrapper.

Check the Java version Maven will use:

```shell
./mvnw -v
```

On Windows, use `.\mvnw.cmd -v`.

## Run

Start the application with:

```shell
./mvnw spring-boot:run
```

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

The application uses a file-based H2 database at
`./data/candidatesdb` by default. The H2 console is disabled unless the `demo`
profile is active.

### Demo profile

Start with `--spring.profiles.active=demo` to enable the H2 console and load
30 clearly fictional candidates into an empty database:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=demo"
```

The demo data includes all statuses, Pune/Bangalore/Mumbai/Remote locations,
experience from 0.5 to 15 years (and some unknown values), notice periods from
0 to 90 days, varied skills, and candidates with no skills or missing optional
fields. Names are fictional, surnames are `Demo`, and emails use `example.com`.
Requests are Bean Validated and created through `CandidateService`, including
its normal email and skill normalization.

Loading is transactional and skipped if any candidate rows already exist,
including soft-deleted rows. Restarts do not add duplicates or top up existing
data. The profile uses the same configured database; it does not reset or
replace existing records.

With the demo profile active, the H2 console is available at
`http://localhost:8080/h2-console`; connect with JDBC URL
`jdbc:h2:file:./data/candidatesdb` and username `sa` (no password).

The configured Actuator endpoints include:

- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/info`

## Candidate web UI

Open `http://localhost:8080/` after starting the application. Spring Boot serves
the HTML, CSS, and JavaScript directly; there is no frontend build or external
dependency. The page supports debounced text search, filters, sorting,
pagination, and create/edit dialogs. Delete requires confirmation and uses the
existing soft-delete API. Use **More filters** to expand the additional search
fields; the summary shows how many are active even when the section is closed.
Choose one or more status checkboxes to filter by any of those statuses.

The editor keeps the version returned by the API. A concurrent-update conflict
offers Reload, which discards unsaved edits and fetches the latest candidate.
Duplicate-email and validation errors appear beside their fields. Empty
optional values are submitted as null, and an empty skills input clears skills.

To run the UI browser check separately, use
`node --test src\test\browser\candidate-ui.test.js`. It requires Node.js and
Chrome or Edge; set `CHROME_PATH` if the browser is not in a standard location.

## API documentation and request IDs

Springdoc OpenAPI 3.0.3 provides Swagger UI at
`http://localhost:8080/swagger-ui.html` (redirects to the UI) and the OpenAPI
document at `http://localhost:8080/v3/api-docs`.

Every request returns an `X-Request-Id` header. A supplied value is accepted
only when it contains 1-64 letters, digits, underscores or hyphens; otherwise
a UUID is generated. The ID is available as `requestId` in the logging MDC
and is removed when request processing finishes.

An INFO access line records the HTTP method, path, status and duration in
milliseconds. It never includes query strings, request bodies or other
headers. Actuator and Swagger paths are excluded from access logging, but
still receive request IDs.

## Test and verify

Run the test suite and package verification:

```shell
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

The JPA repository tests use an in-memory H2 database. Flyway applies the
migrations in `src/main/resources/db/migration`, and Hibernate validates the
resulting schema rather than generating it.

## Current implementation

- `Candidate` and `Skill` JPA entities, with normalized lowercase skill names
- Candidate status enum, auditing timestamps, optimistic locking, and
  candidate-to-skill many-to-many mapping
- `CandidateRepository` with `JpaRepository` and
  `JpaSpecificationExecutor`
- Soft deletion: deleted candidates are hidden from Hibernate-generated
  queries, but remain in the database
- Flyway migration for candidates, skills, their join table, constraints, and
  indexes
- REST API under `/api/v1/candidates`: `POST` (201 + `Location`),
  `GET` (paged search), `GET /{id}`, `PUT /{id}` (requires the current
  `version`), `DELETE /{id}` (soft delete, 204)
- Errors are returned as RFC 7807 `ProblemDetail` (400, 404, 409, 500)

## Search candidates

`GET /api/v1/candidates` returns active candidates only. All query parameters
are optional; supplied filters are combined with AND.

| Parameter | Behavior |
|---|---|
| `q` | At most 100 characters and 5 whitespace-separated tokens. Every token must occur in at least one of first name, last name, email, current title, or current company, ignoring case. `%`, `_`, and `!` are treated literally, not as SQL wildcards. |
| `status` | One or more enum names, such as `status=NEW&status=SCREENING` or `status=NEW,SCREENING`. Matches any requested status. |
| `location` | At most 150 characters. Case-insensitive exact match, with surrounding whitespace trimmed. |
| `skills` | At most 20 entries, each at most 100 characters. Comma-separated skill names, trimmed, lowercased, and deduplicated. |
| `skillMatch` | `ALL` (default) or `ANY`. Ignored when no skills are supplied. |
| `minExperience`, `maxExperience` | Inclusive nonnegative decimal bounds; minimum must not exceed maximum. Candidates with unknown experience do not match a supplied bound. |
| `maxNoticePeriod` | Inclusive nonnegative day limit; candidates with unknown notice period do not match. |
| `page` | Zero-based page number, default `0`. |
| `size` | Page size from `1` to `100`, default `20`. |
| `sort` | One `field,direction` pair, default `createdAt,desc`. Directions are `asc` or `desc` (case-insensitive). |

Sortable fields are `createdAt`, `updatedAt`, `firstName`, `lastName`,
`experienceYears`, and `status`. Every ordering ends with `id` ascending as a
stable tie-breaker; `id` itself is not a user-selectable sort field. Every sort
order specifies nulls last, including for ascending and descending experience.

Example:

```text
GET /api/v1/candidates?q=java%20engineer&status=SCREENING&skills=java,spring&skillMatch=ALL&minExperience=2&size=10&sort=lastName,asc
```

The response contains only `content` (a list of candidate response DTOs),
`page`, `size`, `totalElements`, and `totalPages`, rather than Spring's internal
`Page` representation. An out-of-range page has empty content but retains the
matching total.

Invalid enums, numeric values, page bounds, experience ranges, or sort pairs
return `400 application/problem+json`. Spring Boot 4 reports query binding and
validation failures as `MethodArgumentNotValidException`; the existing handler
returns the same `errors` list of `{ "field": "...", "message": "..." }` as
request-body validation. Conversion errors use a generic message without
echoing rejected values. An inverted experience range uses the field
`experienceRangeValid`.

Skill filtering uses correlated subqueries, not outer collection joins, so
matching multiple skills does not duplicate candidates or inflate totals.
Response mapping runs inside a read-only service transaction; the configured
Hibernate batch fetch size of 50 handles lazy skill loading without a
pagination fetch join.

## Database notes

The schema is managed exclusively by Flyway. Hibernate is configured with
`ddl-auto: validate`; do not use Hibernate schema generation to modify it.
Add future schema changes as new Flyway migrations.

The current migration uses SQL that works with H2 and PostgreSQL. MySQL may
require a vendor-specific migration. Candidate email is unique across all
rows, including soft-deleted candidates, so a deleted candidate's email
remains reserved.
