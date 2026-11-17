# Candidate Management Project — Implementation Notes

This document describes the behavior implemented in the Candidate Management
project. It is an implementation reference, not a roadmap: features
described as "not implemented" are not currently available in the application.
See the root `README.md` for setup, run commands, URLs and curl examples. The
source code is the reference for exact implementation; it is not duplicated
here.

## Project overview

The project is a Java 21 / Spring Boot 4.1.2 REST application using Maven,
Spring MVC, Spring Data JPA, Bean Validation, Flyway, H2, Actuator, and
springdoc-openapi 3.0.3. Jackson 3 is used by Spring Boot 4 for JSON
processing. A static single-page UI is served from `src/main/resources/static`.

The main package is `com.suraj.candidates`. Code is organized by feature and
role:

```text
com.suraj.candidates
├── candidate
│   ├── Candidate.java
│   ├── CandidateController.java
│   ├── CandidateMapper.java
│   ├── CandidateNotFoundException.java
│   ├── CandidateRepository.java
│   ├── CandidateSpecifications.java
│   ├── CandidateService.java
│   ├── CandidateStatus.java
│   ├── DuplicateEmailException.java
│   ├── SkillMatch.java
│   └── dto
│       ├── CandidateRequest.java
│       ├── CandidateResponse.java
│       ├── CandidateSearchCriteria.java
│       └── PageResponse.java
├── config
│   ├── DemoDataLoader.java
│   ├── JpaAuditingConfig.java
│   ├── OpenApiConfig.java
│   └── RequestIdFilter.java
├── error
│   └── GlobalExceptionHandler.java
├── skill
│   ├── Skill.java
│   └── SkillRepository.java
└── ui
    └── HomeController.java
```

Resources:

```text
src/main/resources
├── application.yml
├── application-demo.yml
├── db/migration/V1__init.sql
└── static
    ├── index.html
    ├── app.js
    └── styles.css
```

The request flow is:

```text
HTTP request
  -> CandidateController
  -> CandidateService
  -> CandidateRepository / SkillRepository
  -> JPA entities and database
  -> CandidateMapper
  -> response DTO
```

JPA entities are not returned directly by the API. Services own business rules
and transaction boundaries; controllers handle HTTP mapping.

## API

All candidate routes use the base path `/api/v1/candidates`.

| Method | Path | Success response | Behavior |
|---|---|---|---|
| `POST` | `/api/v1/candidates` | `201 Created`, response body, and `Location` header | Creates a candidate |
| `GET` | `/api/v1/candidates` | `200 OK` and a `PageResponse` body | Searches, filters, sorts, and pages active candidates |
| `GET` | `/api/v1/candidates/{id}` | `200 OK` and response body | Reads a non-deleted candidate |
| `PUT` | `/api/v1/candidates/{id}` | `200 OK` and response body | Replaces candidate request fields using an optimistic-lock version |
| `DELETE` | `/api/v1/candidates/{id}` | `204 No Content` | Soft-deletes the candidate |

### Search, filtering, pagination, and sorting

`GET /api/v1/candidates` accepts these optional query parameters:

| Parameter | Behavior |
|---|---|
| `q` | At most 100 characters and 5 tokens. Splits on whitespace. Every token must case-insensitively match (substring) at least one of first name, last name, email, current title, or current company. `%`, `_`, and the escape character `!` are treated literally. |
| `status` | One or more enum values, supplied as repeated parameters or comma-separated values; matches any. |
| `location` | At most 150 characters. Case-insensitive exact match, trimmed. |
| `skills` | At most 20 entries of at most 100 characters each. Comma-separated skill names normalized by `Skill.normalize`. |
| `skillMatch` | `ALL` (default) requires every requested skill; `ANY` requires at least one. |
| `minExperience`, `maxExperience` | Inclusive experience bounds; each must be non-negative and the minimum cannot exceed the maximum. |
| `maxNoticePeriod` | Inclusive, non-negative upper bound on notice period days. |
| `page`, `size` | Zero-based page and page size; defaults are `0` and `20`, and size is limited to `100`. |
| `sort` | `field,direction`, where field is one of `createdAt`, `updatedAt`, `firstName`, `lastName`, `experienceYears`, or `status`, and direction is `asc` or `desc`. |

The default sort is `createdAt,desc`; every sort order puts nulls last, and
`id,asc` is always appended as a stable tie-breaker. Invalid or unsupported
values return a 400 ProblemDetail; an inverted experience range is reported on
the field `experienceRangeValid`. The
response is a `PageResponse` with `content`, `page`, `size`, `totalElements`,
and `totalPages`, rather than Spring Data's `Page` representation.

