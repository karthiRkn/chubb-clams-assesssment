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

All 13 endpoints are implemented and have automated tests.

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
| 11 | `GET /api/v1/management/officers` | Per-officer open workload and decision performance, per currency |
| 12 | `GET /api/v1/claims/{claimId}/history` | A claim's status history: from, to, when, who |
| 13 | `GET /api/v1/claims/{claimId}/assignments` | A claim's assignment history: previous officer, new officer, when, actor |

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
- **Officer metrics** (`GET /api/v1/management/officers`) are built from existing columns, with no new table: open workload by the current assignee and status, and decisions (settled or rejected, settled amount) by the deciding officer, all grouped by currency in SQL. There is no reassignment history and no time-based metric (the data model has no "review started" timestamp).
- **Management summary** counts non-terminal claims only, groups liability by currency, and labels it "Simple estimate ... Not an actuarial reserve".
- **Officer search** filters (`status`, `market`, `claimType`, `assignedOfficerId`, `assigned`) and paging run in SQL. `assigned=false` lists claims nobody has picked up (`assigned_officer_id IS NULL`); `assigned=true` lists claims that have an officer; a non-boolean value is a 400 `INVALID_PARAMETER`, and `assigned=false` together with `assignedOfficerId` is a 400 `CONFLICTING_FILTERS`. `size` defaults to 20 and is capped at 100. `market` matching is exact and case-sensitive.
- **Status history** (`claim_status_history`, migration V2) gets one row per status change: creation (`fromStatus` null, actor `CLAIMANT`), review, information request, answer-resume (actor `CLAIMANT`) and decision. The row is written in the same transaction as the change. Assigning an officer does not change status and is not recorded. `actor` is the officer ID from the request, or `CLAIMANT`; officer IDs are trusted free text, so this is not proof of identity.
- **Assignment history** (`claim_assignment_history`, migration V3) gets a row whenever the assigned officer actually changes: `assign`, and the automatic assignment when `review` is started on an unassigned claim. It stores previous officer (null the first time), new officer and time. `actor` is null for `assign`, because that request only names the new assignee, and is the reviewing officer for `review`. Re-assigning the same officer adds nothing.
- **Placeholder officer IDs:** the Swagger UI default `"string"` (any case) is rejected with 400 `VALIDATION_FAILED` on every officer ID field. This only catches the placeholder: officer IDs are still unauthenticated free text, so history actors are not verified identities.
- **Currency:** every claim has an explicit 3-letter currency (`NOT NULL`, format-checked, returned with every amount). INR is used in the examples; the API still accepts any well-formed code because the brief does not name supported currencies, and no exchange rates exist. Liability is the sum of **claimed** amounts of open claims, per currency, never added across currencies; it is neither the expected settlement nor a reserve.
- **Market validation** is configurable: `claims.markets.allowed` (comma separated) is empty by default, which keeps accepting any 2-letter upper-case code. When set, other codes are a 400 `INVALID_MARKET`. The brief mentions six markets but does not name them, so none is built in.
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
    │   ├── claim/            Claim entity, service, repository, create/get/history endpoints, status history, MarketPolicy, ReferenceGenerator
    │   ├── officer/          workload search, assign, review, decision
    │   ├── information/      information requests and answers
    │   ├── management/       management summary and per-officer metrics
    │   └── common/           ApiException, ApiError, GlobalExceptionHandler
    ├── main/resources/
    │   ├── application.properties
    │   ├── application-postgres.properties   opt-in PostgreSQL profile (not verified)
    │   └── db/migration/V1__..., V2__create_claim_status_history.sql, V3__create_claim_assignment_history.sql
    └── test/
        ├── java/com/chubb/claims/   IntegrationTestBase + 15 test classes
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

The same properties switch to PostgreSQL when `DB_URL` is set, and an opt-in `postgres` profile (`src/main/resources/application-postgres.properties`, environment variables only, no credentials) selects the PostgreSQL driver explicitly. The PostgreSQL driver and Flyway PostgreSQL module are already dependencies.

