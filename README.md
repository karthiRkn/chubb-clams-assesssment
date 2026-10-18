# Chubb APAC Claims Management Backend

A Spring Boot REST backend for motor and property insurance claims across APAC markets.

## 1. Overview and business problem

Claims handling involves three groups with different needs:

- **Claimants** submit a claim and track it with a reference number.
- **Claims officers** pick up submitted claims, review them, ask the claimant for more information, and settle or reject.
- **Managers** need to see how many claims are outstanding and a rough estimate of the money involved, per currency.

This service gives each group an API for that, enforces the claim lifecycle on the server, and keeps a record in a database.

**Scope:** it is a time-boxed assessment project, built as a single modular monolith. It is not production-ready (see section 14).

## 2. Implemented features

All 10 endpoints are implemented and have automated tests.

| # | Method and path | Purpose |
|---|---|---|
| 1 | `POST /api/v1/claims` | Create a claim; the server generates `claimId` |
| 2 | `GET /api/v1/claims/{claimId}` | Track a claim |
| 3 | `GET /api/v1/officer/claims` | Officer workload with filters and paging |
| 4 | `POST /api/v1/officer/claims/{claimId}/assign` | Assign an officer |
| 5 | `POST /api/v1/officer/claims/{claimId}/review` | Start (or explicitly resume) review |
| 6 | `POST /api/v1/officer/claims/{claimId}/information-requests` | Ask the claimant for information |
| 7 | `GET /api/v1/claims/{claimId}/information-requests` | List a claim's information requests |
| 8 | `POST /api/v1/claims/{claimId}/information` | Claimant answers a request (text only) |
| 9 | `POST /api/v1/officer/claims/{claimId}/decision` | Settle or reject |
| 10 | `GET /api/v1/management/summary` | Outstanding claims and liability per currency |

Request and response examples for every endpoint, with positive and negative cases, are in **[API_TEST_CASES.md](API_TEST_CASES.md)**.

### Key rules

- **Two IDs.** `id` is the internal `BIGINT` identity key. `claimId` (for example `CLM-2026-000101`) is the public reference. It comes from the database sequence `claim_number_seq` (starts at 101, atomic, safe under concurrency, not `count + 1`) and has a unique constraint. Every `{claimId}` in a path is the public ID; the numeric `id` in a path returns 404. Information requests get their own ID (`IR-2026-000001`, from `information_request_number_seq`) and carry the parent `claimId`. Sequence numbers are not reused after a rollback, so gaps are possible.
- **Lifecycle** (enforced in the `Claim` class, not in controllers):

  ```text
  SUBMITTED --review--> UNDER_REVIEW --information-request--> AWAITING_INFORMATION
                            ^                                          |
                            +------ last OPEN request answered --------+   (automatic)
  UNDER_REVIEW --decision--> SETTLED | REJECTED   (terminal)
  ```

  Assign works in any non-terminal state and does not change the status. Review by an officer other than the assigned one is a 409. Any other transition is a 409 `INVALID_CLAIM_TRANSITION`.
- **After information is answered the claim resumes automatically** to `UNDER_REVIEW` once no `OPEN` request remains. The officer does not need to call `/review` again.
- **Decisions** are only allowed from `UNDER_REVIEW`. `SETTLED` needs a `settledAmount` that is positive and not above the claimed amount. `REJECTED` must not carry one. The decision currency must equal the claim currency. A second decision is a 409 and the first is kept.
- **Money** is `BigDecimal` with 2 decimals and an explicit 3-letter currency. Currencies are never added together.
- **Management summary** counts non-terminal claims only, groups liability by currency, and labels it "Simple estimate ... Not an actuarial reserve".
- **Officer search** filters (`status`, `market`, `claimType`, `assignedOfficerId`) and paging run in SQL. `size` defaults to 20 and is capped at 100. `market` matching is exact and case-sensitive.
- **Errors** share one JSON shape: `{timestamp, status, error, code, message, path}`. Status codes: 400 invalid input, 404 unknown claim or request, 405 wrong method, 409 invalid transition or duplicate action, 415 wrong content type.