Search predicates are built as Spring Data JPA Specifications and combined
with AND. Skill filtering uses correlated subqueries (EXISTS for ANY and a
distinct-skill count for ALL), not an outer collection join, so pagination
and total counts are not inflated by multiple matching skills. The
`@SQLRestriction("deleted = FALSE")` on Candidate also applies to these
Hibernate-generated specification queries, keeping soft-deleted candidates
out of search results.

Example:

```text
GET /api/v1/candidates?q=senior%20java&status=NEW,SCREENING&skills=java,spring&skillMatch=ALL&page=0&size=20&sort=createdAt,desc
```

### Example create request

```json
{
  "firstName": "Jane",
  "lastName": "Doe",
  "email": "Jane.Doe@example.com",
  "phone": "+1 555 0100",
  "currentTitle": "Software Engineer",
  "currentCompany": "Example Co",
  "location": "Toronto",
  "experienceYears": 5.5,
  "noticePeriodDays": 30,
  "status": "SCREENING",
  "notes": "Interested in backend roles",
  "skills": ["Java", "Spring"]
}
```

`status` is optional on create and defaults to `NEW`. `version` is accepted in
the request record but ignored during creation.

### Example update request

PUT uses the same request DTO, but `version` is required. Include the version
received in the last response:

```json
{
  "version": 0,
  "firstName": "Jane",
  "lastName": "Doe",
  "email": "jane.doe@example.com",
  "currentTitle": "Senior Software Engineer",
  "skills": ["Java", "Kotlin"]
}
```

The update is a full request-body update, not a PATCH. `firstName`, `lastName`,
and `email` are required. Optional scalar fields may be omitted or null, which
clears those values. If `status` is omitted or null, the existing status is
preserved. If `skills` is omitted or null, the candidate's skills are cleared.
The supplied version is compared with the stored version; a mismatch returns
`409 Conflict`.

### Response fields

`CandidateResponse` includes:

- `id`, `firstName`, `lastName`, `email`
- `phone`, `currentTitle`, `currentCompany`, `location`
- `experienceYears`, `noticePeriodDays`, `status`, `notes`
- `skills` as an alphabetically sorted list of normalized names
- `createdAt`, `updatedAt`, `version`

The internal `deleted` flag and JPA entities are not exposed in the response.
Status values are represented as enum names in JSON, for example
`"SCREENING"`; they are not ordinals.

## Request validation and normalization

`CandidateRequest` is a Java record. Its Bean Validation constraints are:

| Field | Rules |
|---|---|
| `firstName` | Required and non-blank; at most 100 characters |
| `lastName` | Required and non-blank; at most 100 characters |
| `email` | Required and non-blank; valid email format; at most 254 characters |
| `phone` | At most 30 characters |
| `currentTitle` | At most 150 characters |
| `currentCompany` | At most 150 characters |
| `location` | At most 150 characters |
| `experienceYears` | At least 0; at most 3 integer digits and 1 fractional digit |
| `noticePeriodDays` | Zero or greater |
| `status` | A `CandidateStatus` enum value, or omitted/null |
| `notes` | At most 4000 characters |
| `skills` | At most 50 entries; each entry must be non-blank and 1–50 characters |
| `version` | Required for PUT through the `OnUpdate` validation group; not required for POST |

The `OnUpdate` group extends Bean Validation's `Default` group. PUT validates
using `OnUpdate`, so the version constraint and all normal request constraints
run. POST validates using the default group, so version is not required.

Email whitespace is stripped in the record constructor before validation.
`CandidateMapper` then trims and lowercases email using `Locale.ROOT`.
Skills are trimmed, lowercased, and deduplicated before persistence. `Skill`
also normalizes its name in its constructor, and its name is its stable,
normalized identity for equality and hash-code comparisons.

## Domain and persistence

### Candidate

The `candidate` table stores:

- Generated `Long` id
- Required first name, last name, and unique email
- Optional phone, current title, current company, location, and notes
- Optional decimal years of experience and notice period days
- Status, stored by enum name and defaulted to `NEW`
- Creation and last-update timestamps populated through Spring Data JPA auditing
- `version`, used by JPA optimistic locking
- `deleted`, used for soft deletion

`CandidateRepository` extends `JpaRepository<Candidate, Long>` and
`JpaSpecificationExecutor<Candidate>`.

### Skills

The `skill` table stores unique normalized lowercase names. Candidates and
skills have a many-to-many relationship through `candidate_skill`. Skills are
shared reference data: the candidate relationship has no cascade, so the
service resolves existing skills by name and persists missing skills through
`SkillRepository`.

On candidate updates, `Candidate.replaceSkills(...)` mutates the existing JPA
collection by clearing and re-adding the resolved skills. This keeps Hibernate's
managed collection in place while updating the join table.

### Flyway schema

