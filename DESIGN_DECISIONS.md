# Design Decisions

Why the Chubb APAC Claims backend is built the way it is, what was left out, and what would come next. This describes the code as it stands. Where something is only proposed or unverified, it says so.

Related documents: `README.md` (setup and run), `API_TEST_CASES.md` (endpoint contracts and test cases), `CLAUDE.md` (development rules), `.claude/ai-journal.md` (working journal).

## 1. What the brief asks for, and what was prioritised

The brief (`design.docx`) asks for a backend that lets claimants report an incident, track a claim and provide extra information, and lets claims staff pick up, review, settle or reject claims and see team workload and performance. It also wants managers to see outstanding liability. It says the brief is deliberately underspecified, that the candidate should decide what to build, and that the decisions are a significant part of the assessment.

Built, in priority order:
1. The claim lifecycle with rules enforced on the server (the part most likely to be wrong in a claims system).
2. Claimant, officer and management endpoints on top of it (14 REST endpoints).
3. Efficient officer workload queries and per-currency liability.
4. Automated tests for every endpoint, on a database that needs no install.

Deliberately not built: authentication, document upload, notifications and Kafka. Assignment history and review assessment notes were added later (section 3). See section 9.

## 2. Modular monolith versus microservices

**Decision: one Spring Boot application, organised by feature packages** (`claim`, `officer`, `information`, `management`, `common`).

Why:
- **One transaction boundary.** Every state change touches one claim (and sometimes one information request) and must be atomic. In one process this is a single database transaction with a row lock. Splitting into services would turn a status change plus an information request into a distributed workflow.
- **Small domain.** There are five tables (`claims`, `information_requests`, `claim_status_history`, `claim_assignment_history`, `claim_assessments`) and one lifecycle. Only `claims` and `information_requests` drive the workflow; the other three are append-only records written in the same transaction. Several services would add network calls, deployment units, versioned contracts and distributed failure handling for no gain at this size.
- **Speed.** The brief is a short sprint. A monolith lets most of the effort go into the rules and tests.
- **Clear seams remain.** Controllers are thin, rules live in the `Claim` entity and services, and DTOs separate the API from the entities. The packages can be pulled apart later if there is a reason.

Trade-offs accepted:
- One deployable unit scales as a whole, and a bug in one package can take down the rest.
- One shared database schema, so packages could reach into each other's tables; this is controlled only by convention (and `CLAUDE.md`).

What would justify splitting:
- **Different read/write profiles.** Officer search and the management summary are read-heavy aggregates, while workflow transitions are low-volume, strongly consistent writes. If reads grow, a separate read model (fed by events, see section 5) is the first split.
- **Different scaling or ownership**, such as a notifications service run by another team.
- **Regulatory or availability isolation** of a component.

None of those exist yet, and none is measured, so the monolith stands.

## 3. Data model

Five tables, created by `V1__create_claims_schema.sql`, `V2__create_claim_status_history.sql`, `V3__create_claim_assignment_history.sql` and `V4__create_claim_assessments.sql` (Flyway owns the schema; Hibernate only validates it).

```text
claims 1 ----< information_requests
claims 1 ----< claim_status_history
claims 1 ----< claim_assignment_history
claims 1 ----< claim_assessments
```

**`claims`**
- `id` BIGINT identity (internal key; never used in public routes).
- `claim_id` VARCHAR(32), unique (`uq_claims_claim_id`): the public business reference, for example `CLM-2026-000101`.
- Claimant and incident data: `claimant_name`, `claimant_email`, `market` (2 characters), `claim_type` (`MOTOR`, `PROPERTY`), `description`, `incident_date`.
- Money: `claimed_amount` NUMERIC(19,2) with a positive check, and `currency` (3 characters).
- Workflow: `status` (check constraint on the five statuses), `assigned_officer_id`.
- Decision: `decision_reason`, `settled_amount`, `decided_by_officer_id`, `decided_at`.
- `created_at`, `updated_at`.

**`information_requests`**
- `id` BIGINT identity and `information_request_id` (unique, for example `IR-2026-000001`).
- `claim_pk` foreign key to `claims.id`; the API exposes the parent `claimId`.
- `question`, `status` (`OPEN`, `ANSWERED`), `requested_by_officer_id`, `created_at`, `answer`, `answered_at`.

**IDs.** `id` is internal; `claimId` and `informationRequestId` come from database sequences (`claim_number_seq`, starting at 101, and `information_request_number_seq`). A sequence is atomic across concurrent transactions and instances, unlike `count + 1`. Numbers are not reused after a rollback, so gaps are possible. The year in the reference is only a prefix for readability.