## 3. Architecture

A single Spring Boot application organised by feature, with thin controllers, services for workflow, and an entity that owns the lifecycle rules.

```text
HTTP -> Controller (validation, DTOs) -> Service (transactions, row lock) -> Claim entity (transition rules) -> Repository -> DB
                                  \-> GlobalExceptionHandler (uniform error body)
```

- JPA entities are never returned directly; each endpoint uses a DTO record.
- State-changing calls load the claim with a pessimistic row lock (`SELECT ... FOR UPDATE`) so concurrent transitions on one claim are serialised.
- Flyway owns the schema (`V1__create_claims_schema.sql`); Hibernate only validates it (`ddl-auto=validate`).
- Indexes in V1: `(status, market)`, `assigned_officer_id`, `claim_type`, `created_at` for the officer workload query; `(status, currency)` for the management summary; `information_requests (claim_pk, status)` for listing and open-request checks.

## 4. Technology stack and prerequisites

| Item | Version / detail |
|---|---|
| Java | 17 (verified with OpenLogic OpenJDK 17.0.16) |
| Spring Boot | 3.3.5 (Web, Data JPA, Validation) |
| Build | Maven, via the Maven Wrapper (`mvnw.cmd`, downloads Maven 3.9.9 on first use) |
| Migrations | Flyway (`flyway-core`, `flyway-database-postgresql`) |
| Databases | H2 (default, in memory) and PostgreSQL (configurable, not verified) |
| API docs | `springdoc-openapi-starter-webmvc-ui` 2.6.0 |
| Tests | JUnit 5, Spring Boot Test, MockMvc, AssertJ |

Prerequisites: a JDK 17 and internet access the first time (to download Maven and dependencies from Maven Central). You do **not** need to install Maven, PostgreSQL or Kafka to run the app or the tests.

## 5. Project structure

```text
.
├── README.md                 this file
├── API_TEST_CASES.md         test cases for all endpoints
├── CLAUDE.md                 development rules for this repository
├── .claude/ai-journal.md     AI working journal
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/     Maven Wrapper
└── src/
    ├── main/java/com/chubb/claims/
    │   ├── ClaimsApplication.java
    │   ├── claim/            Claim entity, service, repository, create/get endpoints, ReferenceGenerator
    │   ├── officer/          workload search, assign, review, decision
    │   ├── information/      information requests and answers
    │   ├── management/       management summary
    │   └── common/           ApiException, ApiError, GlobalExceptionHandler
    ├── main/resources/
    │   ├── application.properties
    │   └── db/migration/V1__create_claims_schema.sql
    └── test/
        ├── java/com/chubb/claims/   IntegrationTestBase + 5 test classes (+ ClaimDomainTest)
        └── resources/application-test.properties
```

## 6. Run the application

The port is **8181** (`server.port=8181` in `src/main/resources/application.properties`).

### Windows (PowerShell or cmd), from the project root

```bat
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

Leave that terminal open. Stop the app with `Ctrl+C`. If port 8181 is busy, change `server.port` in `application.properties`.

### IntelliJ IDEA

1. **File > Open** and choose the project folder; let it import the Maven project.
2. Set **File > Project Structure > Project SDK** to JDK 17.
3. Open `src/main/java/com/chubb/claims/ClaimsApplication.java` and click the green run arrow next to `main`.
4. To run tests, right-click `src/test/java` and choose **Run 'All Tests'**, or use the Maven tool window (**Lifecycle > test**).

### URLs

- API base: `http://localhost:8181`
- Swagger UI: `http://localhost:8181/swagger-ui/index.html` (`/swagger-ui.html` redirects there)
- OpenAPI JSON: `http://localhost:8181/v3/api-docs`