1. Install PostgreSQL and create the database before starting the app (Flyway does not create it): `createdb claims_db` (default port 5432).
2. Set the variables in your own shell. Do not commit them or paste them into chat:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://localhost:5432/claims_db"
   $env:DB_USERNAME = "<your-local-db-username>"
   $env:DB_PASSWORD = "<your-local-db-password>"
   ```
3. Run `.\mvnw.cmd spring-boot:run`. Flyway should apply `V1__create_claims_schema.sql`.

**Limitation: PostgreSQL integration has never been run.** No PostgreSQL server (and no psql or Docker) is available on the development machine, so these were not exercised against a real server: the profile, the migrations, the `nextval(...)` sequence calls, the `SELECT ... FOR UPDATE` locking, and the `TIMESTAMP WITH TIME ZONE` columns. Treat PostgreSQL support as untested until someone runs the app and the smoke flow against it.

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
| `OfficerMetricsTest` | Per-officer open workload and decisions, per-currency amounts, unassigned claims excluded |
| `UnassignedFilterTest` | `assigned=true/false`, combinations with other filters and paging, invalid value, conflicting filters |
| `MarketValidationTest`, `DefaultMarketTest` | Configurable market allow-list (test values only) and the empty-default behaviour |
| `StatusHistoryTest` | History rows for creation and the full lifecycle, actors, no row for assign or rejected transitions, 404, GET only |
| `ConcurrentTransitionTest` | Two competing decisions; decision vs information request; two reviewers racing on one claim; two answers to one request; stale actions after a decision (H2 only) |
| `AssignmentHistoryTest` | Assignment rows (previous/new/actor), same-officer no-op, review auto-assignment, rejected actions, 404, GET only |
| `PlaceholderActorTest` | Placeholder `"string"` rejected on every officer field; IDs containing the word still accepted |
| `CurrencyHandlingTest` | Currency persisted and returned, malformed currency rejected, per-currency liability with two currencies, currency mismatch, settled amount scale |
| `EndpointContractTest` | Malformed JSON, empty body, missing fields, wrong content type/method (parameterised over all POST endpoints), response shapes, summary edge cases |

The endpoint-by-endpoint coverage table is in section 12 of `API_TEST_CASES.md`.

## 11. Manual testing and demo flow

### Swagger UI

Open `http://localhost:8181/swagger-ui/index.html` and follow the 12-step flow in section 11 of `API_TEST_CASES.md`.

### PowerShell

With the app running, copy `claimId` from the first response automatically as below:

```powershell
$b = 'http://localhost:8181'; $h = @{'Content-Type'='application/json'}
$c = Invoke-RestMethod -Method Post "$b/api/v1/claims" -Headers $h -Body '{"claimantName":"Mei Tan","claimantEmail":"mei.tan@example.com","market":"SG","claimType":"MOTOR","description":"Rear bumper damaged.","incidentDate":"2026-10-12","claimedAmount":2400.00,"currency":"INR"}'
$id = $c.claimId
Invoke-RestMethod "$b/api/v1/claims/$id"
Invoke-RestMethod "$b/api/v1/officer/claims?status=SUBMITTED&market=SG"
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/assign" -Headers $h -Body '{"officerId":"OFF-1007"}'
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/review" -Headers $h -Body '{"officerId":"OFF-1007"}'
$ir = Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/information-requests" -Headers $h -Body '{"question":"Please provide the repair estimate.","requestedByOfficerId":"OFF-1007"}'
Invoke-RestMethod "$b/api/v1/claims/$id/information-requests"
Invoke-RestMethod -Method Post "$b/api/v1/claims/$id/information" -Headers $h -Body (@{informationRequestId=$ir.informationRequestId; answer='Estimate is 2,200 INR.'} | ConvertTo-Json)
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/decision" -Headers $h -Body '{"decision":"SETTLED","decisionReason":"Estimate approved.","settledAmount":2200.00,"currency":"INR","decidedByOfficerId":"OFF-1007"}'
Invoke-RestMethod "$b/api/v1/management/summary"
# a second decision must fail with 409:
Invoke-RestMethod -Method Post "$b/api/v1/officer/claims/$id/decision" -Headers $h -Body '{"decision":"REJECTED","decisionReason":"x","currency":"INR","decidedByOfficerId":"OFF-1007"}'
```

## 12. Build and test results

Recorded from real runs on 2026-10-18 (Windows 11, Java 17.0.16, Maven wrapper):