**Money.** `BigDecimal` / `NUMERIC(19,2)`, an explicit currency on every claim (`NOT NULL`, `[A-Z]{3}`), returned with every amount, and amounts of different currencies are never added together. INR is used in examples and tests; AUD and HKD remain only where a second currency is needed. The brief names no supported currencies and no exchange rate exists, so any well-formed code is accepted and no conversion is done.

**`claim_status_history`** (V2, append-only)
- `id`, `claim_pk` (foreign key to `claims.id`), `from_status` (null on the creation row), `to_status`, `changed_at`, `actor`. One row per status change, written in the same transaction as the change by `ClaimService.recordTransition`, which skips the write when the status did not change (so assignment adds nothing). `actor` is the officer ID from the request, or `CLAIMANT` for creation and the automatic resume; there is no authentication, so it records who the caller claimed to be. `GET /api/v1/claims/{claimId}/history` reads it. Index `(claim_pk, changed_at)` serves that listing. A table was chosen over audit columns on `claims` because a claim changes status several times.

**`claim_assignment_history`** (V3, append-only)
- `id`, `claim_pk` (foreign key), `previous_officer_id` (null for the first assignment), `new_officer_id`, `changed_at`, `actor` (nullable). A row is written by `ClaimService.recordAssignment` in the same transaction whenever the assignee really changes: on `assign`, and when `review` assigns an unassigned claim. It is separate from status history because assignment does not change status. `actor` is null for `assign` because the request carries only the new assignee, and is the reviewing officer for `review`; nothing is authenticated, so `actor` is never a verified identity. `GET /api/v1/claims/{claimId}/assignments` reads it (index `(claim_pk, changed_at)`).

**`claim_assessments`** (V4, append-only)
- `id`, `claim_pk` (foreign key), `officer_id`, `notes` (max 2000), `created_at`. `POST .../review` takes an optional `assessmentNotes`; a non-blank value is trimmed and saved by `OfficerClaimService.startReview` in the same transaction as the review, after the transition succeeded, so a rejected review saves nothing. A table was chosen over a column on `claims` because a claim can be reviewed more than once and overwriting would lose the earlier note. Existing clients that send only `officerId` are unaffected (the field is additive). Notes are read with `GET /api/v1/officer/claims/{claimId}/assessments` (index `(claim_pk, created_at)`) and are **not** added to the claimant-facing claim response, which has no authentication to protect internal notes. Without authentication the officer route is only a naming convention, so anyone who can reach the port can read the notes. Notes are plain text: no findings structure, no recommended amount, no edit or delete.

**Indexes** (each tied to a real query): `(status, market)`, `assigned_officer_id`, `claim_type` and `created_at` for the officer workload search; `(status, currency)` for the management summary; `information_requests (claim_pk, status)` for listing and open-request checks.

**Not modelled:** officers, claimants, markets and policies are not tables. Officer IDs are free text; there is no policy to check cover, limits or deductibles against.

## 4. Claim lifecycle

```text
SUBMITTED --review--> UNDER_REVIEW --information-request--> AWAITING_INFORMATION
                          ^                                          |
                          +------ last OPEN request answered --------+   (automatic)
UNDER_REVIEW --decision--> SETTLED | REJECTED   (terminal)
```

- Transitions are enforced inside the `Claim` entity, so they hold no matter which controller or service calls them. An illegal move is a `409 INVALID_CLAIM_TRANSITION`.
- `assign` works in any non-terminal state and does not change the status.
- `review` works from `SUBMITTED`, and from `AWAITING_INFORMATION` only if no request is `OPEN`. If a claim has an assigned officer, a different officer cannot start review (`409 OFFICER_MISMATCH`).
- **Decision to resume after an information request:** answering the last `OPEN` request moves the claim back to `UNDER_REVIEW` automatically. The officer does not need to call `review` again. This keeps the claimant's answer from stalling in a queue; the cost is that the officer is not explicitly acknowledging the answer.
- A decision needs `UNDER_REVIEW`. `SETTLED` needs a settled amount that is positive and no more than the claimed amount; `REJECTED` must carry none; the decision currency must match the claim currency; a second decision is a 409 and the first is kept.
- State-changing calls load the claim with a pessimistic row lock (`SELECT ... FOR UPDATE`) inside a transaction, so concurrent transitions on one claim are serialised. `ConcurrentTransitionTest` races two decisions (one settle, one reject) and a decision against an information request on one claim: one request wins, the other gets a 409, the stored decision is the winner's, and a rejected action leaves no history row. No `@Version` column was added: the row lock already serialises writers, so a stale transition re-reads the new status and fails the rule check. This has only been exercised on H2, not PostgreSQL (see section 9).
- At most one information request can be open per claim, because a new request needs `UNDER_REVIEW`, and an open request leaves the claim in `AWAITING_INFORMATION`.