## 7. Database: H2 (default)

With no environment variables set, the app uses in-memory H2:

```properties
spring.datasource.url=${DB_URL:jdbc:h2:mem:claimsdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}
spring.datasource.username=${DB_USERNAME:sa}
spring.datasource.password=${DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
```

- Flyway creates the tables and sequences at startup; Hibernate then validates them.
- **Data lives in memory and is lost when the app stops.** There is nothing to install or create.
- H2 is a `runtime`-scope dependency in `pom.xml`. (It was previously `test` scope, which made `spring-boot:run` fail with `Cannot load driver class: org.h2.Driver`.)
- `MODE=PostgreSQL` only approximates PostgreSQL. It is a local convenience, not proof the app works on PostgreSQL.
- The H2 console is not enabled.

## 8. Database: PostgreSQL (configured, not verified)

The same properties switch to PostgreSQL when `DB_URL` is set. The PostgreSQL driver and Flyway PostgreSQL module are already dependencies.

1. Install PostgreSQL and create the database before starting the app (Flyway does not create it): `createdb claims_db` (default port 5432).
2. Set the variables in your own shell. Do not commit them or paste them into chat:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://localhost:5432/claims_db"
   $env:DB_USERNAME = "<your-local-db-username>"
   $env:DB_PASSWORD = "<your-local-db-password>"
   ```
3. Run `.\mvnw.cmd spring-boot:run`. Flyway should apply `V1__create_claims_schema.sql`.

**Limitation: PostgreSQL integration has never been run.** PostgreSQL is not installed on the development machine, so these were not exercised against a real server: the migration, the `nextval(...)` sequence calls, the `SELECT ... FOR UPDATE` locking, and the `TIMESTAMP WITH TIME ZONE` columns. Treat PostgreSQL support as untested until someone runs the app and the smoke flow against it.

## 9. Kafka

**Kafka is not implemented.** There is no producer, consumer, topic, dependency or configuration for it, and nothing in this repository has been tested against a broker.

Possible future use (a proposal only): publish a `ClaimSubmitted` event when a claim is created so other systems, such as notifications or analytics, can react. That would need an event schema, an idempotent consumer, a failure strategy (for example an outbox or retries), and verification against a real running broker before it could be called working.

## 10. Tests

Run all tests from the project root:

```bat
.\mvnw.cmd clean test
```

Tests use the full Spring context with H2 (`@ActiveProfiles("test")`, `src/test/resources/application-test.properties`) and MockMvc. The database is emptied before each test. No PostgreSQL or Kafka is needed.

Reports: `target/surefire-reports/` contains `TEST-*.xml` and `*.txt` per test class. The summary is also printed at the end of the Maven run.

| Test class | Covers |
|---|---|
| `ClaimCreationTest` | Create, `claimId` generation/uniqueness/stability, retrieval, 404, validation |
| `ClaimDomainTest` | Lifecycle transitions and terminal states (no HTTP) |
| `ClaimWorkflowTest` | Assign, review, information requests and answers, decisions, invalid transitions |
| `OfficerSearchTest` | Filters, paging, invalid query parameters |
| `ManagementSummaryTest` | Terminal claims excluded, liability per currency |
| `EndpointContractTest` | Malformed JSON, empty body, missing fields, wrong content type/method (parameterised over all POST endpoints), response shapes, summary edge cases |

The endpoint-by-endpoint coverage table is in section 12 of `API_TEST_CASES.md`.

## 11. Manual testing and demo flow

### Swagger UI

Open `http://localhost:8181/swagger-ui/index.html` and follow the 12-step flow in section 11 of `API_TEST_CASES.md`.

### PowerShell

With the app running, copy `claimId` from the first response automatically as below:

```powershell
$b = 'http://localhost:8181'; $h = @{'Content-Type'='application/json'}
$c = Invoke-RestMethod -Method Post "$b/api/v1/claims" -Headers $h -Body '{"claimantName":"Mei Tan","claimantEmail":"mei.tan@example.com","market":"SG","claimType":"MOTOR","description":"Rear bumper damaged.","incidentDate":"2026-10-12","claimedAmount":2400.00,"currency":"SGD"}'
$id = $c.claimId
Invoke-RestMethod "$b/api/v1/claims/$id"
Invoke-RestMethod "$b/api/v1/officer/claims?status=SUBMITTED&market=SG"
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/assign" -Headers $h -Body '{"officerId":"OFF-1007"}'
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/review" -Headers $h -Body '{"officerId":"OFF-1007"}'
$ir = Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/information-requests" -Headers $h -Body '{"question":"Please provide the repair estimate.","requestedByOfficerId":"OFF-1007"}'
Invoke-RestMethod "$b/api/v1/claims/$id/information-requests"
Invoke-RestMethod -Method Post "$b/api/v1/claims/$id/information" -Headers $h -Body (@{informationRequestId=$ir.informationRequestId; answer='Estimate is 2,200 SGD.'} | ConvertTo-Json)
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/decision" -Headers $h -Body '{"decision":"SETTLED","decisionReason":"Estimate approved.","settledAmount":2200.00,"currency":"SGD","decidedByOfficerId":"OFF-1007"}'
Invoke-RestMethod "$b/api/v1/management/summary"
# a second decision must fail with 409:
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/decision" -Headers $h -Body '{"decision":"REJECTED","decisionReason":"x","currency":"SGD","decidedByOfficerId":"OFF-1007"}'
```

## 12. Build and test results

Recorded from real runs on 2026-10-18 (Windows 11, Java 17.0.16, Maven wrapper):

- `.\mvnw.cmd clean test`: **Tests run: 84, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS. H2 only. Per class: ClaimCreationTest 9, ClaimDomainTest 6, ClaimWorkflowTest 18, EndpointContractTest 43, ManagementSummaryTest 2, OfficerSearchTest 6.
- `.\mvnw.cmd spring-boot:run`: started in about 6 seconds, Flyway applied V1 on H2. Port 8181 was already used by another process during the final check, so the run below used `--server.port=8199` for a verification instance; the configured port is still 8181.
- **Demo flow run for real** (the PowerShell script in section 11, against that verification instance): create 201, get 200, search 200, assign 200, review 200, information request 201, list 200, answer 200 (claim back to `UNDER_REVIEW` automatically), settle 200, management summary 200 (`outstandingClaimCount` 0 afterwards), second decision **409 `INVALID_CLAIM_TRANSITION`**, unknown claim **404 `CLAIM_NOT_FOUND`**, `/swagger-ui/index.html` 200, `/v3/api-docs` lists 10 paths.
- **Not verified:** PostgreSQL (no installation), Kafka (not implemented).

If you change code, re-run the tests and update these numbers from the new output.

## 13. Assumptions

- `market` is a 2-letter upper-case code (for example `SG`, `HK`, `AU`) and `currency` a 3-letter upper-case code; neither is checked against a real list.
- The claimed amount may be any positive value with up to 2 decimals; the currency's own minor-unit rules are not applied.
- Officer IDs are free text (for example `OFF-1007`); there is no officer table.
- One information request can be open per claim at a time.

## 14. Known limitations

- **No authentication or authorization.** Anyone who can reach the port can call every endpoint, including the officer and management ones. Officer IDs in request bodies are trusted.
- **PostgreSQL integration is untested**, and the default H2 database loses all data on restart.
- **Kafka is not implemented.**
- **Additional information is text only.** There is no document or photo upload.
- Locking and concurrent transitions were only exercised on H2; concurrent *creation* is tested, concurrent transitions on one claim are not.
- No audit history of status changes beyond the current state and decision fields.
- No rate limiting, no pagination on the information-request list, and no API versioning beyond `/api/v1`.
- `market` filtering is case-sensitive.