`src/main/resources/db/migration/V1__init.sql` creates the candidate, skill,
and join tables and their constraints and indexes. Candidate indexes cover
last name, status, location, and experience years; unique constraints cover
candidate email and skill name. The join table has a composite primary key and
foreign keys to both parent tables.

Flyway is the sole schema manager. Hibernate uses `ddl-auto: validate`, so it
checks the entity mappings against the migrated schema instead of generating
or altering tables. The current migration is intended for H2 and PostgreSQL,
but it has only been run on H2 (no PostgreSQL configuration exists yet); the
identity column or timestamp type may need a vendor-specific migration for
MySQL.

The migration does not currently have a CHECK constraint for the status
values. A comment in V1 documents the H2 issue that prompted its omission.
The Java enum and `@Enumerated(EnumType.STRING)` provide the application's
status type restriction; database-level enforcement should be revisited when
the noted H2 issue is resolved.

### Soft deletion and email uniqueness

Delete calls `Candidate.markDeleted()` and saves the candidate; it never
physically deletes the database row. Hibernate's
`@SQLRestriction("deleted = FALSE")` hides deleted candidates from
Hibernate-generated reads, including repository lookups and specifications.
It does not automatically filter native SQL, which must apply its own
condition when needed.

The unique email constraint covers all candidate rows, including deleted
ones. A deleted candidate's email therefore remains reserved. To avoid a
misleading result from queries that cannot see deleted rows,
`CandidateRepository` has native email-count queries that bypass the
restriction; the service uses these for create and update duplicate checks.
The unique constraint remains the final protection against a concurrent
duplicate insert. There is no restore endpoint.

### Optimistic locking

Each candidate has a JPA `@Version` field. The client must send the version it
last read when updating. The service checks that version before applying
changes; Hibernate also includes the version in the update statement, which
detects a concurrent change after the service's initial comparison. Either
stale-version path is translated to `409 Conflict`.

## Service and transaction behavior

- `create` checks email uniqueness including soft-deleted rows, resolves
  skills, creates and flushes the candidate, then maps it to a response.
- `getById` is read-only transactional and maps the response before the
  transaction closes. This is needed because skills are lazy-loaded and
  Open Session in View is disabled.
- `update` validates version and email uniqueness, applies scalar fields,
  replaces skills, flushes changes, and maps the response in the transaction.
- `delete` marks and saves the candidate as deleted; normal reads then treat it
  as not found.

Writes use `@Transactional`; reads use `@Transactional(readOnly = true)`.
Create and update use `saveAndFlush` so database-generated/version/audited
values are available while constructing the response. The application sets
`hibernate.default_batch_fetch_size` to `50` to batch-fetch lazy associations.

Application service logs use candidate IDs and do not log email or phone
values. The data-integrity handler avoids logging the database exception
message because it may contain submitted values.

## Error responses

`GlobalExceptionHandler` is a `@RestControllerAdvice` returning RFC 7807
`ProblemDetail` for application errors:

| Condition | HTTP status | Response behavior |
|---|---:|---|
| Bean validation failure | 400 | Problem detail with title and an `errors` list of field/message pairs |
| Malformed JSON | 400 | Generic malformed-request detail |
| Unknown enum value | 400 | Identifies the field and lists allowed enum values |
| Candidate not found (including soft-deleted) | 404 | Candidate-not-found problem |
| Duplicate email | 409 | Duplicate-email problem |
| Optimistic locking conflict | 409 | Asks the client to reload and retry |
| Database integrity conflict | 409 | Generic conflict detail, not raw database text |
| Unexpected exception | 500 | Generic detail; stack trace is logged server-side, not returned |

The handler extends Spring MVC's `ResponseEntityExceptionHandler` to customize
validation and unreadable-body errors while retaining the framework's handling
for other MVC exceptions. Spring's Jackson 3 exception type is used for
invalid enum values.

## Configuration and running

`src/main/resources/application.yml` configures:

- Application name `candidates`
- File-backed H2 at `jdbc:h2:file:./data/candidatesdb`, username `sa`, with no
  configured password
- Flyway enabled
- Hibernate schema validation and `open-in-view: false`
- Hibernate default batch fetch size of 50
- H2 console disabled
- Actuator web exposure for `health` and `info` (`/actuator/info` currently
  returns `{}`)
- Log level pattern `%5p [requestId=%X{requestId:-}]`, so every log line shows
  the current request ID (empty outside requests)

`src/main/resources/application-demo.yml` only enables the H2 console
(`/h2-console`). The `demo` profile also activates `DemoDataLoader`, an
`ApplicationRunner` that creates 30 fictional candidates through
`CandidateService` in one transaction. It skips loading when any candidate
row exists, including soft-deleted rows (`countIncludingDeleted()`), so it is
idempotent. The demo profile uses the same file database as the default
profile.