- `.\mvnw.cmd clean test` (run after adding assignment history, the placeholder-actor check, the currency tests and more concurrency tests): **Tests run: 132, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS. H2 only. Per class: AssignmentHistoryTest 10, ClaimCreationTest 9, ClaimDomainTest 6, ClaimWorkflowTest 18, ConcurrentTransitionTest 5, CurrencyHandlingTest 5, DefaultMarketTest 1, EndpointContractTest 43, ManagementSummaryTest 2, MarketValidationTest 3, OfficerMetricsTest 6, OfficerSearchTest 6, PlaceholderActorTest 4, StatusHistoryTest 6, UnassignedFilterTest 8. The demo flow and live checks below were run earlier, before these changes, and were not repeated.
- `.\mvnw.cmd spring-boot:run`: started in about 6 seconds, Flyway applied V1 on H2. Port 8181 was already used by another process during the final check, so the run below used `--server.port=8199` for a verification instance; the configured port is still 8181.
- **Demo flow run for real** (the PowerShell script in section 11, against that verification instance): create 201, get 200, search 200, assign 200, review 200, information request 201, list 200, answer 200 (claim back to `UNDER_REVIEW` automatically), settle 200, management summary 200 (`outstandingClaimCount` 0 afterwards), second decision **409 `INVALID_CLAIM_TRANSITION`**, unknown claim **404 `CLAIM_NOT_FOUND`**, `/swagger-ui/index.html` 200, `/v3/api-docs` lists 10 paths.
- **Officer metrics live check** (after endpoint 11 was added, verification instance on port 8199): three claims created, one assigned, one reviewed, one reviewed and settled by a second officer; `GET /api/v1/management/officers` returned 200 with the expected per-officer counts and per-currency amounts, and `/v3/api-docs` now includes `/api/v1/management/officers`. The "10 paths" in the demo-flow line above was true before this endpoint existed; there are now 11.
- **Live check after the history changes** (curl against a verification instance on port 8199): create 201; `assigned=false` listed the claim; `officerId":"string"` on assign returned 400; assign 200, review 200, information request, answer 200, settle 200, second decision 409; `GET .../history` returned five rows (SUBMITTED by `CLAIMANT`, UNDER_REVIEW by `OFF-1007`, AWAITING_INFORMATION by `OFF-1007`, UNDER_REVIEW by `CLAIMANT`, SETTLED by `OFF-1007`); `GET .../assignments` returned one row (null to `OFF-1007`, `actor` null); the summary note shows the new wording. The Swagger UI itself was not clicked through.
- **Not verified:** PostgreSQL (no installation), Kafka (not implemented).

If you change code, re-run the tests and update these numbers from the new output.

## 13. Assumptions

- `IND` is not supported: `market` is a 2-letter code in this design and the brief names no market, so adding `IND` would be a guess. If India is one of the six, its code goes into `claims.markets.allowed`; the six-market list is therefore still incomplete.
- `market` is a 2-letter upper-case code (for example `SG`, `HK`, `AU`) and `currency` a 3-letter upper-case code. `currency` is not checked against a list; `market` is checked only if `claims.markets.allowed` is set.
- The claimed amount may be any positive value with up to 2 decimals; the currency's own minor-unit rules are not applied.
- Officer IDs are free text (for example `OFF-1007`); there is no officer table.
- One information request can be open per claim at a time.

## 14. Known limitations

- **No authentication or authorization.** Anyone who can reach the port can call every endpoint, including the officer and management ones. Officer IDs in request bodies are trusted.
- **PostgreSQL integration is untested**, and the default H2 database loses all data on restart.
- **Kafka is not implemented.**
- **Additional information is text only.** There is no document or photo upload.
- Locking was only exercised on H2. Concurrent creation and two competing transitions on one claim are tested (`ConcurrentTransitionTest`), but never on PostgreSQL.
- Status history records status changes only; assignments have their own history (V3). The actor is a trusted, unauthenticated officer ID or `CLAIMANT`. There is no assessment record (notes, findings) on review.
- The six APAC markets are not named in the brief, so no market list is built in; set `claims.markets.allowed` once they are known.
- No rate limiting, no pagination on the information-request list, and no API versioning beyond `/api/v1`.
- `market` filtering is case-sensitive.
