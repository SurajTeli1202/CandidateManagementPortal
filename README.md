# Candidate Management API

A learning project for a REST API to manage candidate records. The project is
being built in small steps; the current implementation focuses on the JPA
domain model and Flyway-managed database schema.

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
`./data/candidatesdb` by default. The H2 console is enabled in the development
configuration and is available at `http://localhost:8080/h2-console`; connect
with JDBC URL `jdbc:h2:file:./data/candidatesdb` and username `sa` (no
password).

The configured Actuator endpoints include:

- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/info`

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

The REST controllers, services, request/response DTOs, validation rules, and
search endpoints have not been implemented yet.

## Database notes

The schema is managed exclusively by Flyway. Hibernate is configured with
`ddl-auto: validate`; do not use Hibernate schema generation to modify it.
Add future schema changes as new Flyway migrations.

The current migration uses SQL that works with H2 and PostgreSQL. MySQL may
require a vendor-specific migration. Candidate email is unique across all
rows, including soft-deleted candidates, so a deleted candidate's email
remains reserved.
