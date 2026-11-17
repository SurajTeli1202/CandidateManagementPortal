# AI usage

This document describes how I used AI tools while building the Candidate
Management application: where, how, how I validated the output, what I changed,
and where I chose not to follow a recommendation. For the technical design see
[`docs/DESIGN.md`](docs/DESIGN.md); for setup see [`README.md`](README.md).

## Tools and where I used them

| Phase | Tool | What I used it for |
|---|---|---|
| Design | Claude (chat) | Turning the requirements into a design: stack, data model, API, error handling, soft-delete trade-offs and a testing plan |
| Prompting | Claude | Drafting the step-by-step prompts I gave Copilot, and understanding the trade-offs behind the questions Copilot asked me |
| Implementation | GitHub Copilot (agent chat in IntelliJ) | Migration, entities, repository, DTOs, service, controller, error handler, Specifications, tests, OpenAPI, request-ID filter, demo data, UI, README and implementation notes |
| Review | Claude and Copilot | Reviewing Copilot's entities, tests and search code, and a read-only audit of the whole project |

## How I used it

I worked in small steps. A `.github/copilot-instructions.md` file set the
architecture rules for the agent: layering, DTOs instead of entities, a
Flyway-only schema, soft delete, ProblemDetail errors, no personal data in
logs, and Spring Boot 4 APIs (many examples online are for Boot 3).

Each prompt covered one step ("do this step only"), asked Copilot for a short
plan before writing code, and prohibited changes outside the step (for example
no changes to the POM or migrations). When Copilot asked clarifying questions,
such as how to handle a unique email with soft delete, I made the decision
after weighing the options with Claude. I reviewed the diff and committed after
each step.

## How I validated the output

- I ran `./mvnw clean verify` after each step. The final hardening run reported
  162 tests with zero failures, errors or skips.
- Persistence tests run against the Flyway-created schema with
  `ddl-auto=validate`, and they check database state with native SQL (for
  example that a soft-deleted row and its skill links still exist), not only
  Java objects.
- I read the entities, `Skill`, the tests and the search Specifications myself
  and had them reviewed separately.
- I ran a read-only audit of the whole project, which found real issues (below).
- I ran the application with the demo profile and checked the data and the UI
  through Swagger UI and the browser.

## Changes I made to AI output

- **Soft delete.** Copilot first used `@SQLDelete`, and I accepted it. Its own
  test then showed that Hibernate deletes the `candidate_skill` rows before the
  update, so skill links were lost. I replaced it with an explicit
  `markDeleted()` in the service.
- **Search.** My review of the search code found unbounded `q`, `skills` and
  `location` inputs and database-dependent null ordering. I added input limits
  and `nullsLast()` sorting.
- **Audit findings I fixed.** Email addresses could appear in Hibernate error
  logs and in access logs, a very large `page` value caused a 500 response, and
  the smoke test opened the developer's database file. All three were fixed
  and covered by tests.
- **UI.** The first version did not look good. Copilot redesigned it into a
  compact dashboard, and I asked for the status filter to be checkboxes.
- **Documentation.** Copilot's implementation notes duplicated about 1,500
  lines of source code. I had the source removed.
- **Tests.** I trimmed a long list of suggested tests to the ones that protect
  real behavior, instead of adding tests for getters and plain mapping.

## Where I did not follow AI recommendations

- **Soft-delete side effect.** For the lost skill links, Copilot's recommended
  option was to accept the loss and document it. I chose to keep the links so a
  future "restore" feature stays possible, which meant a different mechanism.
- **Audit items I deliberately left out** (not needed at this scope, and listed
  in `docs/DESIGN.md`): authentication and authorization, a request-body size
  limit, race-free duplicate-email and skill-creation messages, a Maven wrapper
  checksum, running the browser test inside Maven, and query-plan
  measurements.
- **Frontend framework.** I kept plain HTML and JavaScript instead of adding a
  framework, because I want to be able to explain and change every line of the
  UI myself.

## Recommendations I accepted after weighing alternatives

- Keep a global unique email, so a deleted candidate's email stays reserved,
  and return a clear 409 instead of a database error.
- Drop the database CHECK on status because of an H2 bug, enforce it in the
  application, and leave a TODO in the migration.
- Use `IDENTITY` ids for portability between H2 and PostgreSQL, and Spring Data
  auditing for created and updated timestamps.

## Limits

I reviewed all AI-generated code and documentation before committing. The
application has no authentication, and passing tests and an AI review do not
make it production-ready.
