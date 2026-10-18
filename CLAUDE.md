# CLAUDE.md - Development rules for the Chubb Claims Backend

Practical instructions for anyone (human or AI) changing this repository. The application is implemented; these rules keep it consistent, tested and honestly documented.

Documents: `README.md` (overview, setup, run, limits), `API_TEST_CASES.md` (endpoint contracts and test cases), `.claude/ai-journal.md` (working journal). They describe the **implemented** behaviour. If code and documents disagree, fix the document or the code in the same change.

## 1. Build, run, test (Windows)

Run from the project root with the Maven Wrapper (Java 17 required):

```bat
.\mvnw.cmd clean test            # all tests (H2, no database install needed)
.\mvnw.cmd spring-boot:run       # app on http://localhost:8181
```

- Port: **8181** (`server.port` in `src/main/resources/application.properties`).
- Swagger UI: `http://localhost:8181/swagger-ui/index.html`; OpenAPI JSON: `/v3/api-docs`.
- Test reports: `target/surefire-reports/`.
- If the wrapper cannot download dependencies, report the real error (network, proxy, permissions). Do not work around company controls.

## 2. Architecture and package conventions

- Java 17, Spring Boot 3.3.5, Maven, Spring Web, Spring Data JPA, Bean Validation, Flyway, springdoc-openapi. A single modular monolith, base package `com.chubb.claims`.
- Packages are **by feature**: `claim` (entity, service, repository, create/get/history, `ClaimStatusHistory`, `MarketPolicy`, `ReferenceGenerator`), `officer` (workload, assign, review, decision), `information`, `management`, `common` (`ApiException`, `ApiError`, `GlobalExceptionHandler`).
- Controllers stay thin: bind and validate the request, call a service, return a DTO.
- Business rules live in the service and in the `Claim` entity. **Lifecycle transitions are enforced inside `Claim`**, never only in a controller.
- Never return JPA entities from the API. Use DTO records (`CreateClaimRequest`, `ClaimResponse`, `OfficerDtos`, `InformationDtos`, `ManagementSummary`).
- State-changing workflows load the claim with `ClaimService.loadForUpdate` (pessimistic row lock) and run in a transaction.
- Keep it simple; do not add services, layers or frameworks that the task does not need.

## 3. IDs

- `id` is the internal `BIGINT` identity key. It is never used in public routes.
- `claimId` (`CLM-<year>-<6 digits>`) is the public business reference, generated server-side from the database sequence `claim_number_seq`. It is stable after creation and has a unique constraint. Clients must not supply it, and the create request has no such field.
- Information requests have their own ID (`IR-<year>-<6 digits>`, from `information_request_number_seq`) and refer to their parent claim by `claimId` in the API.
- Every `{claimId}` path variable is looked up by the business ID. Never query it against `id`.
- Do not generate IDs with `count + 1` or in-memory counters.

## 4. API and validation conventions

- All routes are under `/api/v1`. The 14 endpoints and their JSON are in `API_TEST_CASES.md`; do not change a contract without updating that file, `README.md` and the tests.
- Validate with Jakarta annotations on request records (`@Valid @RequestBody`). Query parameters are constrained with `@Validated` (`page >= 0`, `1 <= size <= 100`).
- Status codes: **400** invalid input, **404** unknown claim or information request, **405** wrong method, **409** invalid transition or duplicate action, **415** wrong content type. Anything unexpected is 500 with no stack trace in the body.
- Errors go through `GlobalExceptionHandler` and always use `{timestamp, status, error, code, message, path}`. Throw `ApiException` (with a stable `code`) for business failures. Register a handler for any new framework exception so it does not fall into the catch-all 500.
- Money is `BigDecimal`, scale 2, positive, with an explicit 3-letter currency. Never add amounts of different currencies.
- Assessment notes (optional `assessmentNotes` on review) go in the append-only `claim_assessments` table, are saved only after a successful review, and are never added to claimant-facing responses.
- Every change of assigned officer must append a `claim_assignment_history` row through `ClaimService.recordAssignment` (only when the assignee changes). Actors are unverified: never present them as authenticated identities.
- Every status change must append a `claim_status_history` row through `ClaimService.recordTransition` in the same transaction (capture the old status first). Assignment does not change status and is not recorded. Never update or delete history rows.
- Markets: the six APAC markets are not named in the brief, so never hard-code a list. `claims.markets.allowed` (empty by default) is the only switch.
- Officer search filters and paging must run in the database (`Specification` + `Pageable`), never in memory. Add an index only for a real filter or query pattern and say why in the migration comment.

