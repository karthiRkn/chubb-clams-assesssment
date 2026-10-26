# Design Decisions

Why the Chubb APAC Claims backend is built the way it is, what was left out, and what would come next. This describes the code as it stands. Where something is only proposed or unverified, it says so.

Related documents: `README.md` (setup and run), `API_TEST_CASES.md` (endpoint contracts and test cases), `CLAUDE.md` (development rules), `.claude/ai-journal.md` (working journal).

The code was first built as a monolith, then split into four microservices (section 2), a fifth was added for authentication (section 8a), and in Phase 1 the five were reduced to **three business services**: User (the Auth Service), Claim and Assessment (section 2). Sections 3 to 8 describe the data model, lifecycle and rules; Phase 1 changed several of them (statuses, fields, who may see and do what), and section 2a lists every change with the alternative considered and the reason. The original monolith is still in the repository root, unmodified, as a reference baseline.

## 1. What the brief asks for, and what was prioritised

The brief (`design-i.docx` in the repository root) asks for a backend that lets claimants report an incident, track a claim and provide extra information, and lets claims staff pick up, review, settle or reject claims and see team workload and performance. It also wants managers to see outstanding liability. It says the brief is deliberately underspecified, that the candidate should decide what to build, and that the decisions are a significant part of the assessment.

Built, in priority order:
1. The claim lifecycle with rules enforced on the server (the part most likely to be wrong in a claims system).
2. Claimant, officer and management endpoints on top of it (18 claim-domain REST endpoints in the Claim and Assessment services, plus 8 in the User Service).
3. Efficient officer workload queries and per-currency liability.
4. Automated tests for every endpoint, on a database that needs no install.

Deliberately not built at first: authentication, document upload, notifications and Kafka. Assignment history and assessment notes were added later (section 3), authentication and roles later still (section 8a), and the three-service model, claimant edits, officer scoping and the approve-then-settle lifecycle in Phase 1 (section 2a). Document upload, notifications and Kafka are still not built. See section 9.

## 2. Modular monolith first, then microservices, then three

**History.** The application was first written as one Spring Boot application organised by feature packages, and this section used to argue for keeping it that way. It was then split into four microservices on request, with authentication as a fifth, and finally merged into three, again on the owner's instruction. The monolith stays in the repository root; the services are in `microservices/`.

### Why the monolith was the first choice (and what that cost to undo)

- **One transaction boundary.** Every state change touches one claim (and sometimes an information request or a note) and had to be atomic. In one process that was a single database transaction with a row lock.
- **Small domain.** A handful of tables and one lifecycle. Several services add network calls, deployment units, versioned contracts and distributed failure handling for no gain at this size.
- **Clear seams.** Controllers were thin, rules lived in the `Claim` entity and services, and DTOs separated the API from the entities. That is why every later change was mostly moving code rather than rewriting it.

### The decision now: three business services

| Service | Owns | Calls | What it does |
|---|---|---|---|
| **User** (the Auth Service) | `users` | nobody | Login, password hashing, JWTs, roles and the active flag. Credentials are the most sensitive data in the system; keeping them in one service means no other service can read a password hash. |
| **Claim** | `claims`, `claim_status_history`, `claim_assignment_history`, `claim_update_history` | User, for one check | The claim aggregate and its lifecycle rules, create/list/read/edit, assignment, history, and the **management workload and liability reports**. |
| **Assessment** | `claim_assessments`, `information_requests` | Claim | Review, the assessment history, information requests and answers, the approve/reject decision, settlement. |

**The rule that shaped everything: the lifecycle stays in one place.** Splitting the `Claim` entity's rules across services would turn every transition into a distributed workflow. The Claim Service exposes each transition as an internal endpoint (`review`, `request-information`, `resume-after-information`, `decision`, `settlement`) that does the work in one local transaction under the row lock. The Assessment Service never changes a claim's status; it asks. The decision's amount and currency rules need the claim's data, so they live there too.

**What the merge of five into three gave, and gave up.** The earlier argument for five services was data ownership (Information is its own record with its own writer, the claimant) and isolating the read-heavy reporting load (Reporting was stateless and could be scaled alone). The owner reversed that decision. What it removes:
- **Reporting into Claim.** The management reports are SQL `GROUP BY` over the `claims` table the Claim Service already owns, so the two `/internal/v1/claims/stats/*` routes, `ClaimStatsClient`, the copied status enum (a new status used to need a change in two services) and a service that was down whenever the Claim Service was down all disappear. The reports are now consistent with the writes in the same database.
- **Information into Assessment.** Asking for information is part of assessing a claim, and the review needs to know whether a request is still `OPEN`. That check is now a local query: the `/internal/.../information-requests/open` route and `InformationClient` are gone, and so is one hop in the review flow.
- Two fewer processes, ports, databases and Flyway histories.