## 5. Synchronous REST now, Kafka later

**Decision: every operation is a synchronous REST call.** There are 14 endpoints and no messaging.

Why REST fits each of them:
- **Commands** (create, assign, review, request information, answer, decide) need an immediate answer: the caller must know whether the transition was legal. The result is a status code and a body, and the change is committed in the same request.
- **Queries** (track a claim, officer search, management summary, officer metrics) are plain reads that return current data. They are computed by the database (`GROUP BY`, `Pageable`), so they are always consistent with the writes.
- Strong consistency matters more than throughput here, and there is no workload that needs buffering.

**Kafka is not implemented.** There is no producer, consumer, topic, dependency or configuration. Nothing has been tested against a broker. What follows is a proposal, not a feature.

Where events would help:
- **Facts that others react to:** `ClaimSubmitted`, `InformationRequested`, `InformationReceived` and `ClaimDecided`. Consumers would be claimant notifications, analytics, or a read model for management reporting. These do not need to block the HTTP response.
- **Decoupling a future read model or service** from the claims database (see section 2).

What would stay REST: all commands, and the claimant and officer queries that need an immediate answer.

What a safe design needs (not built):
- **Transactional outbox:** write the event to an outbox table in the same transaction as the status change, and publish it from there. This avoids the "database committed but the event was lost" case.
- **Key by `claimId`** so events for one claim stay in order.
- **Idempotent consumers:** deliver at least once, so consumers de-duplicate on an event ID.
- **A versioned event schema** and a dead-letter or retry path for failing consumers.
- **Verification against a real running broker** before anything is claimed as working.

## 6. Outstanding liability

`GET /api/v1/management/summary` reports outstanding claims and an estimated liability.

Calculation:
- A claim is outstanding when its status is not `SETTLED` or `REJECTED` (so `SUBMITTED`, `UNDER_REVIEW` and `AWAITING_INFORMATION` count).
- Estimated liability is the **sum of `claimed_amount`** of outstanding claims, **grouped by currency**, with a claim count per currency. A database `GROUP BY` does the work; amounts of different currencies are never added.
- The response includes a note: "Simple estimate: sum of claimed amounts of non-terminal claims, grouped by currency. Not an actuarial reserve."

Limitations:
- **What the figure is:** the sum of claimed amounts of open claims, per currency. It is not settlement exposure (expected payout) and not a reserve; the response note says so.
- **Claimed is not expected.** A claimant's figure is not the amount Chubb expects to pay. Claims are often settled for less, or rejected, so this tends to overstate.
- **No reserving.** It is not an actuarial reserve: no incurred-but-not-reported (IBNR) claims, no development factors, no probability of settlement by status.
- **No policy data.** Limits, deductibles and cover are not checked because there are no policies.
- **No currency conversion.** Exposure is reported per currency, so there is no single group total and no FX view.
- **Point in time.** It is calculated on request from current data; there is no history or trend.
- **Settled amounts are ignored for outstanding claims** (they only exist on decided claims).

`GET /api/v1/management/officers` (per-officer workload and performance) reuses the same approach. It reports open claims by current assignee and status, and decisions by the deciding officer (counts and settled amount per currency). It has no time-based metric because the data model has no "review started" timestamp, and it has no reassignment history.

## 7. APAC markets

The brief says Chubb APAC processes claims "across six markets". **It does not name them, and this project does not invent a list.**

What the code does today:
- `market` must match `[A-Z]{2}` (two upper-case letters). `SG`, `HK` and `AU` appear in the examples only as illustrations; the code does not treat them specially.
- There is no built-in list of the six markets and no market table, so by default nothing checks that a claim's market is one of six.
- The officer search filters by market with an exact, case-sensitive match.
- **Configurable allow-list (built):** `claims.markets.allowed` (comma separated, `MarketPolicy`) is **empty by default**, which keeps the behaviour above. When it is set, creating a claim with any other market is a 400 `INVALID_MARKET`. The tests use `SG, HK` as test values only; they are not claimed to be the six markets. The format check (`[A-Z]{2}`) still runs first.

**Gap:** the six markets are still not defined, so they are not enforced by default. Once the panel supplies them, set `claims.markets.allowed` (no code change needed); only a per-market rule or a reference table would need more. Until then the field is a free two-letter code, which is stated as an assumption in the README.