## 5. Claim lifecycle and business rules (as implemented)

Statuses: `SUBMITTED`, `UNDER_REVIEW`, `AWAITING_INFORMATION`, `SETTLED`, `REJECTED`. `SETTLED` and `REJECTED` are terminal.

| Action | Allowed from | Result |
|---|---|---|
| assign | any non-terminal | status unchanged, officer set |
| review | `SUBMITTED`; `AWAITING_INFORMATION` only if no request is `OPEN` | `UNDER_REVIEW`; 409 `OFFICER_MISMATCH` if another officer is assigned |
| information request | `UNDER_REVIEW` | `AWAITING_INFORMATION`, request `OPEN` |
| answer | `AWAITING_INFORMATION` | request `ANSWERED`; claim **resumes to `UNDER_REVIEW` automatically** when no `OPEN` request remains |
| decision | `UNDER_REVIEW` | `SETTLED` or `REJECTED` |

Decision rules: `SETTLED` needs `settledAmount` > 0 and <= claimed amount; `REJECTED` must have none; decision currency must equal the claim currency; a second decision is a 409 and the first is kept. Management summary counts only non-terminal claims, groups liability by currency, and labels it a simple estimate, not an actuarial reserve. Additional information is text only; document upload does not exist and must not be claimed.

## 6. Database and configuration

- Flyway owns the schema: `src/main/resources/db/migration/V<n>__description.sql`. **Never edit an applied migration**; add a new version. Keep SQL valid for both PostgreSQL and H2 (PostgreSQL mode); avoid vendor-only syntax unless it is guarded.
- `spring.jpa.hibernate.ddl-auto=validate` must stay. Do not switch to `update`/`create` and do not remove validation to hide a mismatch.
- Default datasource is in-memory H2 (`sa`, empty password, `MODE=PostgreSQL`); H2 is a **runtime**-scope dependency. PostgreSQL is selected by setting `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` in the environment. The database `claims_db` must exist first.
- An opt-in `postgres` profile (`application-postgres.properties`, environment variables only) exists. PostgreSQL integration has **not** been verified. Do not say it works until the app and the demo flow have run against a real server.
- Kafka is **not implemented**. Add it only if asked, after the core works, with a real broker for verification.

## 7. Testing expectations

- Every endpoint has automated tests: success with the expected body, 400 (missing/invalid fields, malformed JSON), 404 (unknown claim and information request), and 409 where a lifecycle rule applies. Add the same for any new endpoint or rule, in the same change.
- Use MockMvc with the full Spring context on H2. Extend `IntegrationTestBase` (it empties the tables before each test) so tests are isolated and repeatable. No test may require PostgreSQL or Kafka.
- Concurrency tests (`ConcurrentTransitionTest`) run on H2 and must not be presented as PostgreSQL proof.
- Domain rules that need no HTTP go in `ClaimDomainTest`; cross-cutting HTTP contract checks go in `EndpointContractTest` (it is parameterised over the POST endpoints, so add new POST endpoints to its list).
- Fix real defects in the application. Never weaken or delete an assertion just to get green.
- Run `.\mvnw.cmd clean test` after every change that touches code, SQL or configuration, and read the Surefire reports.

## 8. Documentation maintenance

When behaviour, ports, configuration, endpoints or tests change, update in the same change:
- `README.md` (setup, ports, features, results, limitations),
- `API_TEST_CASES.md` (contracts, test cases, coverage table),
- this file if a rule changes,
- `.claude/ai-journal.md` with a new entry: the real prompt/goal, files changed, decisions (accepted, challenged, overridden, and why), commands run and their real results, unresolved issues.

Never write history, commits, prompts or results that did not happen.

## 9. Security and secrets

- Never commit passwords, tokens, connection strings with credentials, or `.env` files. Credentials come only from environment variables. Never ask for or accept real passwords in chat.
- `.gitignore` must keep excluding `target/`, `.idea/`, `*.iml`, `.env*`, `*.log` and local override property files.
- There is **no authentication or authorization** in this application. Do not describe it as secure, and do not claim access control exists. Adding it is future work.
- Do not install software, change database server settings, or bypass administrator, proxy or company security controls without explaining the need first.

## 10. Verify, do not invent

- Report real command output. State the actual test totals (run, failed, errors, skipped) from Maven and the Surefire files.
- If a command was not run, say it was not run. If something cannot be run (for example PostgreSQL is missing), say so as a blocker.
- Do not claim a feature, endpoint, build, test or integration works unless it was actually executed and observed.
- Keep a short list of known limitations in `README.md` up to date.