What it gives up: reporting can no longer be scaled separately from claim writes (a heavy report shares the Claim Service's database connections; the queries use the `(status, currency)` index and have not been measured under load); the Assessment Service now serves the claimant-facing answer route as well as the officer routes, so one service has two audiences; and one service owns two tables with different lifecycles. The old argument was outweighed by the instruction and by fewer calls, not refuted.

**Dependencies are acyclic: Assessment to Claim, Claim to User.** The User Service calls nobody and nobody calls the Assessment Service. Claim calls User for exactly one question, answered by `GET /internal/v1/users/{username}` (role and active flag only): *is this assignee an active claims officer?* It is asked only when a manager or administrator assigns a claim to someone; an officer taking a claim for themselves needs no call because their role is in their token. The check runs **before** the claim row is locked, so no database lock is held over an HTTP call. If the User Service is down the assignment fails with 503 and nothing changes (tested with a real refused connection); an officer taking a claim for themselves, reads and the reports still work (also tested). One fact crosses a boundary the other way round: whether a claim has an `OPEN` request is now local to Assessment, which hands it to the Claim Service as a parameter of `review`.

**No foreign keys across services.** `information_requests.claim_id` and `claim_assessments.claim_id` hold the public `claimId` as text; the owning service is asked whether a claim exists. Within a service, history tables keep their foreign keys.

**Shared code is small on purpose.** `claims-common` has the error model, the exception handler, `DownstreamClient` and the security code (`Role`, `JwtUtil`, the filter chain, `AccessRules`, `AuthenticatedUser`, `ClaimVisibility`); no entities and no DTOs of another service. Each consumer declares only the fields it reads from another service and tolerates new ones; statuses and roles are read as text so a new value cannot break parsing.

**No gateway.** A client calls the service that owns an endpoint. Each service verifies the JWT itself, and nothing in the code reads an identity or role header (the only header read is `Authorization`), so there is no header a client could spoof; a gateway would add one more component without a requirement for it. The system tests contain a small path router (`SystemUnderTest.baseUrlFor`) that shows what its routing table would be.

### What the split costs, as found by building it

- **Atomicity is gone across services.** The monolith saved a note and moved the claim in one transaction. Now each service commits its own database and calls the owner before committing, so a refused or failed call undoes the local write (nothing is stored on failure; tested with real refused connections). Two windows remain that cannot be closed without idempotency keys or an outbox: the service's own database failing between the other service's success and its own commit, and a timeout that hides a success. They are listed in `README.md` section 3, with the recovery path for the one that has one (a claim stuck in `MORE_INFO_REQUIRED` is resumed by an officer's `review`).
- **No retries.** The calls change state, and repeating one that may already have succeeded is unsafe, so `DownstreamClient` never retries. An outage becomes a 503 immediately.
- **Availability is the product of the services'.** Review, decision, settlement and answering need Assessment and Claim; a manager's assignment needs Claim and User.
- **Locking moved.** The answer flow has no claim lock in its own database, so it locks the information request row instead (and holds it across the call to the Claim Service, so that "answered" and "claim resumed" stay together). Two simultaneous answers are serialised and the second is a 409; this is tested on H2 only.
- **Test cost:** a mocked-peer suite per service plus a system suite that boots all three services in one JVM and drives them over real HTTP. The original monolith's HTTP tests were replayed there, then deliberately adapted to the new model (section 8a); the monolith's own tests are untouched.

### When the split would be worth it, and when it would not

Worth it: different scaling or ownership per area, isolating the read-heavy reporting load, or regulatory separation of assessment data. None of those is measured here. If the aim were only a simpler system at this size, the monolith was the cheaper design, and it still passes its own tests.

## 2a. What Phase 1 changed, and why

| Change | Alternative considered | Why |
|---|---|---|
| Five services to three (Reporting into Claim, Information into Assessment) | Keep five | Owner's instruction; removes the stats routes, two clients, a copied enum and two processes (section 2) |
| No API gateway | Add one | Nothing reads identity headers and each service checks the JWT; one less component |
| Role `OFFICER` renamed `CLAIMS_OFFICER`; `ADMIN` kept | Drop ADMIN; keep the old name | Owner's role names; someone must create staff accounts, so ADMIN stays (and is allowed on every public route) |
| Six statuses: `MORE_INFO_REQUIRED` (was `AWAITING_INFORMATION`) and a new `APPROVED`, then `SETTLED` | Keep the one-step decision that settled | Owner specified them; approving and paying are different acts with different people and dates, and settlement can now be tracked |
| Claim fields `claimantId` (from the token), `policyNumber`, `incidentDescription`, `approvedAmount` | Keep `description` and `settledAmount` | Owner's field list; a migration renames the columns without losing data (section 3) |
| Claimants can list and correct their own claims (while `SUBMITTED`), with an edit history | No edit; or edit at any time | "Create, retrieve, list and update claims"; editing after an officer started would change what they reviewed |
| Officers see only unassigned claims and their own (`ClaimVisibility`) | Every officer sees everything | Owner's rule ("only claims they are authorised to handle"); one shared class so the services cannot drift |
| Officer identity comes from the token; an administrator acting for an officer must name one | Keep trusting officer IDs in bodies | Closes the "one officer can act as another" gap; the body field is optional for officers so contracts still work |
| Managers can assign; officers only to themselves; the assignee must be an active claims officer (User Service lookup) | Accept any text as the officer ID | Owner's rule; costs the Claim to User call and a 503 when it is down |
| `active` flag on users | Delete users | Keeps history and names intact; stops new logins only (no revocation) |
| No default JWT secret; `JWT_SECRET` required; `start-all.ps1` generates one per run | Keep the committed default | A secret in a file in a synced repository is public; costs one setting per IDE run configuration |
| Database credentials only from `*_DB_*` environment variables; Claim port back to 8181 | Leave the owner's hard-coded URL, user and password and port 8787 | Owner approved moving secrets out of source control; port 8181 is the documented default every other service points at |
| `GET /actuator/health` on each service | Nothing | A way to see that a service and its database are up; only `health` is exposed |
| An `APPROVED` claim counts at its approved amount in the liability | Count claimed amounts until settled | The approved amount is what has been committed (section 6) |

## 3. Data model

Each service owns its tables; Flyway owns the schema and Hibernate only validates it (`ddl-auto=validate`).

```text
Claim DB       claims 1 ----< claim_status_history | claim_assignment_history | claim_update_history
Assessment DB  claim_assessments, information_requests        (each refers to a claim by its public claim_id, no foreign key)
User DB        users
```

**`claims`** (Claim, V1 to V5)
- `id` BIGINT identity (internal key; never used in public routes). `claim_id` VARCHAR(32), unique: the public reference, for example `CLM-2026-000101`.
- `claimant_id` (V5: renamed from `created_by`): the login name in the token of whoever submitted the claim, never a name from the body; null for claims created before it existed, which only staff can see. `claimant_name`, `claimant_email`, `policy_number` (V5; a nullable column because old claims have none, required by the API for every new claim), `market` (2 characters), `claim_type` (`MOTOR`, `PROPERTY`), `incident_description` (V5: renamed from `description`), `incident_date`.
- Money: `claimed_amount` NUMERIC(19,2), positive; `currency` (3 characters); `approved_amount` (V5: renamed from `settled_amount`), null until approval and for a rejected claim.
- Workflow: `status` (check constraint on the six statuses), `assigned_officer_id`; decision: `decision_reason`, `decided_by_officer_id`, `decided_at`; settlement (V5): `settled_by_officer_id`, `settled_at`; `created_at`, `updated_at`.
- V5 also renamed the status label `AWAITING_INFORMATION` to `MORE_INFO_REQUIRED` in `claims` **and in `claim_status_history`** (the one time history rows were touched: their meaning is unchanged, only the name of one status), and for a claim that was `SETTLED` under the old one-step decision it set the settlement to the decision's officer and time. `ClaimMigrationTest` runs V5 over a database that holds old claims.

**`claim_status_history`** (V2, append-only): `claim_pk`, `from_status` (null on creation), `to_status`, `changed_at`, `actor`. One row per status change, written in the same transaction by `ClaimService.recordTransition`. `actor` is now an authenticated login name: the claimant for creation and for the automatic resume (the answering claimant), the officer for review, request, decision and settlement. A table was chosen over audit columns because a claim changes status several times.

**`claim_assignment_history`** (V3, append-only): `previous_officer_id` (null for the first), `new_officer_id`, `changed_at`, `actor`. Written by `ClaimService.recordAssignment` whenever the assignee really changes: on `assign` (actor: the manager or officer who did it) and when `review` assigns an unassigned claim (actor: the reviewing officer). Separate from status history because assignment does not change status.

**`claim_update_history`** (V5, append-only): `claim_pk`, `changed_at`, `actor`, `change_summary`, one line per changed field. Numbers, dates, claim type and policy number are shown as `old -> new`; free text (name, e-mail, description) is only recorded as `changed`, so personal data is not copied into a second table.

**`claim_assessments`** (Assessment, V1 and V2, append-only): `claim_id` (text), `officer_id`, `kind` (V2: `NOTE`, `APPROVED`, `REJECTED`, `SETTLED`; old rows are notes), `notes` (max 2000), `created_at`. It is the assessment history: the optional note written at review, then the decision's reason and the settlement note, each with the officer. An entry is written before the Claim Service is called and committed only if it accepts, so a refused review, decision or settlement saves nothing. A table was chosen over a column because a claim can be reviewed more than once. It is read with `GET /api/v1/officer/claims/{claimId}/assessments`, which only a claims officer (and ADMIN) may call, and it is **never** added to claimant-facing responses. Notes are plain text: no structured findings, no recommended amount, no edit or delete.

**`information_requests`** (Assessment, V2; moved from the former Information Service's database): `information_request_id` (unique, `IR-2026-000001`, from `information_request_number_seq`), `claim_id` (text), `question`, `status` (`OPEN`, `ANSWERED`), `requested_by_officer_id`, `created_at`, `answer`, `answered_at`. **Nothing was copied from the old Information Service's database** (it ran on in-memory H2 by default); a persistent one would have to be exported by hand.

**`users`** (User, V1 and V2): `username` (unique, lower-case), `password_hash` (BCrypt), `role` (check: `CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER`, `ADMIN`; `SERVICE` is deliberately not storable), `active` (V2, default true), `created_at`. V2 renames stored `OFFICER` rows and replaces the check; `UserMigrationTest` runs it over old rows.

**IDs.** `id` is internal; `claimId` and `informationRequestId` come from database sequences (`claim_number_seq`, starting at 101, and `information_request_number_seq`). A sequence is atomic across concurrent transactions, unlike `count + 1`; numbers are not reused after a rollback, so gaps are possible.

**Money.** `BigDecimal` / `NUMERIC(19,2)`, an explicit currency on every claim (`[A-Z]{3}`), returned with every amount, and amounts of different currencies are never added together. The brief names no supported currencies and no exchange rate exists, so any well-formed code is accepted and no conversion is done.

**Indexes** (each tied to a real query): `(status, market)`, `assigned_officer_id`, `claim_type` and `created_at` for the staff list; `(status, currency)` for the management summary; `(claimant_id, created_at)` (V5) for "my claims"; the history tables on `(claim_pk, changed_at)`; `claim_assessments (claim_id, created_at)`; `information_requests (claim_id, status)`.

**Not modelled:** officers, claimants, markets and policies are not tables in the Claim Service. An officer is a user with a role; the claim stores the login name. There is no policy to check cover, limits or deductibles against.

## 4. Claim lifecycle

```text
SUBMITTED --review--> UNDER_REVIEW --request information--> MORE_INFO_REQUIRED
                          ^   |                                    |
                          |   +---- last OPEN request answered ----+   (automatic)
UNDER_REVIEW --reject--> REJECTED          (terminal)
UNDER_REVIEW --approve (with an approved amount)--> APPROVED --settle--> SETTLED   (terminal)
```

- Transitions are enforced inside the `Claim` entity, so they hold no matter which controller or service calls them. An illegal move is a `409 INVALID_CLAIM_TRANSITION`. `ClaimDomainTest` holds a hand-written table of the allowed moves from every status and checks that every other move is refused and changes nothing.
- `assign` works in any non-terminal state (including `APPROVED`) and does not change the status.
- `review` works from `SUBMITTED`, and from `MORE_INFO_REQUIRED` only if no request is `OPEN`. If a claim has an assigned officer, a different officer cannot start review (`409 OFFICER_MISMATCH`).
- Requesting information, deciding and settling are done only by the **assigned** officer (`409 OFFICER_MISMATCH` otherwise). Together with the scope rule (section 8a) this means an officer cannot assess or settle a claim that is not theirs.
- **Why `APPROVED` is separate from `SETTLED`.** Before Phase 1 the decision settled the claim in one step. Approving (a decision with an amount) and paying are different acts: the claim is still open work and still a liability after approval, and "settlement tracking" needs a date and an officer. The cost is one more status, one more route and a changed meaning of the old `SETTLED`; the migration keeps old settled claims as `SETTLED` with their amount as the approved amount.
- **A rejected claim can never be settled**: `REJECTED` is terminal and a settlement from any status but `APPROVED` is a 409 (for a rejected claim the message says "A rejected claim cannot be settled").
- **Resume after an information request:** answering the last `OPEN` request moves the claim back to `UNDER_REVIEW` automatically, so the claimant's answer does not stall in a queue; the cost is that the officer does not explicitly acknowledge it.
- A decision needs `UNDER_REVIEW`. An approval needs an `approvedAmount` that is positive and no more than the claimed amount; a rejection must carry none; the decision currency must match the claim currency; a second decision is a 409 and the first is kept. "Approval requires the necessary assessment information" is implemented as: a non-blank reason and a valid amount in the claim's currency; no separate finding is required before approving.
- A claimant may correct their own claim only while it is `SUBMITTED` (`409 CLAIM_NOT_EDITABLE` after that), under the same row lock, so an edit and the start of review cannot interleave: whichever commits second sees the other's result.
- State-changing calls load the claim with a pessimistic row lock (`SELECT ... FOR UPDATE`) inside a transaction. `ConcurrentTransitionTest` races two decisions, a decision against an information request, two reviewers, two resumes and two settlements, and `ClaimUpdateTest` races an edit against the start of review: one wins, the other gets a 409, the stored result is the winner's and a rejected action leaves no history row. No `@Version` column: the row lock already serialises writers. This has only been exercised on H2, not PostgreSQL (section 9).
- At most one information request can be open per claim, because a new request needs `UNDER_REVIEW`, and an open request leaves the claim in `MORE_INFO_REQUIRED`.

## 5. Synchronous REST now, Kafka later

**Decision: every operation is a synchronous REST call.** There are 26 public endpoints (18 claim-domain, 8 in the User Service) and no messaging.

Why REST fits each of them:
- **Commands** (create, edit, assign, review, request information, answer, approve, reject, settle) need an immediate answer: the caller must know whether the transition was legal. The result is a status code and a body, and the change is committed in the same request.
- **Queries** (track a claim, list, management summary, officer metrics) are plain reads that return current data. They are computed by the database (`GROUP BY`, `Pageable`), so they are always consistent with the writes.
- Strong consistency matters more than throughput here, and there is no workload that needs buffering.

**Kafka is not implemented.** There is no producer, consumer, topic, dependency or configuration, and nothing has been tested against a broker. What follows is a proposal, not a feature.

Where events would help:
- **Facts that others react to:** `ClaimSubmitted`, `InformationRequested`, `InformationReceived`, `ClaimApproved`, `ClaimRejected` and `ClaimSettled`. Consumers would be claimant notifications, analytics, or a read model for reporting. These do not need to block the HTTP response.
- **Decoupling a future read model or service** from the claims database.

What would stay REST: all commands, and the queries that need an immediate answer.

What a safe design needs (not built): a **transactional outbox** (write the event in the same transaction as the status change and publish from there); keys by `claimId` so events for one claim stay in order; **idempotent consumers** (at-least-once delivery, de-duplicate on an event ID); a versioned event schema and a dead-letter or retry path; and **verification against a real running broker** before anything is claimed as working.

## 6. Outstanding liability

`GET /api/v1/management/summary` (Claim Service, MANAGER and ADMIN) reports outstanding claims and an estimated liability.

Calculation:
- A claim is outstanding when its status is not `SETTLED` or `REJECTED`: `SUBMITTED`, `UNDER_REVIEW`, `MORE_INFO_REQUIRED` and `APPROVED` count.
- Estimated liability is the sum, **grouped by currency**, with a claim count per currency, of the **claimed amount** for a claim not yet decided and the **approved amount** for an `APPROVED` claim awaiting settlement (what the insurer has committed to pay, which can be less than was claimed). One database `GROUP BY` with a `CASE` does the work; amounts of different currencies are never added.
- The response carries a note: a simple estimate, with no exchange-rate conversion, neither the expected settlement nor a booked reserve, not an actuarial reserve.

Limitations:
- **Claimed is not expected.** Until a claim is decided the figure is what the claimant asked for; claims are often settled for less or rejected, so it tends to overstate.
- **No reserving.** No incurred-but-not-reported claims, no development factors, no probability of settlement by status.
- **No policy data.** Limits, deductibles and cover are not checked because there are no policies; `policyNumber` is recorded but not looked up.
- **No currency conversion.** Exposure is per currency; there is no group total and no FX view.
- **Point in time.** Calculated on request from current data; no history or trend.

`GET /api/v1/management/officers` reports open claims by current assignee and status (an `APPROVED` claim is open work until it is settled), and decisions by the deciding officer: `decidedCount`, `approvedCount` (everything the officer approved, still `APPROVED` or since `SETTLED`), `settledCount`, `rejectedCount` and the approved amount per currency. It has no time-based metric because the data model has no "review started" timestamp, and no reassignment history.

## 7. APAC markets

The brief says Chubb APAC processes claims "across six markets". **It does not name them, and this project does not invent a list.**

What the code does today:
- `market` must match `[A-Z]{2}` (two upper-case letters). `SG`, `HK` and `AU` appear in the examples only as illustrations; the code does not treat them specially.
- There is no built-in list of the six markets and no market table, so by default nothing checks that a claim's market is one of six. The staff list filters by market with an exact, case-sensitive match.
- **Configurable allow-list (built):** `claims.markets.allowed` (comma separated, `MarketPolicy`, Claim Service) is **empty by default**, which keeps the behaviour above. When it is set, creating a claim with any other market is a 400 `INVALID_MARKET`. The tests use `SG, HK` as test values only.

**Gap:** the six markets are still not defined, so they are not enforced by default. Once the panel supplies them, set `claims.markets.allowed` (no code change needed). Until then the field is a free two-letter code, stated as an assumption in the README.

## 8. API conventions in brief

- Routes under `/api/v1`; public paths use `claimId`, never the internal `id` (the numeric `id` in a path returns 404). Validation with Jakarta annotations; paging capped at 100.
- Status codes: 400 invalid input, 401 no valid token or a wrong password (a deactivated user gets the same), 403 the caller may not do this (the role may not use the route, or an officer naming another officer or giving a claim away), 404 unknown claim or request (also a claim outside the caller's scope), 405 wrong method, 409 invalid transition or duplicate action, 415 wrong content type, 502 and 503 when a service this one calls fails.
- One error shape for everything, including 401 and 403 raised inside the security filter chain: `{timestamp, status, error, code, message, path}`, with no stack traces. Entities are never returned; DTO records are.
- `GET /actuator/health` on each service answers `{"status":"UP"}` without a token and shows no details; no other actuator endpoint is exposed (checked on the real jars: `/actuator/env` is 401 without a token). Spring Boot's database health indicator makes it report `DOWN` when the database is unreachable; that case was not tested here.

## 8a. Authentication and authorization

JWT login and role-based access control were added after the first split, on request; Phase 1 tightened them (below). Every route needs a token and each route lists the roles that may call it (`README.md` section 2, `API_TEST_CASES.md` section 3b). Kafka was deliberately kept out.

**A separate User Service, and tokens checked locally.** Credentials live in their own service and database. The Claim and Assessment services never call it to check a token: they verify the signature, expiry and issuer themselves with the shared secret. A claim can be reviewed while the User Service is down; only logging in stops (and a manager's assignment, section 2). The price is that nothing can be revoked before the token expires.

**Token format: JWT signed with HMAC-SHA-256 and one shared secret.** The simplest scheme that works for services deployed together; `jjwt` was chosen over Spring's OAuth2 resource server because the filter is 20 lines. The cost is real: every service that can verify a token can also *mint* one, including an ADMIN or SERVICE token. A public-key signature (the User Service signs, the others only verify) removes that and is the first thing I would change.

**The secret has no default; `JWT_SECRET` is required.** History: it had no default at first; a demo default (the same literal in every `application.properties`) was then introduced because a fresh checkout could not obtain a usable token, and because a half-edited configuration had left some services disagreeing; in Phase 1 it was removed again at the owner's request to keep secrets out of source control. Now every service reads `${JWT_SECRET:}` and refuses to start if it is missing or under 32 characters (observed on the real claim-service jar: it exits with a message naming the variable and never opens its port). `start-all.ps1` uses your `JWT_SECRET` or generates a random one for that run, gives it to its three processes only, never prints or saves it, and restores your session environment, so tokens of that run die with it. The price is one setting per IDE run configuration. `JwtSecretConfigurationTest` fails if any service has a default or reads another variable, or if any packaged properties file holds a credential literal (the public demo password is the one exception). Tests never use a configured secret: each test JVM generates a random one.

**What a token must satisfy:** valid HS256 signature (HS384/HS512 refused even with the right key), the expected issuer, an expiry in the future (30 seconds of clock tolerance), a subject, and a known role. A token without an expiry is refused even though the library would accept it. A token carrying a role name that no longer exists (the old `OFFICER`) is simply refused. Anything else is "no token": the request becomes anonymous and gets 401 with no hint why.

**Roles.** `CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER`, `ADMIN` (people) and `SERVICE` (one service calling another). No role hierarchy: a manager can read every claim, assign claims and read the reports but cannot review, decide or settle; an officer cannot read the reports or assign to anyone but themselves; because the requirement lists them as separate jobs and a hierarchy would quietly hand out more than listed. ADMIN is on every public route ("full access") and creates and manages users. `SERVICE` is deliberately **not** storable as a user's role (a database check, validation, and `JwtUtil.generate` refuses it).

**Authorization is a table per service, not annotations.** Each service lists `(method, path, roles)` rows (`AccessRules`), and one shared chain applies them. The whole policy of a service is on one screen; the same rows drive the tests; a denied request is answered in the filter chain and never reaches a controller; anything not listed is ADMIN-only, so a new endpoint without a rule fails closed. Three tests keep the table honest: (1) every route is called as every role and with no token and must behave as the table says; (2) the table is compared with a hand-written copy of the documented matrix; (3) every `/api/v1` route the service serves must have exactly one rule. Test (1) alone proved nothing about a *wrong* table (widening a rule changed the table and the check together and it still passed); only (2) caught that.

**Internal routes: service tokens, not forwarded user tokens.** `/internal/v1/**` accepts only a SERVICE token, in every service, enforced once in the shared chain. Each service mints a 60-second token named after itself for every outgoing call (a `RestClientCustomizer`). Forwarding the caller's token would carry the officer's identity into the Claim Service, but would also let that officer call `/internal/.../decision` directly and skip the Assessment Service's workflow; the stricter boundary was chosen. A person's token, an administrator's included, is refused there. If another service refuses a token (for example the secrets differ) it is a configuration fault between services, so the caller gets a 502 `DOWNSTREAM_ERROR` with no detail, never the other service's 401.

**Which claims a person may see: one shared rule, `ClaimVisibility`.** A claimant sees only claims they submitted (`claims.claimant_id`); a claims officer sees unassigned claims (the queue they can pick up) and claims assigned to them; a manager and an administrator see everything. Outside that, the answer is exactly the 404 an unknown claim gets, so claim numbers cannot be probed. The rule lives in `claims-common` and is used by the Claim Service for a single read (`ClaimVisibility`) and for lists (the same rule as SQL, `ClaimSpecifications.visibleTo`, so a list cannot show what a read would hide), and by the Assessment Service for every officer and claimant action, using `claimantId` and `assignedOfficerId` from the Claim Service's internal status view. The trade-off, chosen on purpose: an officer cannot look at a colleague's claim to help, and a claim assigned to an absent officer needs a manager to reassign it. A claim created before owners were recorded is never a claimant's; it is visible to staff (to officers only while unassigned).

**Who an action is recorded against: the token.** Before Phase 1 the officer ID in a request body was trusted text, so one officer could act "as" another and the history `actor` was only what the caller claimed. Now `AuthenticatedUser.actingOfficerId` decides: a claims officer may leave the field out or repeat their own name (naming another is 403 `OFFICER_IDENTITY_MISMATCH`), so existing request bodies still work; an administrator acting for an officer must name one (400 otherwise), so an administrator can never silently become the officer of a claim. The history `actor` is therefore a login name. One honest consequence: when an administrator acts for an officer, the history shows that officer, not the administrator. Adding a column for "who really did it" to the history tables (and a migration) was weighed and not done; instead the Assessment Service writes one log line per such request naming the administrator (`ActingAs`), which is cheap and visible but only as durable as the log (an independent review pointed out the gap; this is the part of the answer that is a limit, not a fix).

**Assignment.** An officer may only take a claim for themselves (403 `ASSIGNMENT_NOT_PERMITTED` otherwise); a manager or administrator may name any officer, who must be an active claims officer (400 `INVALID_OFFICER`, 503 if the User Service cannot answer). Every change is in the assignment history with the caller as the actor. **Starting a review of an unassigned claim is also an assignment**, so it follows the same rule; the first version missed this and an administrator could put a claim on any name by calling `review` (found by an independent review, then fixed and mutation-checked). The Assessment Service now tells the Claim Service whether the officer's identity was proved by the officer's own token (`officerVerified`); if not (an administrator named the officer) and the claim is unassigned, the Claim Service asks the User Service before it locks the claim. I preferred this to a second User Service client in the Assessment Service (a second edge in the call graph) and to refusing administrators' reviews of unassigned claims (which would have changed what the replayed tests prove).

**The User Service.** Passwords are stored only as BCrypt hashes, 8 to 72 bytes (BCrypt ignores anything longer, so a longer password is refused rather than silently cut). Usernames are lower-case, so `Admin` and `admin` cannot be two people. Login answers an unknown user, a wrong password and a deactivated account identically, using Spring's `DaoAuthenticationProvider`, which spends a BCrypt comparison on an unknown user too. A deactivated account is deliberately reported as "credentials expired" rather than "disabled": Spring tests "disabled" before it compares the password and would answer measurably faster than a wrong password (found by the independent review), whereas "credentials expired" is tested after. The test counts the BCrypt comparisons (one for a deactivated account, one for an unknown user); response times themselves were not measured. Anyone can register, and registration always creates a CLAIMANT; only an ADMIN creates other roles, changes a role (not their own) or switches an account on or off (not their own). An administrator can be created at start-up from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`, only if that name does not exist, never reset, never logged.

**The active flag.** `PUT /api/v1/users/{username}/active` (ADMIN) stops a person logging in: they get the same 401 as a wrong password. It does **not** revoke a token already issued, which keeps working until it expires (default 60 minutes; a test records this), and it does not stop an inactive officer's existing assignments. It does stop a manager assigning *new* claims to them, because the Claim Service asks the User Service. Deactivating was chosen over deleting so names in the history stay meaningful.

**Default test users, on request.** Out of the box there was no way to obtain a CLAIMS_OFFICER, MANAGER or ADMIN token. `DemoUsers` therefore creates `admin1`, `officer1` (CLAIMS_OFFICER), `manager1` and `claimant1`, all with the password `password123` (or `DEMO_USERS_PASSWORD`). I first refused to ship default credentials; the owner asked for them explicitly, with these limits: the switch is a property (`claims.auth.demo-users.enabled`), on in the packaged `application.properties` as `${DEMO_USERS:true}`, off in code and in the tests; users are created only if missing and never reset (so a demo officer already stored with the old role name is converted by migration V2, not recreated); the password is a BCrypt hash and never logged; a WARN line names them at every start. The cost is a well-known administrator login on any instance started with the packaged defaults, which is why every document calls the result a demo, not a secure deployment. The better design (not built) is no default users and a first-run administrator step.

**Where login lives.** Login is served only by the User Service, on its own port. Asking the other services for `/api/v1/auth/login` gives 401 (no token) or 404 (with one), which looks like "the endpoint is missing", so the Swagger description, the 401 message and the README all say where to log in, and login is a documented group of its own (*Authentication*). No rate limiting, lockout, password reset, refresh token, logout or revocation: a role change reaches a user at their next login.

**How the existing tests kept their meaning.** The monolith's tests were written before login existed and are untouched in the monolith. In the microservices, any request that sets no `Authorization` header is sent as ADMIN (or SERVICE on `/internal`), which keeps the business-rule tests about the rule; they prove a rule for an authorised caller only, so keeping the wrong person out is proved by the role and scope tests. A test that wants no credentials sends an empty header. The system suite's replay of the old tests was changed deliberately for Phase 1 and is no longer "unchanged": officers are real lower-case accounts (a manager or administrator can only assign to a real, active claims officer, and `OFF-1007` cannot be a username), decisions say `APPROVED` and are followed by a settlement where the claim must end `SETTLED`, history actors are login names, and the new field names are used. Every change keeps or tightens the assertion; none was removed to get green.

**Not done, in order of importance:** public-key signing so only the User Service can mint tokens; revocation or very short tokens with refresh; recording the administrator as well as the officer in the history tables when an administrator acts for one (today only a log line does); rate limiting on login and registration; TLS; per-team data scope for managers; running the services on PostgreSQL end to end (section 9).

## 9. Shortcuts taken, and what I would improve with more time

### Shortcuts and known limitations (as implemented)
- **Authentication is shared-secret JWT with demo users on** (section 8a). The signing secret is no longer in any file, but the four demo users share the public password `password123`, so on a default start anyone who has read the README can log in as an administrator; any holder of `JWT_SECRET` can mint a token with any role; no revocation, logout, refresh, lockout, rate limiting or TLS; a deactivated user's token works until it expires; a manager can read every claim.
- **Microservice shortcuts (section 2):** no gateway, no service discovery, no retries or circuit breakers, one instance per service, and best-effort (not transactional) consistency across services. Service-to-service routes accept only a service token, which is shared-secret trust, not network isolation. There is no data migration from the old Information Service's database or from a monolith database.
- **H2 is the default database** (one per service). Data is lost on restart, and the three databases can disagree after a partial restart (a restarted User Service has no users while old tokens stay valid until they expire).
- **Credentials are environment-only now.** The Claim and User services' `application.properties` used to hold a hard-coded local PostgreSQL URL, user and password (and the Claim port 8787); Phase 1 replaced them with the `*_DB_*` variables and the documented default port 8181. That password sat in a synced folder, so it should be treated as exposed. An IDE run configuration that does not set the variables (and `JWT_SECRET`) now starts on H2, or fails fast for the secret.
- **PostgreSQL has been run only partly.** A local PostgreSQL 17.6 ran Claim migrations V1 to V4 and User V1, Hibernate's `validate`, an authenticated search and logins, before Phase 1. **Not run on PostgreSQL:** Claim V5, User V2, Assessment V2 (they ran on H2, including over old rows, and use only syntax PostgreSQL has), claim creation (`nextval`), transitions, `SELECT ... FOR UPDATE` locking, `TIMESTAMP WITH TIME ZONE` round trips, the concurrency tests, and the Assessment database. Flyway will apply the new migrations the first time a service starts against an existing database, which changes data (a status label, column names, a role name); that was not done to anyone's database during Phase 1.
- **One database per service is a rule, learned the hard way.** The owner's local setup once put the Claim and User services in one PostgreSQL database; the second to start failed with a Flyway checksum mismatch on version 1 (every service has a different V1 and a database has one history table), and Flyway `repair` only moved the failure to the other service (reproduced on H2). The fix is the project's own design, a database per service, and `DatabasePerServiceConfigurationTest` fails the build if two services' packaged URLs are equal.
- **Kafka is not implemented** (section 5).
- **Text-only information.** There is no document or photo upload.
- **Assessment is free text only.** Notes, the decision reason and a settlement note; no structured findings, no recommended amount, no edit. "Approval needs the necessary assessment information" means a reason and a valid amount, not a prior note.
- **A database connection is held during a call to another service.** Assessment writes its own row first and calls the Claim Service before it commits, so that a refusal rolls its row back (the design choice of section 2). The connection stays checked out for the call, up to the 10-second read timeout; a Claim Service that hangs (rather than refuses) can exhaust the default pool of ten connections under concurrent load until the timeouts fire. Calling after the commit would free the connection but lose the rollback guarantee. The Claim Service's assignment path avoids it by asking the User Service before opening a transaction.
- **Legacy data after the upgrade:** claims assigned to an old free-text officer name (`OFF-1007`) are visible to no officer (that name cannot be a login) until a manager reassigns them; a claim that was waiting for information has no question any more (the Information Service's data is not copied) until an officer resumes and asks again. Both are in the README's upgrade notes.
- **History actors are authenticated but flat:** an administrator acting for an officer is recorded as that officer (with a log line naming the administrator). The data for "time in review" exists but no metric uses it yet.
- **Markets are validated only if configured** (section 7).
- **Concurrency testing is limited to H2.** The row locking has never run on PostgreSQL.
- **Free-text claimant name and email** stay as typed; the claimant's identity is the login name.
- **The API still returns the internal numeric `id`** in claim bodies (never accepted in a path); a pre-existing wart not changed here.
- **No explicit test for a 500 response.**
- **Version control:** the commits that existed when Phase 1 started cover the monolith. The microservices, authentication and the Phase 1 changes are uncommitted; nothing was committed or pushed by the assistant, so the history does not show the development process.

### With more time, in rough priority order
1. **Security, the rest of it:** public-key token signing, revocation or short tokens with refresh, login rate limiting, TLS, and a gateway or network policy in front of the internal routes.
2. **Run against PostgreSQL** (with Testcontainers) to verify the new migrations, sequences and locking, and apply them to the owner's databases with a backup.
3. **Use the histories** for time-in-state, time-to-decision and reassignment metrics.
4. **A richer assessment record** (structured findings, recommended amount) on top of the notes.
5. **Set the market list** (`claims.markets.allowed`) once the six markets are known.
6. **Outbox plus Kafka events** (section 5), verified against a real broker.
7. **Better liability:** expected-payout estimates by status and claim type, policy limits and deductibles, optional FX conversion for a group view; and a read replica or cache if the reports ever compete with claim writes.
8. **Operations:** metrics, structured logging with correlation IDs, readiness probes beyond the health endpoint, and a CI pipeline running the tests.
9. **Document upload** for supporting evidence, with virus scanning and storage outside the database.

## 10. Questions a reviewer might ask

- **Why three services, when this document once argued for five?** The owner asked for three. Section 2 records what that removes (the stats routes, two clients, a copied enum, two processes, a service that was down whenever Claim was) and gives up (reporting cannot scale apart from claim writes; Assessment has two audiences). The lifecycle stays in the Claim Service, so no distributed transaction is needed.
- **Why does the Claim Service call the User Service?** To check that a manager's assignee is an active claims officer. It is the only such call, it is made before any lock is taken, and an officer taking a claim for themselves does not need it. If the User Service is down, assignment fails with 503 and nothing else is affected.
- **Why REST and not Kafka?** Commands need an immediate, consistent answer; events are proposed for side effects later, with an outbox (section 5).
- **Why is approving separate from settling?** Approval fixes the amount; settlement is a later act with its own officer and time. An approved claim is still a liability, counted at its approved amount (sections 4 and 6).
- **Why does answering an information request resume review automatically?** To avoid a stalled queue; the trade-off is that no officer explicitly acknowledges it (section 4).
- **Why is liability partly the claimed amount?** It is the only amount known for an undecided claim; it is labelled an estimate, and its limits are listed (section 6).
- **Why generate `claimId` from a sequence?** It is atomic and collision-safe under concurrency; `count + 1` is not (section 3).
- **Can an officer see or act on another officer's claim?** No: an officer sees only the unassigned queue and their own claims, anything else answers as unknown (404), the officer an action is recorded against comes from the token, and only the assigned officer can request information, decide or settle (sections 4 and 8a).
- **Is it secure?** More than it was, not secure enough to call production-ready. Every route needs a signed token limited to the roles that may use it; claimants and officers see only their claims; internal routes take only service tokens; passwords are hashed; no secret or credential is in a file. But the signing secret is shared by all services, tokens cannot be revoked, login is not rate limited, there is no TLS, and a default start has four well-known demo users (switch them off with `DEMO_USERS=false`). It has had no security review or penetration test (sections 8a and 9).
- **Why can any service mint a token, and is that a problem?** It is a consequence of one shared HMAC secret, chosen for simplicity. Anyone who obtains the secret can mint an ADMIN token, so it stays in the environment only, and a leaked secret means changing it everywhere and restarting. Public-key signing removes the problem (section 8a).
- **Why does an officer's token not work on `/internal/.../decision`?** So the decision can only go through the Assessment Service, which validates it, checks scope and keeps the assessment history consistent. Internal routes take a service token only, even from an ADMIN.
- **Does it work on PostgreSQL?** Partly, and not the Phase 1 changes: before Phase 1, migrations V1 to V4 (Claim) and V1 (User), schema validation, a search and logins ran on a local PostgreSQL 17.6; the new migrations, claim creation, transitions, locking and the concurrency tests did not (section 9).
