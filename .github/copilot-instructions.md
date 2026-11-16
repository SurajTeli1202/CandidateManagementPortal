# Project: Candidate Management (learning project)

A small Spring Boot REST application to manage and search candidate records.

## Stack
- Java 21, Spring Boot 4.1.x (Spring Framework 7, Jackson 3), Maven
- Spring Web, Spring Data JPA, Bean Validation, Flyway, H2 (file mode by default), Actuator
- Tests: JUnit 5, Mockito, AssertJ, @DataJpaTest, @WebMvcTest, @SpringBootTest

## IMPORTANT: Spring Boot 4 specifics
This project uses Spring Boot 4, not 3.x. Many examples online use Boot 3 APIs.
Do not assume Boot 3 imports, annotations or starter names. For example, use
@MockitoBean instead of @MockBean. If unsure whether an API exists in Boot 4,
say so and tell me what to verify rather than guessing.

## Architecture
- Layered, packaged by feature: controller -> service -> repository.
- Controllers: HTTP concerns only, no business logic.
- Services: business rules and @Transactional boundaries.
- Never expose JPA entities through the API; use DTOs (Java records).
- Dynamic search/filtering with Spring Data JPA Specifications.
- Schema is managed only by Flyway migrations (src/main/resources/db/migration).
  Hibernate ddl-auto must be `validate` or `none`.

## Conventions
- Constructor injection only, no field injection.
- Base package: com.suraj.candidates (adjust to the project's actual package).
- API base path: /api/v1/candidates. Return proper status codes
  (201 + Location on create, 204 on delete, 404, 400, 409).
- Errors: one @RestControllerAdvice returning RFC 7807 ProblemDetail.
- Logging: SLF4J. Never log PII (email, phone).
- Keep methods small and readable. No unnecessary abstractions.
- Every behaviour change comes with tests.
- Deletion is soft only. Never call repository delete*/deleteAll* methods; use Candidate.markDeleted() and save.

## How to work with me
- Work in small steps. Do one step at a time and stop so I can review.
- Before writing code, briefly explain the approach and any trade-offs.
- After writing code, list the files you changed and anything I should double-check.