## 8. API conventions in brief

- Routes under `/api/v1`; public paths use `claimId`, never the internal `id` (the numeric `id` in a path returns 404).
- Validation with Jakarta annotations; paging capped at 100.
- Status codes: 400 invalid input, 404 unknown claim or request, 405 wrong method, 409 invalid transition or duplicate action, 415 wrong content type.
- One error shape for everything: `{timestamp, status, error, code, message, path}`, with no stack traces.
- Entities are never returned; DTO records are.

## 9. Shortcuts taken, and what I would improve with more time

### Shortcuts and known limitations (as implemented)
- **No authentication or authorization.** Anyone who can reach the port can call every endpoint, including officer and management ones. Officer IDs in request bodies are trusted. A claimant can read any claim if they know the `claimId`.
- **H2 is the default database.** Data is lost on restart.
- **PostgreSQL has never been run.** An opt-in `postgres` profile exists (`application-postgres.properties`, environment variables only) but no PostgreSQL server is available on this machine, so it is unverified. The migrations, the sequence calls, the `TIMESTAMP WITH TIME ZONE` columns and the row locking are written for it but unverified. Tests use H2 in PostgreSQL mode, which only approximates it.
- **Kafka is not implemented** (section 5).
- **Text-only information.** There is no document or photo upload.
- **Unassigned filter (built):** `assigned=false` lists claims nobody has picked up. It uses `assigned_officer_id IS NULL` on the existing officer index; no new index was added, and I have not measured it on a large table.
- **Assessment is free text only.** Review can save optional notes (V4), but there are no structured findings, no recommended amount, and notes cannot be edited. Notes are unauthenticated and readable by anyone who can reach the port.
- **History actors are unverified:** status and assignment history record the officer ID from the request (or `CLAIMANT`, or null for `assign`). Only the Swagger placeholder `"string"` is rejected (400, `OfficerIds`); any other text is accepted. Assignment history now exists (V3). The data for "time in review" exists but no metric uses it yet.
- **Markets are validated only if configured** (section 7); the six are not named, so none is built in, and `IND` was not added because nothing in the project establishes it (the code is two letters).
- **Concurrency testing is limited to H2.** Tested: concurrent creation, two decisions, a decision against an information request, two reviewers on one claim, two answers to one request, and stale actions after a decision. The row locking has never run on PostgreSQL.
- **No explicit test for a 500 response**, or for an officer search with no matches.
- **Free-text officer IDs** with no officer table, and no claimant identity.
- **Version control:** the implementation was committed as a single commit (`f1b6d62`); everything since is uncommitted at the time of writing (officer metrics, then the filter, market, history, concurrency and profile work). The history does not show the development process step by step.

### With more time, in rough priority order
1. **Security:** authentication (for example OAuth2/JWT), roles for claimant, officer and manager, and claimant ownership so people see only their own claims.
2. **Run against PostgreSQL** (and add PostgreSQL integration tests, for example Testcontainers) to verify the migration, sequences and locking.
3. **Use the status and assignment histories** for time-in-state, time-to-decision and reassignment metrics.
4. **A richer assessment record** (structured findings, recommended amount) on top of the notes.
5. **Set the market list** (`claims.markets.allowed`) once the six markets are known.
6. **Outbox plus Kafka events** (section 5), verified against a real broker.
7. **Better liability:** expected-payout estimates by status and claim type, policy limits and deductibles, and optional FX conversion for a group view.
8. **Operations:** metrics, structured logging with correlation IDs, health checks, and a CI pipeline running the tests.
9. **Document upload** for supporting evidence, with virus scanning and storage outside the database.
10. **Run the concurrency tests on PostgreSQL** (for example with Testcontainers).

## 10. Questions a reviewer might ask

- **Why not microservices?** One transactional workflow over five tables (two workflow tables and three append-only record tables); services would add distributed failure handling without a measured need (section 2).
- **Why REST and not Kafka?** Commands need an immediate, consistent answer; events are proposed for side effects later, with an outbox (section 5).
- **Why does answering an information request resume review automatically?** To avoid a stalled queue; the trade-off is that no officer explicitly acknowledges it (section 4).
- **Why is liability the claimed amount?** It is the only amount known for an open claim; it is labelled an estimate, and its limits are listed (section 6).
- **Why generate `claimId` from a sequence?** It is atomic and collision-safe under concurrency; `count + 1` is not (section 3).
- **Is it secure?** No. There is no authentication or authorization (section 9).
- **Does it work on PostgreSQL?** Unknown. It has only been run on H2 (section 9).