### OpenAPI

`OpenApiConfig` defines the API metadata for springdoc. Swagger UI is at
`/swagger-ui.html` (redirecting to `/swagger-ui/index.html`), and the OpenAPI
document is at `/v3/api-docs`. Both are enabled in every profile.

### Request IDs and access logging

`RequestIdFilter` sets an `X-Request-Id` response header on every request.
A supplied ID is accepted only if it has 1–64 letters, digits, underscores or
hyphens; otherwise a UUID is generated. The ID is put in the MDC as
`requestId` and removed when the request finishes. One INFO access line logs
method, path, status and duration. Query strings, bodies and other headers
are never logged. Actuator and Swagger/OpenAPI paths get request IDs but no
access line.

### Web UI

`index.html`, `app.js` and `styles.css` in `src/main/resources/static` form a
framework-free single-page UI using only relative API URLs. `HomeController`
serves `index.html` at `GET /`. API data is written with `textContent` and DOM
APIs, not `innerHTML`. The UI provides:

- Debounced search and filters, including status checkboxes, skills with
  ALL/ANY, and experience and notice-period bounds.
- Sort, page size and Previous/Next paging.
- Create/edit dialogs that keep the `version`.
- Field-level validation and duplicate-email errors.
- A Reload action for concurrent-modification conflicts.
- Confirmed soft delete.

See `README.md` for run commands and URLs.

## Tests and verification

Run the full verification:

```shell
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

Stop any running instance first: `CandidatesApplicationTests` uses the
configured file database, which H2 locks while the application runs.

The latest full verification completed successfully with 146 tests and no
failures or errors:

| Test class | Type | Tests |
|---|---|---:|
| `CandidateRepositoryTest` | `@DataJpaTest`, in-memory H2, Flyway schema, Hibernate validation | 9 |
| `CandidateSpecificationsTest` | `@DataJpaTest` for filters, subqueries, sorting, and paging | 31 |
| `CandidateServiceTest` | Mockito service tests | 11 |
| `CandidateControllerTest` | Boot 4 `@WebMvcTest` with `@MockitoBean` service | 58 |
| `CandidateApiIntegrationTest` | Full Spring Boot / MockMvc lifecycle on in-memory H2 | 2 |
| `CandidateMapperTest` | Plain JUnit mapper behavior tests | 2 |
| `CandidatesApplicationTests` | Application context smoke test (file H2) | 1 |
| `DemoDataLoaderTest` | `@SpringBootTest` with the `demo` profile: loads into an empty database, then does nothing on a second run | 1 |
| `OpenApiIntegrationTest` | `@SpringBootTest` (random port): Swagger UI redirect, OpenAPI content, H2 console disabled without `demo` | 4 |
| `RequestIdFilterTest` | Plain JUnit filter tests: ID validation/generation, MDC cleanup, access-log content and exclusions | 22 |
| `SkillTest` | Plain JUnit skill normalization and equality tests | 4 |
| `StaticUiTest` | `@SpringBootTest` with MockMvc: `GET /` returns the HTML page | 1 |
| **Total** | | **146** |

`src/test/browser/candidate-ui.test.js` is an optional Node.js browser test
(`node --test src\test\browser\candidate-ui.test.js`, needs Chrome or Edge).
It is not part of the Maven build.

Repository tests cover Flyway migration application, candidate/skill
persistence, enum string storage, unique email, soft-delete visibility and
retained skills, reserved email, auditing, and optimistic locking. Controller
tests cover CRUD and search responses, query binding and validation, malformed
JSON, unknown enums, sort whitelisting, 404/409/500 handling, and preventing
internal details from leaking to clients. Specification tests cover each
filter, combinations, literal wildcard escaping, ANY/ALL skill matching,
deleted-row exclusion, and stable sorting/paging. The integration tests cover
the CRUD lifecycle and a search scenario through the real application stack.

In Spring Boot 4, invalid `@ModelAttribute` query parameters are reported as
`MethodArgumentNotValidException`. The existing handler returns status 400
with `application/problem+json` and an `errors` array containing `{field,
message}` objects. Binding failures are sanitized as "Invalid value for this
parameter"; rejected input is not echoed.

Spring Boot 4 test imports use its `org.springframework.boot...test...`
packages, and `@MockitoBean` comes from
`org.springframework.test.context.bean.override.mockito`; Boot 3 examples
using `@MockBean` should not be copied without checking compatibility.

## Not implemented yet

- Separate skill management endpoints
- Restore of a soft-deleted candidate
- Authentication and authorization
- Production database configuration (no PostgreSQL profile or docker-compose)
- A MySQL-specific migration
- PATCH (partial) updates
- A database CHECK constraint on `status` (blocked by the H2 issue noted in
  `V1__init.sql`)
