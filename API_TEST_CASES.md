# API Test Cases

Test cases for the 18 claim-domain endpoints and the 8 User Service routes of the Chubb APAC Claims backend. They were written from the actual controllers, DTOs, services, access-rule tables and exception handler, not from a planned API list.

**The backend is three business microservices (see `README.md`).** The old statement "14 claim endpoints" is superseded: Phase 1 merged the former Reporting Service into the Claim Service and the former Information Service into the Assessment Service, and added four endpoints (15 to 18), so the claim domain now has **18** public endpoints (Claim Service 11, Assessment Service 7), plus **8** User Service routes (U1 to U8). The endpoint numbers 1 to 14 keep their meaning; 15 to 18 are new. Every endpoint needs a token whose role may use the route (section 3b). There is no gateway (decision: use the services' own endpoints), so call each endpoint on the service that owns it:

| Service | Default port | Public endpoints (numbers as in section 12) | Swagger UI |
|---|---|---|---|
| User Service (the Auth Service) | `8185` | U1 to U8 (section 3b) | `http://localhost:8185/swagger-ui/index.html` |
| Claim Service | `8181` | 1, 2, 3, 4, 10, 11, 12, 13, 15, 16, 17 | `http://localhost:8181/swagger-ui/index.html` |
| Assessment Service | `8182` | 5, 6, 7, 8, 9, 14, 18 | `http://localhost:8182/swagger-ui/index.html` |

Each service also answers `GET /actuator/health` without a token (section 3c). Paths in the sections below are written relative to the service that serves them. Section 3a lists the service-to-service routes.

**Update 2026-10-27.** Sections 13 to 20 were added: PostgreSQL setup (13), the eight demo and test users with login examples (14), two complete end-to-end flows with real requests and responses (15), a role matrix for all 26 endpoints (16), 210 positive and negative scenarios plus the downstream-failure cases (17), the 15-step Swagger order (18), a Postman collection (19) and the final coverage table (20). Sections 1 to 12 are unchanged except for the notes marked `Update 2026-10-27`. Everything in sections 14 to 20 was executed live against jars built from commit `ee90578` on spare ports with in-memory H2; **section 13 (PostgreSQL) was not run**, and the Postman collection was not imported into Postman.

- **Headers:** `Content-Type: application/json` on every `POST`/`PUT`/`PATCH`, and **`Authorization: Bearer <accessToken>` on every request except `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /actuator/health`, Swagger UI and `/v3/api-docs`**. Get a token from `POST /api/v1/auth/login` (section 3b). The cases in sections 4 to 10g say what each endpoint returns for a caller whose role is allowed; the role rules, 401 and 403 cases are in section 3b. Where a case below does not name a role, it was run as an allowed one.
- **IDs:** `{claimId}` in a path is the public business ID (for example `CLM-2026-000101`), never the internal numeric `id`. Sample IDs and timestamps below are illustrative; always use the values the running app returns.
- **Error body (all 4xx/5xx):** `{ "timestamp", "status", "error", "code", "message", "path" }`. No stack traces.
- **Who the officer is:** officer IDs are login names of real claims-officer accounts (lower case, for example `officer1`). A claims officer never has to send one: the officer is the token's user (rule in section 3b, "Who an action is recorded against").

## 1. How the cases are verified

| Kind | What it is | Status |
|---|---|---|
| **Automated, per service** | MockMvc tests in each service module (`microservices/<service>/src/test`) against that service's full Spring context and its own H2 database (PostgreSQL mode); the services it calls are replaced by mocks (`UserClient` for the Claim Service, `ClaimClient` for the Assessment Service) | Last run: see section 12 |
| **Automated, whole system** | `microservices/system-tests`: the three services started together (own database and random port each, real HTTP between them) | Last run: see section 12 |
| **Real jars** | `microservices/scripts/start-all.ps1` starts the three packaged jars; `smoke-test.ps1` runs the flow in section 11 against them as real users | Run for real on spare ports with H2; not through the Swagger UI itself (section 12) |

**The replayed monolith tests were deliberately changed.** The original monolith's HTTP test classes are still replayed in `system-tests` (`AssessmentNotesTest`, `AssignmentHistoryTest`, `ClaimCreationTest`, `ClaimWorkflowTest`, `ConcurrentTransitionTest`, `CurrencyHandlingTest`, `EndpointContractTest`, `ManagementSummaryTest`, `OfficerMetricsTest`, `OfficerSearchTest`, `PlaceholderActorTest`, `StatusHistoryTest`, `UnassignedFilterTest`, plus `DemoFlowTest`), but they are no longer "unchanged": they were adapted to the new model (the monolith's own test classes in `src/test` are untouched and still pass, section 12). What changed in the replayed copies, and nothing else:

- officers are real accounts with lower-case names (`off-1`, `off-1007`, ...; `SystemUnderTest` creates them), because a claim can only be assigned to a real, active claims officer;
- field and status names: `incidentDescription` and `policyNumber` in the request, `approvedAmount` instead of `settledAmount`, `MORE_INFO_REQUIRED` instead of `AWAITING_INFORMATION`, a decision is `APPROVED` (not `SETTLED`) and a settlement is a separate later call;
- the officer who decides, or asks for information, is the one the claim is assigned to (another officer is a 409 `OFFICER_MISMATCH`);
- the history `actor` of a claimant's step is the username (`test-admin`, the default test identity) instead of the literal `CLAIMANT`, and an assignment records who made it;
- the management tests count `APPROVED` as outstanding until it is settled.

Every request in those classes that sets no `Authorization` header is sent with a default identity (ADMIN on public routes, SERVICE on `/internal`), so they check each endpoint's behaviour for an allowed caller; who is allowed is checked by the classes in section 3b. A case marked "(manual only)" has no dedicated automated test.

## 2. Error codes used in the cases

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation failed on a body (including an officer ID that is the placeholder `string`, and an administrator acting for an officer without naming one), or a constrained query parameter was out of range |
| 400 | `MALFORMED_REQUEST` | Body missing, invalid JSON, unknown enum value (for example a decision of `SETTLED`) or unparseable date/number |
| 400 | `INVALID_PARAMETER` | Query parameter has the wrong type (for example `page=abc`, `status=NOPE`) |
| 400 | `INVALID_MARKET` | `market` is not in the configured `claims.markets.allowed` list (only when the list is set) |
| 400 | `CONFLICTING_FILTERS` | `assigned=false` combined with `assignedOfficerId` |
| 400 | `NO_FIELDS_TO_UPDATE` | `PATCH /api/v1/claims/{claimId}` carried none of the editable fields |
| 400 | `INVALID_OFFICER` | A manager (or administrator) named somebody who is not an **active claims officer** (unknown, inactive, or another role) |
| 400 | `INVALID_APPROVED_AMOUNT` | Approved amount missing/not positive/over the claimed amount, or present on a rejection (replaces the old `INVALID_SETTLED_AMOUNT`, which no longer exists) |
| 400 | `CURRENCY_MISMATCH` | Decision currency differs from the claim currency |
| 400 | `INVALID_ROLE` | A user was given the role `SERVICE`, which only services have (User Service) |
| 400 | `PASSWORD_TOO_LONG` | The password is more than 72 bytes (User Service); BCrypt would silently cut it |
| 401 | `UNAUTHENTICATED` | No token, or one that is malformed, tampered with, signed with another secret, from another issuer, without an expiry, expired, or carrying a role that no longer exists (such as the old `OFFICER`). The reason is never given. Carries `WWW-Authenticate: Bearer`. |
| 401 | `INVALID_CREDENTIALS` | Login with a wrong password, an unknown user, **or a deactivated account**; the same answer for all three |
| 403 | `FORBIDDEN` | A valid token whose role may not use the route (including a person's token on `/internal`, and a service token on a public route) |
| 403 | `OFFICER_IDENTITY_MISMATCH` | A claims officer named another officer in `officerId`, `requestedByOfficerId`, `decidedByOfficerId` or `settledByOfficerId` |
| 403 | `ASSIGNMENT_NOT_PERMITTED` | A claims officer tried to assign a claim to somebody other than themselves |
| 404 | `USER_NOT_FOUND` | No user with that username (User Service) |
| 404 | `CLAIM_NOT_FOUND` | No claim with that `claimId`, **or a claim the caller may not see** (a claimant asking for another claimant's claim, a claims officer asking for a claim assigned to another officer). The answer is identical. |
| 404 | `INFORMATION_REQUEST_NOT_FOUND` | No such information request for that claim |
| 404 | `NOT_FOUND` | Unknown route |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for the route |
| 409 | `INVALID_CLAIM_TRANSITION` | The claim's current status does not allow this action (including settling a claim that is not `APPROVED`, and "A rejected claim cannot be settled") |
| 409 | `OFFICER_MISMATCH` | Review, information request, decision or settlement by an officer other than the one the claim is assigned to |
| 409 | `CLAIM_NOT_EDITABLE` | The claimant tried to change a claim that is no longer `SUBMITTED` |
| 409 | `INFORMATION_REQUEST_ALREADY_ANSWERED` | The information request was already answered |
| 409 | `USERNAME_TAKEN` | Registration or user creation with a username that exists (User Service) |
| 409 | `CANNOT_CHANGE_OWN_ROLE` | An administrator tried to change their own role (User Service) |
| 409 | `CANNOT_DEACTIVATE_SELF` | An administrator tried to deactivate their own account (User Service) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not JSON |
| 500 | `INTERNAL_ERROR` | Unexpected failure (no automated test forces this) |
| 502 | `DOWNSTREAM_ERROR` | A service this one depends on answered with something it cannot use (a 5xx, a 401/403 because the secrets differ, a missing route, an unreadable body). No detail from the other service is passed on. |
| 503 | `DOWNSTREAM_UNAVAILABLE` | A service this one depends on could not be reached or did not answer in time. Nothing was changed by the failed call (see section 3a). |

Business errors raised by another service (`CLAIM_NOT_FOUND`, `INVALID_CLAIM_TRANSITION`, `OFFICER_MISMATCH`, `CURRENCY_MISMATCH`, `INVALID_APPROVED_AMOUNT`) reach the client with the same status and `code`; only `path` is the path of the service the client called.

## 3. Claim lifecycle (what the cases rely on)

Six statuses, enforced inside the `Claim` entity (so no caller can bypass them):

```text
SUBMITTED --review--> UNDER_REVIEW --information-request--> MORE_INFO_REQUIRED
                          ^   |                                     |
                          |   +--decision: approve--> APPROVED --settlement--> SETTLED   (terminal)
                          |   +--decision: reject---> REJECTED                            (terminal)
                          +----- last OPEN request answered (automatic) or review resumes -+
```

| From | Allowed moves |
|---|---|
| `SUBMITTED` | `UNDER_REVIEW` (review) |
| `UNDER_REVIEW` | `MORE_INFO_REQUIRED` (information request), `APPROVED` or `REJECTED` (decision) |
| `MORE_INFO_REQUIRED` | `UNDER_REVIEW` (automatic when the last `OPEN` request is answered, or an explicit review when none is `OPEN`) |
| `APPROVED` | `SETTLED` (settlement). Not terminal: still outstanding, can still be reassigned. |
| `REJECTED`, `SETTLED` | none (terminal). **A rejected claim can never be settled.** |

- `assign` is allowed in any non-terminal state (including `APPROVED`) and does not change the status.
- Review, information request, decision and settlement are done by the officer the claim is assigned to (an unassigned claim is taken by the officer who reviews it, and when an **administrator** names that officer the name must be an active claims officer: 400 `INVALID_OFFICER` otherwise, see TC-INT-07); another officer gets 409 `OFFICER_MISMATCH`, whose message does not name the assigned officer.
- A claimant may correct their claim only while it is `SUBMITTED`.
- **What changed from the single-step model** (the reason for the V5 migration): `AWAITING_INFORMATION` was renamed `MORE_INFO_REQUIRED`; a decision used to move `UNDER_REVIEW` straight to `SETTLED` with a `settledAmount`, and is now `APPROVED` (with an `approvedAmount`) followed by a separate settlement. Existing claims keep their meaning: rows in the old status are renamed, a claim that was `SETTLED` in one step stays `SETTLED` with its old amount as its approved amount (its history still reads `UNDER_REVIEW` to `SETTLED`).

## 3a. Service-to-service API (not for claimants or officers)

The services call each other over REST. The dependency graph is fixed: **Assessment calls Claim, Claim calls User, and nobody else calls anybody** (every service checks tokens itself and never asks the User Service whether a token is valid). These routes accept **only a SERVICE token**, which each service mints for its own calls (subject = the calling service's name, valid 60 seconds). A person's token, an ADMIN's included, gets **403** `FORBIDDEN`; no token or an invalid one gets **401** `UNAUTHENTICATED`. Anyone who knows the shared `JWT_SECRET` can mint a service token, so the ports must still stay on a trusted network. They are listed in each service's Swagger UI under the tag **Internal (service-to-service)**.

| Service | Route | Called by | Purpose |
|---|---|---|---|
| Claim | `GET /internal/v1/claims/{claimId}/status` | Assessment | `{claimId, status, claimantId, assignedOfficerId}`: lifecycle position, who submitted it (null for a claim from before owners were recorded) and who has it (null when unassigned), no claimant data; 404 `CLAIM_NOT_FOUND`. The Assessment Service applies the visibility rule with it. |
| Claim | `POST /internal/v1/claims/{claimId}/review` body `{officerId, hasOpenInformationRequests, officerVerified}` (`officerVerified` optional, absent means false) | Assessment | `SUBMITTED` or `MORE_INFO_REQUIRED` to `UNDER_REVIEW`; assigns the reviewer when unassigned. When `officerVerified` is not true (an administrator named the officer) and the claim is unassigned, the Claim Service first asks the User Service that `officerId` is an active `CLAIMS_OFFICER`: otherwise **400 `INVALID_OFFICER`**, nothing changes (503 if the User Service is down). The Assessment Service sets `officerVerified` true only for a claims officer's own token |
| Claim | `POST /internal/v1/claims/{claimId}/request-information` body `{requestedByOfficerId}` | Assessment | `UNDER_REVIEW` to `MORE_INFO_REQUIRED`, only by the assigned officer |
| Claim | `POST /internal/v1/claims/{claimId}/resume-after-information` optional body `{answeredBy}` | Assessment | `MORE_INFO_REQUIRED` to `UNDER_REVIEW`; history actor is `answeredBy` (the claimant's username), or the literal `CLAIMANT` when no body is sent |
| Claim | `POST /internal/v1/claims/{claimId}/decision` body `{decision: APPROVED\|REJECTED, decisionReason, approvedAmount, currency, decidedByOfficerId}` | Assessment | `UNDER_REVIEW` to `APPROVED`/`REJECTED`, only by the assigned officer, with the amount and currency rules |
| Claim | `POST /internal/v1/claims/{claimId}/settlement` body `{settledByOfficerId}` | Assessment | `APPROVED` to `SETTLED`, only by the assigned officer |
| User | `GET /internal/v1/users/{username}` | Claim | `{username, role, active}` and nothing else: lets the Claim Service check that an officer a manager names is an **active `CLAIMS_OFFICER`** before it assigns a claim; 404 `USER_NOT_FOUND` |

**Removed in Phase 1** (the functionality moved, the routes are gone and answer 404): `GET /internal/v1/claims/stats/summary` and `.../stats/officers` (the reports are now computed inside the Claim Service) and `GET /internal/v1/claims/{claimId}/information-requests/open` (information requests live in the Assessment Service, so the "any request still open?" check is a local query).

| ID | Scenario | Expected | Automated |
|---|---|---|---|
| TC-INT-01 | Internal transitions follow the lifecycle rules (review twice, review by another officer, request from `SUBMITTED`, resume when not waiting, duplicate decision, decision amount/currency rules, settlement rules) | `INVALID_CLAIM_TRANSITION`, `OFFICER_MISMATCH`, `CURRENCY_MISMATCH`, `INVALID_APPROVED_AMOUNT` with the same statuses as the public endpoints | claim-service `InternalClaimApiTest` (29 tests), `ClaimDomainTest` |
| TC-INT-02 | Internal request bodies are validated | `{}`, missing `hasOpenInformationRequests`, blank or placeholder officer ID, a decision of `SETTLED`: **400**, nothing changed; a missing body is 400 on every route except `resume-after-information` | `InternalClaimApiTest`, claim-service `EndpointContractTest` (the five body-taking POST routes) |
| TC-INT-03 | Every transition writes its history rows in the same transaction | One status row per change; one assignment row per assignee change; a refused call writes nothing | `InternalClaimApiTest`, `HistoryEndpointsTest` |
| TC-INT-04 | The reports read the claims table with `GROUP BY` queries inside the Claim Service | Counts per status, outstanding liability per currency, per-officer workload and decisions; the old `stats` routes answer 404 | claim-service `ManagementReportTest`, `ClaimSecurityTest.theStatsRoutesAreGoneBecauseTheReportsNowLiveInThisService` |
| TC-INT-05 | A refused or failed call changes nothing | If the Claim Service answers 404/409/400, the Assessment Service stores no information request, keeps no assessment-history entry and does not save an answer | assessment-service `AssessmentServiceTest`, `InformationRequestTest`, system `ServiceFailureTest` |
| TC-INT-06 | A dependency is down | **503** `DOWNSTREAM_UNAVAILABLE` (message names the service): Assessment with the Claim Service down for review, decision, settlement, information request, answer, list and assessments, with nothing kept; Claim with the User Service down for a **manager's** assignment (an officer taking a claim for themselves needs no lookup, and reads and reports still work); a bad request is still **400** (validation runs first) | system `ServiceFailureTest` (real refused connections), claim-service `AssignmentRulesTest`, `DownstreamClientTest` |
| TC-INT-07 | **Starting a review assigns the claim, so the reviewer's name is checked unless the caller has proved it** (found by the independent review: an administrator could assign a claim to any name through `review`) | Unassigned claim, `officerVerified` absent or false, officer not an active `CLAIMS_OFFICER` (typo, claimant, deactivated): **400 `INVALID_OFFICER`**, claim stays `SUBMITTED` and unassigned, no history row. A real officer: 200 and assigned. `officerVerified: true` (a claims officer's own token): no lookup at all. A claim already assigned: no lookup (a different officer is 409 `OFFICER_MISMATCH`). Unknown claim: 404 even for an ineligible name. User Service down: 503 and nothing changes. Mutation-checked: with the check switched off four of these tests fail | claim-service `InternalClaimApiTest` (eight tests), assessment-service `AssessmentSecurityTest` (the Assessment Service sends `officerVerified` false for an administrator and the refusal reaches the caller), live on the real jars |
| TC-INT-08 | An administrator acting for an officer is traceable | The history shows the officer; the Assessment Service logs one line `ADMIN '<login>' requested '<action>' on claim <id> as officer '<officer>'` per request (also for a refused one); an officer acting as themselves logs nothing; a line break in a name cannot forge a log line | assessment-service `ActingAsTest` (4), seen in the log of the real jars |
| TC-INT-09 | A multi-line text is accepted when a claim is corrected, as it is when created | `PATCH` with `incidentDescription` containing `\n`: **200**; a text of only white space and line breaks: 400 | claim-service `ClaimUpdateTest`, live on the real jars |
| TC-INT-10 | A deactivated account costs the same password check as a wrong password | Login for a deactivated user with the right password: 401 `INVALID_CREDENTIALS` (same body as a wrong password) **after** the BCrypt comparison ran; an unknown user also costs one (not time-measured: the test counts the comparisons) | user-service `UserActiveFlagTest` |
| TC-INT-07 | A dependency answers with something unusable | **502** `DOWNSTREAM_ERROR`; a 404 for a missing route (not `USER_NOT_FOUND`) is not mistaken for "not an officer"; a 500 or 403 is not an answer; no detail from the other service is leaked | claims-common `DownstreamClientTest`, claim-service `UserClientTest` |
| TC-INT-08 | Two answers race for one information request | Exactly one 200, one 409; the claim resumes once | assessment-service `InformationRequestTest.twoConcurrentAnswersToOneRequestOnlyOneIsAccepted`, system `ConcurrentTransitionTest` |
| TC-INT-09 | Internal routes need a service token | No token **401**; a CLAIMANT, CLAIMS_OFFICER, MANAGER or ADMIN token **403** on all six Claim routes (state-changing ones too, and nothing changes) and on the User lookup; a SERVICE token **200**; the Assessment Service has no internal routes of its own | claim-service `ClaimSecurityTest`, user-service `UserActiveFlagTest`, assessment-service `AssessmentSecurityTest`, claims-common `SecurityChainTest`, system `AuthenticationFlowTest` |
| TC-INT-10 | Every outgoing call carries a service token | `Authorization: Bearer` with role `SERVICE`, subject = the calling service's name, short expiry | claims-common `SecurityChainTest` (real local HTTP server) |
| TC-INT-11 | The other service refuses this service's token (secrets differ) | **502** `DOWNSTREAM_ERROR` naming the service (`Claim Service ...` for Assessment to Claim, `User Service ...` for Claim to User), no mention of tokens; nothing changed; never 401 or 403 to the caller | system `ServiceFailureTest` (both directions), claims-common `DownstreamClientTest` |
| TC-INT-12 | The Claim Service asks the User Service who may be assigned | An active `CLAIMS_OFFICER` is eligible; inactive, another role, or an unknown user is not (unknown is "no", not an error); the path is `/internal/v1/users/{username}`; the role stays text so a new role cannot break parsing | claim-service `UserClientTest` (a real local HTTP server), user-service `UserActiveFlagTest` |
| TC-INT-13 | The retired routes are gone from every service | `stats/*` and `.../information-requests/open` answer 404 (or 403 for a person's token) | system `ServiceBoundariesTest.theRetiredServicesRoutesAreGoneFromEveryService`, assessment-service `InformationRequestTest.theOldInternalOpenRequestRouteIsGone...` |

## 3b. Authentication, roles and the User Service

### How to authenticate

1. `POST http://localhost:8185/api/v1/auth/login` with `{"username": "...", "password": "..."}`. It is served by the **User Service only**; the other services only check tokens (on them the same path gives 401 without a token, 404 with one). In the User Service's Swagger UI it is under the group **Authentication**, with one request example per default user.
2. Send `Authorization: Bearer <accessToken>` to any service until `expiresIn` seconds have passed (3600 by default), then log in again.
3. **Default test users** (demo only; switch off with `DEMO_USERS=false`): `admin1` (ADMIN), `officer1` (CLAIMS_OFFICER), `manager1` (MANAGER), `claimant1` (CLAIMANT), all with the password `password123` (or `DEMO_USERS_PASSWORD`). They are created at start-up when missing and never reset. **Only these four are seeded.** The extra accounts `admin2`, `officer2`, `manager2` and `claimant2` used from section 14 on do not exist after a start-up: `admin1` creates them with `POST /api/v1/users` (section 14.2). Besides them, an administrator can be created from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`; administrators create officers and managers, and claimants register themselves. The default users make the system a demo, not a secure deployment.
4. **There is no default signing secret.** Every service reads `JWT_SECRET` (at least 32 characters, the **same** value for all three) and refuses to start without it. `start-all.ps1` generates a random one for its own run when you set none; it is never printed or saved. A token issued before the role `OFFICER` was renamed `CLAIMS_OFFICER` carries a role that no longer exists and is refused with 401: log in again.

### Role matrix

"own" means a claimant gets the claim only if they submitted it, otherwise the same 404 as for a claim that does not exist. "queue + own" means a claims officer sees claims that are **unassigned** (the queue they can pick up from) and claims assigned **to them**; a claim assigned to another officer answers exactly as an unknown claim (404). ADMIN is allowed on every public route and sees every claim. A route that no service lists is ADMIN-only. There is no role hierarchy.

| Route | CLAIMANT | CLAIMS_OFFICER | MANAGER | ADMIN |
|---|---|---|---|---|
| `POST /api/v1/claims` | yes | - | - | yes |
| `GET /api/v1/claims` (list) | own claims | queue + own | all | all |
| `GET /api/v1/claims/{claimId}`, `.../history`, `.../assignments`, `.../updates` | own | queue + own | yes | yes |
| `PATCH /api/v1/claims/{claimId}` (only while `SUBMITTED`) | own | - | - | yes |
| `GET /api/v1/officer/claims` | - | queue + own | all | all |
| `POST /api/v1/officer/claims/{claimId}/assign` | - | yes, **only to themselves** | yes, to any active claims officer | yes, same officer check as a manager |
| `POST .../review`, `POST .../decision`, `POST .../settlement`, `GET .../assessments` (Assessment) | - | yes (queue + own; acts as themselves) | - | yes (must name the officer) |
| `POST .../information-requests` (officer asks) | - | yes (queue + own; acts as themselves) | - | yes (must name the officer) |
| `GET /api/v1/claims/{claimId}/information-requests` | own | queue + own | yes | yes |
| `POST /api/v1/claims/{claimId}/information` (claimant answers) | own | - | - | yes |
| `GET /api/v1/management/summary`, `GET /api/v1/management/officers` | - | - | yes | yes |
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | open | open | open | open |
| `GET /api/v1/users/me` | yes | yes | yes | yes |
| `POST /api/v1/users`, `GET /api/v1/users`, `GET /api/v1/users/{username}`, `PUT /api/v1/users/{username}/role`, `PUT /api/v1/users/{username}/active` | - | - | - | yes |
| `GET /actuator/health` | open | open | open | open |
| `/internal/v1/**` (every service) | - | - | - | - (SERVICE token only) |

The assessment history (`GET .../assessments`) is internal: a manager and a claimant get 403 and the body never contains an entry.

### Who an action is recorded against

The officer ID in a request body is **no longer an unverified free-text claim**. The same rule applies to `officerId` (review), `requestedByOfficerId` (information request), `decidedByOfficerId` (decision) and `settledByOfficerId` (settlement):

- **A claims officer** may leave the field out (the officer is the token's user) or repeat their own name. Naming another officer is **403** `OFFICER_IDENTITY_MISMATCH`, and nothing is called or stored.
- **An administrator** acting for an officer must name that officer; leaving it out is **400** `VALIDATION_FAILED`, so an administrator can never become the officer of a claim by omission.
- Whoever acts, the Claim Service still requires that officer to be the one the claim is assigned to (409 `OFFICER_MISMATCH`), and for an officer the claim must be in their scope (404 otherwise).
- Assignment is different: the officer named in `assign` is the **assignee**, not the actor. The actor recorded in the assignment history is the caller's username.

### User Service endpoints (U1 to U8)

| # | Endpoint | Request | Success |
|---|---|---|---|
| U1 | `POST /api/v1/auth/register` | `{"username":"mei.tan","password":"correct-horse-battery"}`: username 3 to 50 characters (lower-case letters, digits, `.`, `_`, `-`, starting with a letter or digit), password 8 to 72 bytes; any `role` field is ignored | **201** `{"username","role":"CLAIMANT","active":true,"createdAt"}` |
| U2 | `POST /api/v1/auth/login` | `{"username","password"}` (username is matched case-insensitively) | **200** `{"accessToken","tokenType":"Bearer","expiresIn":3600,"username","role"}`; a deactivated account gets the same 401 as a wrong password |
| U3 | `GET /api/v1/users/me` | none | **200** the caller's `{username, role, active, createdAt}` |
| U4 | `POST /api/v1/users` | `{"username","password","role"}` with role `CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER` or `ADMIN` | **201** `{username, role, active, createdAt}` (new users are active) |
| U5 | `GET /api/v1/users?page=0&size=20` | `page >= 0`, `1 <= size <= 100` | **200** `{content, page, size, totalElements, totalPages}`, ordered by username, paged in SQL |
| U6 | `GET /api/v1/users/{username}` | none | **200** `{username, role, active, createdAt}` |
| U7 | `PUT /api/v1/users/{username}/role` | `{"role":"CLAIMS_OFFICER"}` | **200** the updated user |
| U8 | `PUT /api/v1/users/{username}/active` | `{"active": false}` or `{"active": true}` (the field is required) | **200** the updated user; **409** `CANNOT_DEACTIVATE_SELF` for the caller's own account |

No response ever contains a password or its hash. Deactivating an account stops new logins; a token already issued keeps working until it expires (there is no revocation: **documented limitation**).

| ID | Scenario | Expected | Automated |
|---|---|---|---|
| TC-AUTH-01 | Register | **201**, role `CLAIMANT`, `active` true, fields exactly `username, role, active, createdAt`; no password or hash in the body | user-service `AuthTest` |
| TC-AUTH-02 | Stored password | Only a 60-character BCrypt hash is stored; the same password for two users gives two different hashes | `AuthTest` |
| TC-AUTH-03 | Register with `"role":"ADMIN"` in the body | **201** but the role is `CLAIMANT` | `AuthTest` |
| TC-AUTH-04 | Register a taken username | **409** `USERNAME_TAKEN`; the first account and its password are unchanged | `AuthTest` |
| TC-AUTH-05 | Bad username (blank, 2 characters, upper case, space, leading `-`, 51 characters) or bad password (7 characters, 73 characters, blank) | **400** `VALIDATION_FAILED`; nothing stored | `AuthTest` |
| TC-AUTH-06 | A password of 72 characters but more than 72 bytes | **400** `PASSWORD_TOO_LONG` (not silently cut) | `AuthTest` |
| TC-AUTH-07 | Missing fields / malformed JSON / no body | **400** `VALIDATION_FAILED` / `MALFORMED_REQUEST` | `AuthTest` |
| TC-AUTH-08 | Wrong content type / `GET` on a `POST` route | **415** / **405** | `AuthTest` |
| TC-AUTH-09 | Login | **200**; the token parses to the same username and role; `expiresIn` 3600 | `AuthTest` |
| TC-AUTH-10 | The token from login is accepted by the service | `GET /api/v1/users/me` with it returns the user | `AuthTest` |
| TC-AUTH-11 | Wrong password and unknown user | Both **401** `INVALID_CREDENTIALS` with the same message, which names neither the user nor the reason | `AuthTest` |
| TC-AUTH-12 | Username in another case | Login works for `MEI.TAN`; a wrong-case password does not | `AuthTest` |
| TC-AUTH-13 | Login with a missing or blank field | **400**, never a token | `AuthTest` |
| TC-AUTH-14 | Login while sending a garbage `Authorization` header | **200** (login ignores it) | `AuthTest` |
| TC-AUTH-15 | SQL-looking username and password | **401**; the users table is untouched | `AuthTest` |
| TC-USR-01 | ADMIN creates users of every person role | **201** each; each can log in with that role | `UserAdminTest` |
| TC-USR-02 | Give a user the role `SERVICE` (create or change) | **400** `INVALID_ROLE`; the stored role is unchanged | `UserAdminTest` |
| TC-USR-03 | Create with a missing role, an unknown role (`SUPERUSER`), a duplicate | **400** `VALIDATION_FAILED`, **400** `MALFORMED_REQUEST`, **409** `USERNAME_TAKEN` | `UserAdminTest` |
| TC-USR-04 | Get one user / unknown user | **200**, no hash / **404** `USER_NOT_FOUND` (also for change role) | `UserAdminTest` |
| TC-USR-05 | List with `page=0&size=2` over five users | Ordered by username, `totalElements` 5, `totalPages` 3, last page has one user | `UserAdminTest` |
| TC-USR-06 | `page=-1`, `size=0`, `size=101`, `page=abc` | **400** | `UserAdminTest` |
| TC-USR-07 | Change a role | **200**; the next login carries the new role | `UserAdminTest` |
| TC-USR-08 | A token issued before a role change | Keeps its old role until it expires (**documented limitation**; the test records it so a change is deliberate) | `UserAdminTest` |
| TC-USR-09 | Change the caller's own role | **409** `CANNOT_CHANGE_OWN_ROLE`; another administrator's role can be changed | `UserAdminTest` |
| TC-USR-10 | `/me` for each person role; without a token; with a service token; with a valid token for a user that no longer exists | **200** / **401** / **403** / **404** `USER_NOT_FOUND` | `UserAdminTest` |
| TC-USR-11 | A CLAIMANT, CLAIMS_OFFICER, MANAGER or SERVICE calls any user-management route (create, list, get, change role, **activate/deactivate**); a claimant tries to promote themselves | **403** each time; nothing changes | `UserAdminTest` |
| TC-USR-12 | Bootstrap administrator from the environment | Created once with the ADMIN role; an existing user is never overwritten; half a configuration or a weak password stops start-up; the message never repeats the password | `AdminBootstrapTest` |
| TC-ACT-01 | New users (created or registered) are active | `active` true in the response and when read back | user-service `UserActiveFlagTest` |
| TC-ACT-02 | A deactivated user logs in | **401** `INVALID_CREDENTIALS`, the same code and message as a wrong password; nothing in the body says "disabled", "inactive" or "active" | `UserActiveFlagTest` |
| TC-ACT-03 | Reactivate | **200** `active` true; login works again with the same role | `UserActiveFlagTest` |
| TC-ACT-04 | A token issued before deactivation | Still works until it expires (**documented limitation**; recorded by the test) | `UserActiveFlagTest` |
| TC-ACT-05 | An administrator deactivates themselves / another administrator | **409** `CANNOT_DEACTIVATE_SELF` (stays active) / **200** | `UserActiveFlagTest` |
| TC-ACT-06 | Missing or non-boolean `active`; unknown user | **400** `VALIDATION_FAILED` / `MALFORMED_REQUEST`; **404** `USER_NOT_FOUND` | `UserActiveFlagTest` |
| TC-ACT-07 | Only an administrator may switch an account (CLAIMANT, CLAIMS_OFFICER, MANAGER, SERVICE get 403, no token 401) | Nothing changes | `UserActiveFlagTest`, `UserAdminTest` |
| TC-ACT-08 | End to end: a deactivated officer cannot log in **and cannot be assigned a claim** (`INVALID_OFFICER`); reactivated, both work | as stated | system `AuthenticationFlowTest.aDeactivatedPersonCannotLogInAndCannotBeAssignedAClaim`; smoke test section 9 |
| TC-ACT-09 | Internal lookup for the Claim Service | A SERVICE token gets `{username, role, active}` and nothing else (no password or hash), including `active:false`; unknown user **404**; every person's token and no token are refused | `UserActiveFlagTest` |
| TC-MIG-01 | User migration V2 over a database that already holds users | An `OFFICER` row becomes `CLAIMS_OFFICER`, other roles and all rows are kept, every user becomes active; the old role name can no longer be stored, the new one can | user-service `UserMigrationTest` (H2, Flyway V1 then V2 over inserted rows) |
| TC-DEMO-01 | One default user per role after start-up | `admin1` ADMIN, `officer1` CLAIMS_OFFICER, `manager1` MANAGER, `claimant1` CLAIMANT, nothing else | user-service `DemoUsersTest` |
| TC-DEMO-02 | Each default user logs in with the configured password | **200**, `tokenType` Bearer, the right `role`, a token that parses to that user and role | `DemoUsersTest`; system `DemoUsersFlowTest` (with the documented `password123`) |
| TC-DEMO-03 | The token opens the routes of its role and no others | `/me` 200; only `admin1` lists users (total 4), the others get **403** | `DemoUsersTest` |
| TC-DEMO-04 | Wrong password | **401** `INVALID_CREDENTIALS` | `DemoUsersTest` |
| TC-DEMO-05 | The password is stored as a BCrypt hash; a second start-up neither duplicates nor resets a user | 4 rows; a changed hash is kept | `DemoUsersTest` |
| TC-DEMO-06 | Switch and password rules | Off: nothing created or looked up; on with a password under 8 characters or none: start-up fails without echoing it; an existing user is skipped and the others are still created; only person roles | user-service `DemoUsersUnitTest` |
| TC-DEMO-07 | Log in as the default users, then call protected APIs on the Claim and Assessment services | claimant1 creates, reads and lists a claim; **manager1 assigns it to officer1** (the Claim Service asks the User Service); officer1 reviews as themselves (Assessment calls Claim) and asks for information; claimant1 sees the request; manager1 reads both reports; wrong roles get **403**; no token **401** naming the login route | system `DemoUsersFlowTest`; real jars, section 12 |
| TC-CFG-01 | No service ships a signing secret | Each of the three `application.properties` has exactly `claims.security.jwt.secret=${JWT_SECRET:}` (environment variable only, no default) | system `JwtSecretConfigurationTest` **(FAILS on commit `ee90578`, run 2026-10-27: the committed files carry a literal fallback after `JWT_SECRET:`; see the update in section 12)** |
| TC-CFG-02 | A service refuses to start without it, or with a short one | The message names `JWT_SECRET` and "at least 32 characters"; the port is never opened | claims-common `JwtUtilTest` (too short, missing); **real jar started with no `JWT_SECRET`** (section 12) **(the real-jar part was NOT reproduced on commit `ee90578`: a jar started with no `JWT_SECRET` answered health UP; see the update in section 12)** |
| TC-CFG-03 | No credential literal in any packaged configuration | Every key containing `password` or `secret`, in `application.properties` and `application-postgres.properties` of the three services, is an environment placeholder or empty; the only literal allowed is the documented public demo password; no database URL carries a user or password | `JwtSecretConfigurationTest.noPackagedConfigurationFile...` **(FAILS on commit `ee90578` for the `claims.security.jwt.secret` line, run 2026-10-27; see the update in section 12)** |
| TC-CFG-04 | Database settings are per service | The datasource URL, user and password of each service come from `USER_DB_*`, `CLAIM_DB_*` and `ASSESSMENT_DB_*` respectively, so one cannot be pointed at another's database by accident | `JwtSecretConfigurationTest.theEnvironmentVariablesAreNamedAfterTheirService...` |
| TC-DB-01 | No two services share a database by default | The packaged `spring.datasource.url` defaults of the User, Claim and Assessment services are all different (a shared URL fails with a message naming it and the reason); the three `application-postgres.properties` name three different databases (`user_db`, `claim_db`, `assessment_db`) | system `DatabasePerServiceConfigurationTest` |
| TC-DB-02 | Two services in one database fail on version 1, and Flyway repair only moves the failure | Claim then User on one in-memory database: **FlywayValidateException** "Migration checksum mismatch for migration version 1"; after `repair` from the User side the **Claim** migration fails the same way and no `users` table exists; one database per service migrates cleanly with default settings | `DatabasePerServiceConfigurationTest` (in-memory H2 only) |
| TC-DB-03 | The User Service on a real PostgreSQL database (history) | Before Phase 1, on 2026-10-26, against a local PostgreSQL 17.6: Flyway validated and applied V1 in `user_db`, demo users were inserted, login worked, tokens were accepted by the already running Claim Service. **That run predates V2 (user), V5 (claim) and V2 (assessment): none of the new migrations has been run on PostgreSQL (NOT TESTED).** | manual, historical; not an automated test |
| TC-DOC-01 | Login is documented in Swagger/OpenAPI | `/v3/api-docs` of the User Service lists `/api/v1/auth/login` and `/register` under the tag *Authentication* with 200/400/401 responses and four request examples; the Swagger UI page answers 200; login and register carry no security requirement | the paths, titles, the bearer scheme and the missing security requirement: system `ServiceBoundariesTest`; the response list and request examples were checked once on a real jar earlier, not by a test |
| TC-ACL-01 | Every public route, as every role and with no token | Exactly as the service's rule table says: allowed roles are never 401/403, others get **403**, no token gets **401** (open routes are not 401) | each service's `*SecurityTest`: `UserSecurityTest`, `ClaimSecurityTest`, `AssessmentSecurityTest` |
| TC-ACL-02 | Each service's rule table equals the role matrix above | Compared with a hand-written copy (11 rows for the Claim Service, 7 for the Assessment Service, 8 plus the 2 open ones for the User Service), so a wrong table fails even though the service enforces it faithfully | the same classes, `theRuleTableIsTheDocumentedRoleMatrix` |
| TC-ACL-03 | A new `/api/v1` route without an access rule | The test fails | the same classes, `everyApiRouteHasAnAccessRule` (`RouteCoverage`) |
| TC-ACL-04 | No token / tampered / expired / signed with another secret / from another issuer / no expiry / `alg=none` / HS512 / unknown role (including the retired `OFFICER`) | **401** `UNAUTHENTICATED` with the standard error body and `WWW-Authenticate: Bearer` | claims-common `JwtUtilTest`, `SecurityChainTest`; claim-service `ClaimSecurityTest`; system `AuthenticationFlowTest.aTokenFromAnotherSecretOrAnExpiredOneIsRefusedByEveryService` |
| TC-ACL-05 | Only the `Bearer` scheme | `Basic`, a bare token and `Token <t>` are **401**; `bearer <t>` (any case) works | `SecurityChainTest` |
| TC-ACL-06 | A route no service lists, and a method the table does not list | No token **401**; every role except ADMIN **403**; ADMIN gets through to the route's normal answer (its response if it exists, otherwise 404 or 405) | `SecurityChainTest` |
| TC-ACL-07 | Swagger UI and `/v3/api-docs` | No token needed on every service; each document declares the `bearerAuth` scheme, and `login` and `register` are documented as needing no token | `SecurityChainTest`, system `ServiceBoundariesTest` |
| TC-ACL-08 | Access denied thrown inside a controller or service | **403** `FORBIDDEN`, never a 500 | `SecurityChainTest`, `GlobalExceptionHandlerTest` |
| TC-ACL-09 | An unknown route with no token | **401**, so the list of routes is not revealed | system `ServiceBoundariesTest` |
| TC-ACL-10 | Assessment history | Readable by CLAIMS_OFFICER (in scope) and ADMIN only; a CLAIMANT, a MANAGER and a service token get **403** and the body never contains an entry | assessment-service `AssessmentSecurityTest`, system `AuthenticationFlowTest` |
| TC-ACL-11 | Reports | MANAGER and ADMIN **200**; CLAIMANT, CLAIMS_OFFICER and a service token **403**; no token **401** | claim-service `ManagementReportTest.onlyManagersAndAdministratorsMayReadTheReports`, system `AuthenticationFlowTest` |
| TC-HLT-01 | Health | `GET /actuator/health` on each service, no token: **200** `{"status":"UP"}` and **nothing else** in the body (no details); `/actuator/env` is 401 without a token and 404 with an administrator's token (not exposed); `/actuator/beans` 404 | system `ServiceBoundariesTest`; `SecurityChainTest` (health open, other actuator paths still 401); real jars (smoke test section 0). The `DOWN` answer (database unreachable) was not tested. |
| TC-OWN-01 | The owner reads their claim, its history, assignments and edit history | **200**; the response carries `claimantId` (their own username) and no other claimant's data | claim-service `ClaimSecurityTest` |
| TC-OWN-02 | The owner is the token's username, not a name or an ID in the body | A claim created by `claimant.a` with another claimant name in the body is owned by `claimant.a`; a `claimantId` in the body is ignored | `ClaimSecurityTest`, `ClaimCreationTest` |
| TC-OWN-03 | Another claimant reads the claim, history, assignments or edit history | **404** `CLAIM_NOT_FOUND` with the same body shape as an unknown claim; nothing about the real owner or claimant | `ClaimSecurityTest`, system `AuthenticationFlowTest` |
| TC-OWN-04 | MANAGER and ADMIN read any claim | **200** | `ClaimSecurityTest` |
| TC-OWN-05 | A claim with no recorded owner (created before owners were recorded) | Staff **200**, any claimant **404** | `ClaimSecurityTest` |
| TC-OWN-06 | A similar username (prefix, different case, trailing space) | **404**: the owner is compared exactly | `ClaimSecurityTest` |
| TC-OWN-07 | Another claimant lists information requests, or answers one | **404** `CLAIM_NOT_FOUND` (before anything is said about the request or the claim's status); the request stays `OPEN` and the claim is not resumed | assessment-service `AssessmentSecurityTest`, system `AuthenticationFlowTest` |
| TC-OWN-08 | A CLAIMS_OFFICER answers for the claimant; an ADMIN does | **403**; **200** | `AssessmentSecurityTest` |
| TC-OWN-09 | The same rule, as one shared piece of code | `ClaimVisibility.canSee` for claimant, officer, manager, administrator and a claim with no owner or no officer; the 404 it raises has the same code and message as an unknown claim | claims-common `ClaimVisibilityTest` |
| TC-VIS-01 | A claims officer reads unassigned claims and their own, and **not** another officer's claim | **200** / **200** / **404** `CLAIM_NOT_FOUND` for the claim, its history, assignments and edit history; the 404 mentions neither the other officer nor the claimant | claim-service `ClaimSecurityTest.aClaimsOfficerReadsUnassignedClaimsAndTheirOwnButNotAnotherOfficersClaims` |
| TC-VIS-02 | The same scope on the Assessment Service | Review, decision, settlement, information request, assessment history and the request list for a claim assigned to another officer are all **404**; nothing is stored and the Claim Service's transition routes are not called; an unassigned claim or their own is fine | assessment-service `AssessmentSecurityTest.anOfficerCannotTouchAClaimAssignedToAnotherOfficer...`, `anOfficerMayStartOnAnUnassignedClaimOrOneAssignedToThem` |
| TC-VIS-03 | Lists are scoped in SQL, never in memory | See TC-LST-06 to TC-LST-09 | `ClaimListTest` |
| TC-IDN-01 | An officer who omits the officer ID acts as the token's user on every route | Review, information request, decision and settlement are recorded against the token's username; the response echoes it | assessment-service `AssessmentSecurityTest.anOfficerWhoOmitsTheOfficerIdActsAsTheTokensUser` |
| TC-IDN-02 | An officer who repeats their own name | Fine | `AssessmentSecurityTest` |
| TC-IDN-03 | An officer names another officer on any of the four routes | **403** `OFFICER_IDENTITY_MISMATCH`; the Claim Service is not called; nothing is stored | `AssessmentSecurityTest`, claims-common `ClaimVisibilityTest` |
| TC-IDN-04 | An administrator names no officer (missing, empty or blank) | **400** `VALIDATION_FAILED` (a message about the officer ID); nothing is called; naming an officer works | `AssessmentSecurityTest.anAdministratorMustNameTheOfficer...`, `ClaimVisibilityTest` |
| TC-E2E-01 | The whole journey with real logins | Claimant submits and corrects; manager assigns (and cannot assign to a non-officer); officer reviews, asks; claimant answers and the claim resumes; officer approves and settles; manager reads the reports; each wrong role and each other officer is refused along the way; the assessment history holds NOTE, APPROVED, SETTLED | system `AuthenticationFlowTest` (9 tests) |
| TC-E2E-02 | The same, against three real processes | 81 checks, as in section 12 | `microservices/scripts/smoke-test.ps1` |

## 3c. Health

`GET /actuator/health` on every service (User, Claim, Assessment), **no token needed**: **200** `{"status":"UP"}`. Only the health endpoint is exposed (`management.endpoints.web.exposure.include=health`, `show-details=never`), so the body never carries database details. When the service's database cannot be reached the same URL reports `DOWN` with HTTP 503 (configured, not tested). `start-all.ps1` waits for it before reporting a service as up. Test cases: TC-HLT-01 above.

## 4. Endpoint 1 - Create a claim

`POST /api/v1/claims` (Claim Service, role CLAIMANT or ADMIN) - automated: claim-service `ClaimCreationTest`, `EndpointContractTest`; system `ClaimCreationTest`, `EndpointContractTest`

Request fields: `claimantName` (required, max 200), `claimantEmail` (required, valid email, max 254), `policyNumber` (required, 3 to 50 characters: letters, digits, `/`, `_` or `-`, starting with a letter or digit), `market` (2 upper-case letters), `claimType` (`MOTOR` or `PROPERTY`), `incidentDescription` (required, max 2000), `incidentDate` (`yyyy-MM-dd`, not in the future), `claimedAmount` (positive, at most 2 decimals), `currency` (3 upper-case letters). The client never sends `id`, `claimId` or `claimantId`: the claim number is generated by the server and **`claimantId` is the logged-in user** (the token's username). Claimant ID, claimant name and policy number are three separate fields.

Base request used below:

```json
{
  "claimantName": "Mei Tan",
  "claimantEmail": "mei.tan@example.com",
  "policyNumber": "POL-2026-0042",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear bumper damaged in a low-speed collision.",
  "incidentDate": "2026-10-12",
  "claimedAmount": 2400.00,
  "currency": "INR"
}
```

| ID | Scenario | Change to the base request | Expected |
|---|---|---|---|
| TC-CRE-01 | Valid claim | none | **201**. Body has `id` (number), `claimId` matching `CLM-\d{4}-\d{6}`, `claimantId` = the caller, `policyNumber`, `status` `SUBMITTED`, `assignedOfficerId` and `approvedAmount` null, `createdAt`, `updatedAt`. |
| TC-CRE-02 | Two claims get different IDs | create twice | Two different `claimId` values, increasing; `id` differs from `claimId`. |
| TC-CRE-03 | Concurrent creation | many parallel creates | All `claimId` values unique (`ClaimCreationTest`). |
| TC-CRE-04 | Client tries to set IDs/status/claimant | add `"claimId":"CLM-HACK-1","status":"SETTLED","claimantId":"somebody-else"` | **201**; extra fields ignored; generated `claimId` is not `CLM-HACK-1`; status `SUBMITTED`; `claimantId` is the caller. |
| TC-CRE-05 | Amount zero or negative | `claimedAmount` `0` or `-5.00` | **400** `VALIDATION_FAILED`. |
| TC-CRE-06 | Too many decimals | `claimedAmount` `10.001` | **400**. |
| TC-CRE-07 | Bad currency | `"currency":"sgd"` or missing | **400**. |
| TC-CRE-08 | Bad market | `"market":"Singapore"` | **400**. |
| TC-CRE-09 | Unknown claim type | `"claimType":"BOAT"` | **400** `MALFORMED_REQUEST`. |
| TC-CRE-10 | Bad email | `"claimantEmail":"not-an-email"` | **400**. |
| TC-CRE-11 | Future incident date | `"incidentDate":"2999-01-01"` | **400**. |
| TC-CRE-12 | Unparseable date | `"incidentDate":"12/10/2026"` | **400**. |
| TC-CRE-13 | Blank name / over-long description | `"claimantName":"   "`, 2001-char `incidentDescription` | **400**. |
| TC-CRE-14 | Amount is not a number | `"claimedAmount":"lots"` | **400**. |
| TC-CRE-15 | Empty object | `{}` | **400** `VALIDATION_FAILED`; message names the missing fields (including `policyNumber` and `incidentDescription`). |
| TC-CRE-16 | Malformed JSON / no body | `{ this is not json` / empty | **400** `MALFORMED_REQUEST`. |
| TC-CRE-17 | Wrong content type | `Content-Type: text/plain` | **415** `UNSUPPORTED_MEDIA_TYPE`. |
| TC-CRE-18 | Nothing saved on failure | any 400 above | `claims` table stays empty. |
| TC-CRE-19 | Market outside the configured allow-list | `claims.markets.allowed=SG,HK` (test values), `"market":"AU"` | **400** `INVALID_MARKET`; nothing saved. Listed markets give **201**. (`MarketValidationTest`) |
| TC-CRE-20 | No allow-list configured (default) | `"market":"ZZ"` | **201**; any 2-letter upper-case code is still accepted. (`DefaultMarketTest`) |
| TC-CRE-21 | Policy number missing or malformed | remove `policyNumber`; or `""`, `"  "`, `"AB"`, `"POL 123"`, `"-POL-1"`, `"POL;DROP TABLE"`, 51 characters | **400** `VALIDATION_FAILED`; nothing saved. |
| TC-CRE-22 | Claimant name, claimant ID and policy number are separate | base request | The response has `claimantName` "Mei Tan", `claimantId` (the login name) and `policyNumber` as three distinct values. (`ClaimCreationTest`) |
| TC-CRE-23 | History of creation | base request | One status-history row, `actor` = the caller's username (not the literal `CLAIMANT`). (`HistoryEndpointsTest`) |

Sample `201` response:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantId": "claimant1",
  "claimantName": "Mei Tan",
  "claimantEmail": "mei.tan@example.com",
  "policyNumber": "POL-2026-0042",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear bumper damaged in a low-speed collision.",
  "incidentDate": "2026-10-12",
  "claimedAmount": 2400.00,
  "approvedAmount": null,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-18T07:10:00Z",
  "updatedAt": "2026-10-18T07:10:00Z"
}
```

Sample `400` response:

```json
{
  "timestamp": "2026-10-18T07:10:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "claimedAmount must be greater than 0",
  "path": "/api/v1/claims"
}
```

## 5. Endpoint 2 - Retrieve a claim

`GET /api/v1/claims/{claimId}` (Claim Service; claimant: own claims, officer: queue + own) - automated: `ClaimCreationTest`, `ClaimSecurityTest`, `EndpointContractTest`; system `ClaimWorkflowTest`

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-GET-01 | Existing claim | `GET /api/v1/claims/CLM-2026-000101` | **200**; same body shape as the create response, same `claimId`, current `status`. |
| TC-GET-02 | `claimId` is stable | GET after assign/review | **200**; same `claimId`, updated `status`. |
| TC-GET-03 | After a decision | GET after approve | **200**; `status` `APPROVED`, `decisionReason`, `approvedAmount` and `decidedAt` populated, `settledAt` null. After settlement: `status` `SETTLED`, `settledAt` populated. |
| TC-GET-04 | Unknown claim | `GET /api/v1/claims/CLM-2026-999999` | **404** `CLAIM_NOT_FOUND`; `path` echoes the URL; no stack trace. |
| TC-GET-05 | Internal numeric id in the public route | `GET /api/v1/claims/1` | **404** (the route only accepts `claimId`). |
| TC-GET-06 | Wrong method | `PUT` or `DELETE` on the same URL | **405** `METHOD_NOT_ALLOWED` (`PATCH` is endpoint 16). |
| TC-GET-07 | A claim the caller may not see | another claimant's claim; a claim assigned to another officer | **404** `CLAIM_NOT_FOUND`, identical to an unknown claim (TC-OWN-03, TC-VIS-01). |
| TC-GET-08 | There is no way to delete a claim | `DELETE /api/v1/claims/{claimId}` | **405**; no delete route exists on any service. |

## 6. Endpoint 3 - Search officer workload

`GET /api/v1/officer/claims` (Claim Service; CLAIMS_OFFICER, MANAGER, ADMIN) - automated: claim-service `OfficerSearchTest`, `UnassignedFilterTest`, `ClaimListTest`, `EndpointContractTest`; system `OfficerSearchTest`, `UnassignedFilterTest`

The staff view of the same list as endpoint 15 (they share one service method). **Scope:** a claims officer sees the unassigned claims and their own; a manager and an administrator see every claim; the filters narrow that and can never widen it.

Query parameters (all optional): `status`, `market` (exact, case-sensitive match), `claimType`, `assignedOfficerId`, `assigned` (`true` = has an officer, `false` = unassigned, i.e. `assigned_officer_id IS NULL`), `page` (default `0`, min `0`), `size` (default `20`, min `1`, max `100`). Results are newest first. Filtering, scoping and paging run in SQL.

Seed used by `OfficerSearchTest`: SG MOTOR `SUBMITTED`; SG PROPERTY `UNDER_REVIEW` (off-1); AU MOTOR `SUBMITTED` assigned to off-2 (run as the administrator, so unscoped).

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-SRCH-01 | No filters | `GET /api/v1/officer/claims` | **200**; `page` 0, `size` 20, `totalElements` 3, newest first. |
| TC-SRCH-02 | Filter by status | `?status=UNDER_REVIEW` | **200**; only the matching claim. |
| TC-SRCH-03 | Filter by market / type / officer | `?market=SG`, `?claimType=MOTOR`, `?assignedOfficerId=off-2` | **200**; only matching claims. |
| TC-SRCH-04 | Combined filters | `?status=SUBMITTED&market=SG` | **200**; both filters applied (AND). |
| TC-SRCH-05 | Pagination in the database | `?size=1&page=1` | **200**; one item, correct `totalElements` and `totalPages`. |
| TC-SRCH-06 | Summary item fields | any | Items have `id`, `claimId`, `claimantId`, `claimantName`, `policyNumber`, `market`, `claimType`, `claimedAmount`, `approvedAmount`, `currency`, `status`, `assignedOfficerId`, `createdAt` and **no** `incidentDescription` or `claimantEmail`. |
| TC-SRCH-07 | Bad enum value | `?status=NOPE`, `?claimType=BOAT` | **400** `INVALID_PARAMETER`. |
| TC-SRCH-08 | Bad paging values | `?page=-1`, `?size=0`, `?size=101`, `?page=abc` | **400**. `?size=100` is **200**. |
| TC-SRCH-10 | Unassigned claims | `?assigned=false` | **200**; only claims with `assignedOfficerId` null, newest first. (`UnassignedFilterTest`) |
| TC-SRCH-11 | Assigned claims | `?assigned=true` | **200**; only claims with an officer. `?assigned=true&assignedOfficerId=off-1` is allowed. |
| TC-SRCH-12 | Unassigned combined with other filters and paging | `?assigned=false&market=AU`, `?assigned=false&status=UNDER_REVIEW`, `?assigned=false&size=1` | **200**; AND semantics, correct `totalElements` and `totalPages`. A claim leaves the unassigned list once assigned. |
| TC-SRCH-13 | Invalid `assigned` value | `?assigned=maybe` | **400** `INVALID_PARAMETER`. |
| TC-SRCH-14 | Contradictory filters | `?assigned=false&assignedOfficerId=off-1` | **400** `CONFLICTING_FILTERS`. |
| TC-SRCH-15 | Empty results | `?assigned=true&market=ZZ`, or `?assigned=false` once every claim is assigned | **200**; empty `content`, `totalElements` 0, `totalPages` 0. (`UnassignedFilterTest`) |
| TC-SRCH-09 | No match | `?market=ZZ` | **200**; empty `content`, `totalElements` 0 (covered through TC-SRCH-15). |
| TC-SRCH-16 | The staff route and the claims route give the same scoped list | officer token on `GET /api/v1/officer/claims` vs `GET /api/v1/claims` | Same claims. (`ClaimListTest.theOfficerRouteGivesTheSameScopedListAsTheClaimsRoute`) |
| TC-SRCH-17 | A claimant on the staff route | any | **403** (they use endpoint 15). |

Sample response:

```json
{
  "content": [
    {
      "id": 1,
      "claimId": "CLM-2026-000101",
      "claimantId": "claimant1",
      "claimantName": "Mei Tan",
      "policyNumber": "POL-2026-0042",
      "market": "SG",
      "claimType": "MOTOR",
      "claimedAmount": 2400.00,
      "approvedAmount": null,
      "currency": "INR",
      "status": "SUBMITTED",
      "assignedOfficerId": null,
      "createdAt": "2026-10-18T07:10:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`?assigned=false&assignedOfficerId=off-1` returns:

```json
{
  "timestamp": "2026-10-18T07:10:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "CONFLICTING_FILTERS",
  "message": "assigned=false cannot be combined with assignedOfficerId, because an unassigned claim has no officer",
  "path": "/api/v1/officer/claims"
}
```

## 7. Endpoints 4 and 5 - Assign an officer (Claim Service), start/resume review (Assessment Service)

`POST /api/v1/officer/claims/{claimId}/assign` and `POST /api/v1/officer/claims/{claimId}/review` - automated: claim-service `AssignmentRulesTest`, `ClaimDomainTest`, `InternalClaimApiTest`, `EndpointContractTest`; assessment-service `AssessmentServiceTest`, `AssessmentSecurityTest`; system `ClaimWorkflowTest`, `AssignmentHistoryTest`

Request for `assign`: `{ "officerId": "officer1" }`. `officerId` is the **assignee**: required, not blank, max 50 characters, and not the placeholder `string`.

**Who may assign, and to whom** (checked in this order):

1. A **claims officer** may only take a claim for themselves (the queue is "pick up what is unassigned"). Naming another officer is **403** `ASSIGNMENT_NOT_PERMITTED`. No lookup is made: their own role and active state are proven by the token.
2. A **manager** (and an administrator) may name any officer, who must be an **active claims officer**: the User Service is asked (`GET /internal/v1/users/{username}`) **before** the claim is locked, so no database lock is held across the call. A user who does not exist, is inactive, or is not a `CLAIMS_OFFICER` (a claimant, a manager, a typo) is **400** `INVALID_OFFICER`; an unreachable User Service is **503** `DOWNSTREAM_UNAVAILABLE` and nothing is assigned.
3. The claim must exist and be visible to the caller (a claims officer cannot touch a claim assigned to another officer: **404**), and must not be `SETTLED` or `REJECTED` (**409**).

Every change of assignee appends an assignment-history row whose `actor` is the **caller's username**.

Request for `review` (Assessment Service): `{ "assessmentNotes": "Photos match the described damage." }`. `officerId` is **optional** (a claims officer reviews as themselves; an administrator must name the officer, section 3b), max 50 characters, never the placeholder `string`. `assessmentNotes` is optional, max 2000 characters; a missing, null or blank value saves nothing. See section 10d.

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-ASG-01 | Assign a submitted claim | `assign` with `off-1007` (as administrator or manager) | **200** `{claimId, status:"SUBMITTED", assignedOfficerId:"off-1007", updatedAt}`. |
| TC-ASG-02 | Reassign before review | `assign` with `off-2` | **200**; `assignedOfficerId` `off-2`; the assignment history keeps both. |
| TC-ASG-03 | Blank / over-long officer | `""`, `"   "`, 51 characters | **400**; claim unchanged. |
| TC-ASG-04 | Empty / malformed body, wrong content type | `{}` / bad JSON / `text/plain` | **400** / **400** / **415**. |
| TC-ASG-05 | Unknown claim | `assign` on `CLM-2026-999999` with an eligible officer | **404** `CLAIM_NOT_FOUND`. |
| TC-ASG-06 | Terminal claim | `assign` on a settled/rejected claim | **409** `INVALID_CLAIM_TRANSITION`; the assignee is unchanged. An `APPROVED` claim **can** be reassigned. |
| TC-ASG-07 | Wrong method | `GET .../assign` | **405**. |
| TC-ASG-08 | A manager assigns to an active claims officer | `assign` as a manager | **200**; the User Service was asked; the assignment row's `actor` is the manager; no status-history row is added. (`AssignmentRulesTest`) |
| TC-ASG-09 | A manager reassigns | `officer.a` then `officer.b`, then `officer.b` again | Two rows (`null` to `officer.a`, `officer.a` to `officer.b`); the repeat adds none. |
| TC-ASG-10 | A manager (or an administrator) names a non-officer, an inactive officer or an unknown user | `assign` to a claimant's name | **400** `INVALID_OFFICER` ("`<name>` is not an active claims officer"); claim unchanged; no assignment row. |
| TC-ASG-11 | The User Service is down | `assign` as a manager | **503** `DOWNSTREAM_UNAVAILABLE`; claim unchanged. An officer taking a claim for themselves still works. (`AssignmentRulesTest`, system `ServiceFailureTest`) |
| TC-ASG-12 | The officer is checked before the claim | unknown claim with an ineligible officer vs. an eligible one | **400** `INVALID_OFFICER` vs. **404** `CLAIM_NOT_FOUND`. |
| TC-ASG-13 | The name is trimmed | `"  officer.a  "` | Checked and stored as `officer.a`. |
| TC-ASG-14 | A claims officer takes an unassigned claim for themselves | `assign` with their own name | **200**; no User Service call; `actor` is the officer. |
| TC-ASG-15 | A claims officer names someone else | `assign` with another officer | **403** `ASSIGNMENT_NOT_PERMITTED`; nothing assigned, no User Service call. |
| TC-ASG-16 | A claims officer tries to take a claim assigned to another officer | `assign` with their own name | **404** `CLAIM_NOT_FOUND` (it is outside their scope); the assignee is unchanged. |
| TC-ASG-17 | An officer takes their own claim again | same request | **200**; no new assignment row. |
| TC-ASG-18 | A claimant or a service token assigns; no token | any | **403** / **403** / **401**; nothing changes; no User Service call. |
| TC-ASG-19 | An approved claim handed to another officer | `assign` after approval | **200**; only the new assignee may settle it (the previous one gets 409 `OFFICER_MISMATCH`). |
| TC-REV-01 | Start review | `review` on a `SUBMITTED` claim | **200** `{status:"UNDER_REVIEW", assignedOfficerId}`; the reviewing officer is assigned if none was. |
| TC-REV-02 | Review twice | `review` again | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-REV-03 | Different officer than assigned | claim assigned to `off-1`, review naming `off-2` (administrator acting) | **409** `OFFICER_MISMATCH`. (A claims officer cannot name another officer at all: TC-IDN-03; and cannot see the claim: TC-VIS-02.) |
| TC-REV-04 | Resume while a request is OPEN | claim `MORE_INFO_REQUIRED` with an `OPEN` request, call `review` | **409** `INVALID_CLAIM_TRANSITION`. The Assessment Service looks the open request up in its **own** table and passes the fact to the Claim Service. |
| TC-REV-05 | Terminal claim | `review` on a decided claim | **409**. |
| TC-REV-06 | Validation / unknown claim | placeholder or over-long officer / unknown `claimId` | **400** / **404**. |
| TC-REV-07 | Explicit resume with nothing OPEN | claim `MORE_INFO_REQUIRED`, all requests answered, `review` | **200** `UNDER_REVIEW`; covered by `ClaimDomainTest` and `InformationRequestTest.anOpenRequestStopsAReviewFromResuming...`. |
| TC-REV-08 | Review with `assessmentNotes` | `{"assessmentNotes":"..."}` | **200**; one `NOTE` entry saved (see TC-ASSM-01 in section 10d). |
| TC-REV-09 | A refused review keeps nothing | the Claim Service answers 409 or is down | No note stored. |

## 8. Endpoints 6, 7 and 8 - Information requests (Assessment Service)

Automated: assessment-service `InformationRequestTest`, `AssessmentSecurityTest`, `AssessmentContractTest`; system `ClaimWorkflowTest`, `AuthenticationFlowTest`

These routes were served by the former Information Service. Their paths and JSON are unchanged; they are now on the Assessment Service (port 8182), which owns the `information_requests` table.

### Endpoint 6 - `POST /api/v1/officer/claims/{claimId}/information-requests`

Request: `{ "question": "Please provide the repair estimate." }` (`question` required, max 2000). `requestedByOfficerId` is optional for a claims officer (their own name; another name is 403), required for an administrator (section 3b). Only the officer the claim is assigned to may ask.

| ID | Scenario | Expected |
|---|---|---|
| TC-IRQ-01 | Claim is `UNDER_REVIEW`, by the assigned officer | **201** `{informationRequestId:"IR-2026-000001", claimId, question, status:"OPEN", requestedByOfficerId, createdAt, answer:null, answeredAt:null}`; claim becomes `MORE_INFO_REQUIRED`. |
| TC-IRQ-02 | Claim is `SUBMITTED` | **409** `INVALID_CLAIM_TRANSITION`; nothing stored. |
| TC-IRQ-03 | Claim already `MORE_INFO_REQUIRED` (second request) | **409**. |
| TC-IRQ-04 | Claim is terminal | **409**. |
| TC-IRQ-05 | Blank question / `{}` / bad JSON | **400**. |
| TC-IRQ-06 | Unknown claim | **404** `CLAIM_NOT_FOUND`; nothing stored. |
| TC-IRQ-07 | By an officer who is not the assigned one (administrator naming another officer) | **409** `OFFICER_MISMATCH`; nothing stored. |
| TC-IRQ-08 | The Claim Service is down | **503**; nothing stored (the insert is rolled back). |
| TC-IRQ-09 | Placeholder or over-long officer ID, over-long question | **400**; the Claim Service is not called. |

### Endpoint 7 - `GET /api/v1/claims/{claimId}/information-requests`

| ID | Scenario | Expected |
|---|---|---|
| TC-ILS-01 | Claim with one request | **200**; array with that request, `status` `OPEN`, then `ANSWERED` after endpoint 8. |
| TC-ILS-02 | Claim with no requests | **200**; `[]`. |
| TC-ILS-03 | Requests are per claim, oldest first | Claim B does not list claim A's requests. |
| TC-ILS-04 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-ILS-05 | Who sees them | The owner, a claims officer in scope, a manager, an administrator; another claimant, and an officer for a claim assigned to someone else, get **404**. |

### Endpoint 8 - `POST /api/v1/claims/{claimId}/information`

Request: `{ "informationRequestId": "IR-2026-000001", "answer": "The repair estimate is 2,200 INR." }` (answer max 4000). Text only; no file upload exists. Role: the claimant who owns the claim (and an administrator).

| ID | Scenario | Expected |
|---|---|---|
| TC-ANS-01 | Answer the only OPEN request | **200** `{informationRequestId, claimId, status:"ANSWERED", answer, answeredAt}`; claim returns to `UNDER_REVIEW` automatically; the history row's `actor` is the answering person's username. |
| TC-ANS-02 | Unknown `informationRequestId` | **404** `INFORMATION_REQUEST_NOT_FOUND`. |
| TC-ANS-03 | Request belongs to a different claim | **404** `INFORMATION_REQUEST_NOT_FOUND`. |
| TC-ANS-04 | Unknown claim | **404** `CLAIM_NOT_FOUND` (before the request is looked at). |
| TC-ANS-05 | Answer the same request twice | **409** `INFORMATION_REQUEST_ALREADY_ANSWERED` (or `INVALID_CLAIM_TRANSITION` once the claim has moved on); the first answer is kept. |
| TC-ANS-06 | Claim is not `MORE_INFO_REQUIRED` (for example `UNDER_REVIEW` or decided) | **409** `INVALID_CLAIM_TRANSITION`; the request stays `OPEN`. |
| TC-ANS-07 | Blank answer / `{}` / bad JSON | **400**. |
| TC-ANS-08 | Claim stays `MORE_INFO_REQUIRED` until every OPEN request is answered | Answering one of two open requests does not resume the claim (the mock allows two; the real Claim Service allows one open request at a time, TC-IRQ-03). |
| TC-ANS-09 | The resume is refused or the Claim Service is down | **503** (or the Claim Service's own status); the answer is rolled back (`OPEN`, no answer) and can be retried. |
| TC-ANS-10 | Two answers at once | One **200**, one **409**; one resume (TC-INT-08). |
| TC-ANS-11 | Another claimant, an officer, a stranger | **404** / **403** / **404**, with nothing said about the request or the claim's status (TC-OWN-07, TC-OWN-08). |

## 9. Endpoint 9 - Record a decision (Assessment Service)

`POST /api/v1/officer/claims/{claimId}/decision` (CLAIMS_OFFICER in scope, ADMIN) - automated: assessment-service `AssessmentServiceTest`; claim-service `InternalClaimApiTest`, `ClaimDomainTest`; system `ClaimWorkflowTest`, `CurrencyHandlingTest`, `EndpointContractTest`

Request fields: `decision` (`APPROVED` or `REJECTED`), `decisionReason` (required, max 2000), `approvedAmount` (positive, at most 2 decimals, **not more than the claimed amount**; required for an approval, forbidden for a rejection), `currency` (3 upper-case letters, must equal the claim currency), `decidedByOfficerId` (optional for a claims officer, required for an administrator; section 3b). Only the officer the claim is assigned to may decide it. An approval is **not final**: the claim stays outstanding until it is settled (endpoint 18). The decision and its reason also become an entry of the assessment history (section 10d).

```json
{ "decision": "APPROVED", "decisionReason": "Repair estimate approved.", "approvedAmount": 2200.00, "currency": "INR" }
```

| ID | Scenario | Expected |
|---|---|---|
| TC-DEC-01 | Approve a claim `UNDER_REVIEW` | **200** `{claimId, status:"APPROVED", decisionReason, approvedAmount:2200.00, currency, decidedByOfficerId, decidedAt, updatedAt}`. |
| TC-DEC-02 | Reject a claim `UNDER_REVIEW` (no `approvedAmount`) | **200**, `status` `REJECTED`, no `approvedAmount`. |
| TC-DEC-03 | Approve exactly the claimed amount; `1000.5` | **200**; stored with two decimals (`1000.50`). |
| TC-DEC-04 | Second decision on a decided claim | **409** `INVALID_CLAIM_TRANSITION`; first decision kept. |
| TC-DEC-05 | Decision on a `SUBMITTED` or `MORE_INFO_REQUIRED` claim | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-DEC-06 | `approvedAmount` zero or negative | **400** (`INVALID_APPROVED_AMOUNT` from the Claim Service, or `VALIDATION_FAILED`). |
| TC-DEC-07 | `approvedAmount` above the claimed amount | **400** `INVALID_APPROVED_AMOUNT`. |
| TC-DEC-08 | Approve without `approvedAmount` | **400** `INVALID_APPROVED_AMOUNT`. |
| TC-DEC-09 | Reject with an `approvedAmount` | **400** `INVALID_APPROVED_AMOUNT`. |
| TC-DEC-10 | Currency differs from the claim | **400** `CURRENCY_MISMATCH`. |
| TC-DEC-11 | `decision` not `APPROVED`/`REJECTED` (for example `SETTLED` or `UNDER_REVIEW`) | **400** `MALFORMED_REQUEST`; the Claim Service is not called. |
| TC-DEC-12 | Blank reason / `{}` / bad JSON | **400**. |
| TC-DEC-13 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-DEC-14 | Failed attempts do not change the claim | Claim still `UNDER_REVIEW`, and **no** entry added to the assessment history, after the 400/404/409/503 cases. |
| TC-DEC-15 | Wrong method | `GET .../decision` -> **405**. |
| TC-DEC-16 | Decided by an officer other than the assigned one | **409** `OFFICER_MISMATCH` (approve and reject); the claim is unchanged. |
| TC-DEC-17 | The Claim Service is down, or answers 500 | **503** `DOWNSTREAM_UNAVAILABLE` / **502** `DOWNSTREAM_ERROR`; no history entry. |
| TC-DEC-18 | Approved is not terminal | After an approval the claim can still be assigned to another officer and is still in the outstanding liability (at the approved amount). |

## 10. Endpoint 10 - Management summary (Claim Service)

`GET /api/v1/management/summary` (MANAGER, ADMIN) - automated: claim-service `ManagementReportTest`; system `ManagementSummaryTest`, `CurrencyHandlingTest`, `DemoFlowTest`, `EndpointContractTest`

Merged from the former Reporting Service: the Claim Service computes it with `GROUP BY` queries on its own table in one read-only transaction, so it no longer needs another service to be running.

| ID | Scenario | Expected |
|---|---|---|
| TC-SUM-01 | Empty database | **200**; `outstandingClaimCount` 0, empty `outstandingLiabilityByCurrency`, **all six statuses** present with count 0. |
| TC-SUM-02 | Mixed claims and currencies | Settled and rejected claims excluded from outstanding figures; liability reported per currency and never added across currencies; `claimCount` per currency correct. |
| TC-SUM-03 | `MORE_INFO_REQUIRED` claim | Counted as outstanding and included in liability at its claimed amount. |
| TC-SUM-04 | After a decision | A rejected claim leaves the liability. An **approved** claim stays outstanding and counts at its **approved** amount (not the claimed one); once settled it leaves. |
| TC-SUM-05 | Exact decimals | 0.10 + 0.20 INR gives 0.30 INR; USD is a separate line. |
| TC-SUM-06 | Labelling | `liabilityNote` says it is a simple estimate, that undecided claims count at the claimed amount and approved ones at the approved amount, that there is no exchange-rate conversion, and "Not an actuarial reserve". |
| TC-SUM-07 | Wrong method | `POST` -> **405**. |
| TC-SUM-08 | Who may read it | MANAGER and ADMIN; CLAIMANT, CLAIMS_OFFICER, service **403**; no token **401** (TC-ACL-11). |

Sample response:

```json
{
  "generatedAt": "2026-10-18T07:10:36Z",
  "liabilityNote": "Simple estimate: for claims not yet decided, the CLAIMED amount; for APPROVED claims awaiting settlement, the APPROVED amount; settled and rejected claims are left out. Grouped by currency, with no exchange-rate conversion. It is neither the expected settlement nor a booked reserve. Not an actuarial reserve.",
  "outstandingClaimCount": 3,
  "outstandingLiabilityByCurrency": [
    { "currency": "AUD", "claimCount": 1, "estimatedLiability": 5200.00 },
    { "currency": "INR", "claimCount": 2, "estimatedLiability": 3800.00 }
  ],
  "countsByStatus": { "SUBMITTED": 1, "UNDER_REVIEW": 1, "MORE_INFO_REQUIRED": 0, "APPROVED": 1, "REJECTED": 1, "SETTLED": 4 }
}
```

## 10a. Endpoint 11 - Per-officer workload and performance (Claim Service)

`GET /api/v1/management/officers` (MANAGER, ADMIN) - automated: claim-service `ManagementReportTest`; system `OfficerMetricsTest`

No parameters. Built from existing columns only: open workload comes from `assigned_officer_id` and `status` of non-terminal claims (an `APPROVED` claim is open work until it is settled); performance comes from `decided_by_officer_id`, the decision outcome and `approved_amount`. Both aggregates are `GROUP BY` queries in the database. Officers are sorted by `officerId`. Amounts are grouped by currency and never added across currencies.

Limits: open workload is by the **current** assignee (there is no reassignment history in the report), and there is no time-based metric (for example time to decision).

| ID | Scenario | Expected |
|---|---|---|
| TC-OFF-01 | Empty database | **200**; `officers` is `[]`; `note` and `generatedAt` present. |
| TC-OFF-02 | Only unassigned claims exist | **200**; `officers` is `[]`. |
| TC-OFF-03 | Two officers with open, approved, settled and rejected claims in several currencies | Per officer: `openClaimCount`, `openClaimsByStatus` (all four open statuses, zero-filled), `openClaimedAmountByCurrency` (one line per currency, statuses combined, sorted by currency), `decidedCount` (approvals + rejections), `approvedCount` (everything the officer approved, settled or not), `settledCount`, `rejectedCount`, `approvedAmountByCurrency`. Settled and rejected claims are not counted as open; unassigned claims never appear. |
| TC-OFF-04 | Officer with decisions but no open work | Appears with `openClaimCount` 0 and empty open amounts. |
| TC-OFF-05 | One officer with claims in two currencies | Two separate currency lines, not a single total. |
| TC-OFF-06 | Wrong method | `POST` -> **405**. |
| TC-OFF-07 | Settling an approved claim | Moves it from open work to `settledCount`; `approvedCount` and the approved amount stay. |

Sample response:

```json
{
  "generatedAt": "2026-10-18T09:09:12Z",
  "note": "Open workload is by CURRENT assignee (no reassignment history); decisions are by the deciding officer. Amounts are grouped by currency and never added across currencies.",
  "officers": [
    {
      "officerId": "off-a",
      "openClaimCount": 3,
      "openClaimsByStatus": { "SUBMITTED": 1, "UNDER_REVIEW": 1, "MORE_INFO_REQUIRED": 0, "APPROVED": 1 },
      "openClaimedAmountByCurrency": [
        { "currency": "AUD", "claimCount": 1, "amount": 700.00 },
        { "currency": "INR", "claimCount": 2, "amount": 1500.00 }
      ],
      "decidedCount": 1, "approvedCount": 1, "settledCount": 0, "rejectedCount": 0,
      "approvedAmountByCurrency": [ { "currency": "AUD", "claimCount": 1, "amount": 600.00 } ]
    },
    {
      "officerId": "off-b",
      "openClaimCount": 0,
      "openClaimsByStatus": { "SUBMITTED": 0, "UNDER_REVIEW": 0, "MORE_INFO_REQUIRED": 0, "APPROVED": 0 },
      "openClaimedAmountByCurrency": [],
      "decidedCount": 1, "approvedCount": 1, "settledCount": 1, "rejectedCount": 0,
      "approvedAmountByCurrency": [ { "currency": "INR", "claimCount": 1, "amount": 250.00 } ]
    }
  ]
}
```

## 10b. Endpoint 12 - Claim status history (Claim Service)

`GET /api/v1/claims/{claimId}/history` (visible claims only) - automated: claim-service `HistoryEndpointsTest`, `InternalClaimApiTest`, `ConcurrentTransitionTest`; system `StatusHistoryTest`, `ConcurrentTransitionTest`

No parameters. Returns a JSON array, oldest first, one element per status change. `fromStatus` is null on the creation row. `actor` is the **username of the person who made the change**: the claimant for creation and for the automatic resume after their answer, the officer for review, information request, decision and settlement. (The literal `CLAIMANT` remains only when an internal resume call names nobody.) Assigning an officer does not change status and adds no row. Rows are written in the same transaction as the change, so a rejected (409/400) action leaves no row.

| ID | Scenario | Expected |
|---|---|---|
| TC-HIS-01 | New claim | **200**; one row: `fromStatus` null, `toStatus` `SUBMITTED`, `actor` the creator's username, `changedAt` set. |
| TC-HIS-02 | Create, assign, review, information request, answer, reject | **200**; 5 rows in order: SUBMITTED; SUBMITTED to UNDER_REVIEW (officer); UNDER_REVIEW to MORE_INFO_REQUIRED (officer); MORE_INFO_REQUIRED to UNDER_REVIEW (the answering person); UNDER_REVIEW to REJECTED (deciding officer). |
| TC-HIS-03 | Assign only | **200**; still one row. |
| TC-HIS-04 | Rejected transitions (duplicate review, approved amount over the claimed amount) | No extra rows. |
| TC-HIS-05 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-HIS-06 | `POST` to the history URL | **405** `METHOD_NOT_ALLOWED`. |
| TC-HIS-07 | Two competing decisions on one claim | One **200**, one **409**; exactly one decision row; stored decision is the winner's; no rejection keeps an approved amount. Also a decision racing an information request: one succeeds, one is 409; two settlements: one wins; a settlement after a racing approve/reject only succeeds if the approval won. H2 only. |
| TC-HIS-08 | Approve then settle | Rows `UNDER_REVIEW` to `APPROVED` and `APPROVED` to `SETTLED`, each with the officer as actor (6 rows for the full journey with an information request). |
| TC-HIS-09 | Claims from before the V5 migration | Their history rows are kept; the old status label is renamed in them; a claim settled in one step still reads `UNDER_REVIEW` to `SETTLED` (TC-MIG-02). |

Sample response:

```json
[
  { "claimId": "CLM-2026-000101", "fromStatus": null, "toStatus": "SUBMITTED", "changedAt": "2026-10-18T07:10:00Z", "actor": "claimant1" },
  { "claimId": "CLM-2026-000101", "fromStatus": "SUBMITTED", "toStatus": "UNDER_REVIEW", "changedAt": "2026-10-18T07:12:00Z", "actor": "officer1" }
]
```

## 10c. Endpoint 13 - Claim assignment history (Claim Service)

`GET /api/v1/claims/{claimId}/assignments` (visible claims only) - automated: claim-service `HistoryEndpointsTest`, `AssignmentRulesTest`, `InternalClaimApiTest`, `ConcurrentTransitionTest`; system `AssignmentHistoryTest`, `ConcurrentTransitionTest`

No parameters. Returns a JSON array, oldest first, one element per change of assigned officer. `previousOfficerId` is null the first time. `actor` is the **caller's username** for `assign` (a manager, an officer taking a claim, or an administrator), and the reviewing officer when `review` assigns an unassigned claim. Re-assigning the same officer, and a review by the already-assigned officer, add no row. Rejected actions add no row.

| ID | Scenario | Expected |
|---|---|---|
| TC-ASGH-01 | New claim | **200**; `[]`. |
| TC-ASGH-02 | Assign `off-1`, then `off-2` | **200**; two rows in order: null to `off-1`, then `off-1` to `off-2`; `changedAt` set. |
| TC-ASGH-03 | Assign | `actor` is the caller's username (`test-admin` in the replayed tests, the manager's name in `AssignmentRulesTest`). |
| TC-ASGH-04 | Assign the same officer twice, or review by the assigned officer | One row only. |
| TC-ASGH-05 | Review of an unassigned claim by `off-7` | One row: null to `off-7`, `actor` `off-7`. |
| TC-ASGH-06 | Assign on a terminal claim (409), blank officer (400), non-officer (400), User Service down (503) | No new rows. |
| TC-ASGH-07 | Assignment does not change status | `GET .../history` is unchanged. |
| TC-ASGH-08 | Unknown claim / `POST` | **404** `CLAIM_NOT_FOUND` / **405**. |
| TC-ASGH-09 | Two officers race to review one unassigned claim | One **200**, one **409**; the assignee is not overwritten; one assignment row and one UNDER_REVIEW history row. |

Sample response:

```json
[
  { "claimId": "CLM-2026-000101", "previousOfficerId": null, "newOfficerId": "officer1", "changedAt": "2026-10-18T07:11:00Z", "actor": "manager1" },
  { "claimId": "CLM-2026-000101", "previousOfficerId": "officer1", "newOfficerId": "officer2", "changedAt": "2026-10-18T07:15:00Z", "actor": "manager1" }
]
```

Placeholder officer IDs (`PlaceholderActorTest`): `"string"` in any case as `officerId`, `requestedByOfficerId`, `decidedByOfficerId` or `settledByOfficerId` returns **400** `VALIDATION_FAILED` with a message saying it must be a real officer ID; IDs that merely contain the word (for example `string-officer-1`) are accepted by validation.

Additional concurrency cases (`ConcurrentTransitionTest`, claim-service and system): two answers to one information request give one 200 and one 409; stale review, information request and second decision after an approval give 409 and write no history.

## 10d. Endpoint 14 - Assessment history (Assessment Service)

`GET /api/v1/officer/claims/{claimId}/assessments` (CLAIMS_OFFICER in scope, ADMIN) - automated: assessment-service `AssessmentServiceTest`, `AssessmentSecurityTest`, `AssessmentMigrationTest`; system `AssessmentNotesTest`

No parameters. Returns a JSON array, oldest first, one element per entry. An entry has a `kind`: **`NOTE`** (written by `POST .../review` when `assessmentNotes` is present and not blank), **`APPROVED`** or **`REJECTED`** (the decision, with its reason as `notes`) and **`SETTLED`** (the settlement, with its note or the default "Claim settled"). It is append-only and each entry carries the officer who did it. It is not part of the claimant-facing `GET /api/v1/claims/{claimId}` response, and this route accepts only CLAIMS_OFFICER and ADMIN tokens (a MANAGER, a CLAIMANT and a service token get 403, section 3b). An entry is committed only if the Claim Service accepted the action: a refused or failed review, decision or settlement leaves no entry.

| ID | Scenario | Expected |
|---|---|---|
| TC-ASSM-01 | Review with `"  Photos match the described damage.  "` | **200** `UNDER_REVIEW`; `GET .../assessments` returns one element with `claimId`, `officerId`, `kind` `NOTE`, trimmed `notes`, `createdAt`. |
| TC-ASSM-02 | Review with no `assessmentNotes` | **200**; `[]`; nothing stored. |
| TC-ASSM-03 | Blank (`"   "`) or `null` notes | **200**; nothing stored. |
| TC-ASSM-04 | Notes of 2001 characters | **400** `VALIDATION_FAILED`; the Claim Service is not called; nothing stored. Exactly 2000 characters is **200**. |
| TC-ASSM-05 | Review rejected with 409 (already under review, or `OFFICER_MISMATCH`) while carrying notes | Notes are **not** saved. |
| TC-ASSM-06 | Placeholder officer ID `string` with notes | **400**; nothing stored. |
| TC-ASSM-07 | A second review call after an information answer | **409** (the answer already resumed review); no extra note. |
| TC-ASSM-08 | Resume from `MORE_INFO_REQUIRED` with no OPEN request, with notes | **200**; second note appended in order. (The replayed system test reaches the state with a direct database update; `InformationRequestTest` reaches it through the API.) |
| TC-ASSM-09 | `GET /api/v1/claims/{claimId}` after a review with notes | No `assessmentNotes` field. |
| TC-ASSM-10 | Unknown claim / `POST` to the URL | **404** `CLAIM_NOT_FOUND` / **405** `METHOD_NOT_ALLOWED`. |
| TC-ASSM-11 | The decision is part of the history | After a review note and an approval: `[NOTE, APPROVED]`, the second with the decision reason and the deciding officer; a rejection gives `REJECTED`. |
| TC-ASSM-12 | The settlement is part of the history | `SETTLED` with the trimmed note, or "Claim settled" for none or a blank one. |
| TC-ASSM-13 | A refused or failed decision or settlement | No entry (409 rejected claim / officer mismatch, 400 amount or currency, 404, 503). |
| TC-ASSM-14 | Entries from before the Assessment V2 migration | Kept and read as `NOTE` (TC-MIG-03). |

Sample response:

```json
[
  { "claimId": "CLM-2026-000101", "officerId": "officer1", "kind": "NOTE", "notes": "Photos match the described damage.", "createdAt": "2026-10-18T07:12:00Z" },
  { "claimId": "CLM-2026-000101", "officerId": "officer1", "kind": "APPROVED", "notes": "Repair estimate approved.", "createdAt": "2026-10-18T07:30:00Z" },
  { "claimId": "CLM-2026-000101", "officerId": "officer1", "kind": "SETTLED", "notes": "Paid by bank transfer.", "createdAt": "2026-10-18T08:00:00Z" }
]
```

## 10e. Endpoint 15 - List claims (Claim Service)

`GET /api/v1/claims` (CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN) - automated: claim-service `ClaimListTest`; system `AuthenticationFlowTest`, `DemoUsersFlowTest`

The same filters, paging and response shape as endpoint 3 (section 6): `status`, `market`, `claimType`, `assignedOfficerId`, `assigned`, `page` (default 0), `size` (default 20, max 100), newest first, filtered, scoped and paged in SQL. **The scope depends on who asks:** a claimant gets their own claims (this is the "list my claims" call), a claims officer the unassigned claims and their own, a manager and an administrator every claim.

| ID | Scenario | Expected |
|---|---|---|
| TC-LST-01 | A claimant lists | Only their own claims, newest first; another claimant's claims never appear; a claimant with none gets `totalElements` 0. |
| TC-LST-02 | A claimant's filters and paging | They apply inside their own claims (`?market=AU`, `?claimType=PROPERTY`, `?page=1&size=1`). |
| TC-LST-03 | A claimant asks for another claimant's claims through a filter | `?assignedOfficerId=officer.b` shows nothing of other claimants; `?assigned=false` still only their own. |
| TC-LST-04 | Status filter | `?status=UNDER_REVIEW` follows the lifecycle. |
| TC-LST-05 | Item fields | `claimantId`, `claimantName`, `policyNumber`, `status`, `currency` ...; **no** `incidentDescription` or `claimantEmail`; no other claimant's name or ID anywhere in the body. |
| TC-LST-06 | A claims officer lists | The unassigned queue plus their own claims; never another officer's. |
| TC-LST-07 | The staff route (`/api/v1/officer/claims`) | Same scoped list for the same caller (TC-SRCH-16). |
| TC-LST-08 | An officer tries to widen the scope with a filter | `?assignedOfficerId=<another officer>` returns nothing; `?assigned=true` shows only their own; `?assigned=false` only the queue. |
| TC-LST-09 | A claim leaves other officers' lists when assigned | `officer.b` sees a claim in the queue; after it is assigned to `officer.a` it disappears for `officer.b` and appears for `officer.a`. |
| TC-LST-10 | Manager and administrator | Every claim, with all filters. |
| TC-LST-11 | Bad parameters | `?status=NOPE`, `?claimType=BOAT`, `?page=-1`, `?size=0`, `?size=101`, `?page=abc`, `?assigned=maybe` all **400**; `?size=100` is 200; `?assigned=false&assignedOfficerId=x` is **400** `CONFLICTING_FILTERS`. |
| TC-LST-12 | No token / service token | **401** / **403**. |

## 10f. Endpoints 16 and 17 - Correct a claim, and the edit history (Claim Service)

### Endpoint 16 - `PATCH /api/v1/claims/{claimId}`

Role: the claimant who owns the claim (and ADMIN), **only while the claim is `SUBMITTED`**. automated: claim-service `ClaimUpdateTest`, `ClaimDomainTest`

Request: any of `claimantName`, `claimantEmail`, `policyNumber`, `claimType`, `incidentDescription`, `incidentDate`, `claimedAmount`; send only what changes. The same rules apply as at creation (text trimmed, e-mail not trimmed, amount positive with two decimals, date not in the future, policy number pattern). **Not changeable here**, and ignored if sent: `claimId`, `claimantId`, `market`, `currency`, `status`, `assignedOfficerId`, `approvedAmount` and everything an officer records. The response is the full claim (as endpoint 2). There is no `DELETE`.

The claim row is locked first, so a claimant's edit and an officer starting the review cannot interleave: whichever commits second sees the other's result (the edit then gets 409 `CLAIM_NOT_EDITABLE`).

| ID | Scenario | Expected |
|---|---|---|
| TC-UPD-01 | The owner corrects several fields | **200**; the sent fields change, all others (name, e-mail, market, currency, status, claimant) stay. |
| TC-UPD-02 | One field; text is trimmed | **200**; `"  Mei Tan Lee  "` becomes `Mei Tan Lee`; an e-mail with spaces around it is **400**, as at creation. |
| TC-UPD-03 | The body tries to change the claim number, the claimant, the market, the currency, the status, the assignee or the approved amount | **200**, those fields ignored; only editable fields changed. |
| TC-UPD-04 | The edit is recorded | `GET /api/v1/claims/{claimId}/updates` shows one entry per edit: `actor` = the claimant, one line per changed field (`claimedAmount: 2400.00 -> 2500.00`, `policyNumber: A -> B`); free text (name, e-mail, description) is only reported as `changed`, never copied. An edit adds no status-history row. |
| TC-UPD-05 | The same values again | **200**, nothing recorded. |
| TC-UPD-06 | A claim never edited | `GET .../updates` is `[]`. |
| TC-UPD-07 | Nothing to change (`{}`, or only fields that are not editable such as `market`) | **400** `NO_FIELDS_TO_UPDATE`. |
| TC-UPD-08 | Invalid values (negative, zero or 3-decimal amount, future or unparseable date, bad e-mail, blank name, empty or over-long description, bad policy number, unknown claim type) | **400**; nothing changed, nothing recorded. |
| TC-UPD-09 | Malformed JSON / no body / wrong content type | **400** `MALFORMED_REQUEST` / **400** / **415**. |
| TC-UPD-10 | Another claimant | **404** `CLAIM_NOT_FOUND`, identical to an unknown claim; nothing changed. |
| TC-UPD-11 | A claims officer, a manager, a service token / no token | **403** / **401**; nothing changed. |
| TC-UPD-12 | Unknown claim | **404**. |
| TC-UPD-13 | An officer has started the review (any status except `SUBMITTED`) | **409** `CLAIM_NOT_EDITABLE`; nothing changed or recorded. (`ClaimDomainTest` covers `UNDER_REVIEW` and `APPROVED`.) |
| TC-UPD-14 | Edit races the start of review | The review always succeeds; either the edit happened first (200, new value stored) or it is refused (409, old value kept); never a 200 with the old value. |

### Endpoint 17 - `GET /api/v1/claims/{claimId}/updates`

Role: the owner, a claims officer in scope, a manager, an administrator. Returns, oldest first, `[{ "claimId", "changedAt", "actor", "changes": ["line", ...] }]`.

| ID | Scenario | Expected |
|---|---|---|
| TC-UPD-15 | Another claimant | **404**; staff in scope **200** (TC-OWN-03, TC-VIS-01). |

Sample `PATCH` request and the matching `updates` entry:

```json
{ "claimedAmount": 2500.00, "incidentDescription": "Bumper and boot lid damaged." }
```

```json
[ { "claimId": "CLM-2026-000101", "changedAt": "2026-10-18T07:11:00Z", "actor": "claimant1",
    "changes": [ "claimedAmount: 2400.00 -> 2500.00", "incidentDescription: changed" ] } ]
```

## 10g. Endpoint 18 - Settle an approved claim (Assessment Service)

`POST /api/v1/officer/claims/{claimId}/settlement` (CLAIMS_OFFICER in scope, ADMIN) - automated: assessment-service `AssessmentServiceTest`, `AssessmentSecurityTest`, `AssessmentContractTest`; claim-service `InternalClaimApiTest`, `ClaimDomainTest`, `ConcurrentTransitionTest`; system `AuthenticationFlowTest`

Request: `{ "settlementNote": "Paid by bank transfer." }`. Both fields are optional: `settledByOfficerId` (a claims officer's own name or nothing; an administrator names the officer) and `settlementNote` (max 2000, kept in the assessment history, "Claim settled" when blank). Only the officer the claim is assigned to may settle it. `APPROVED` to `SETTLED`. A claim that is not approved, and a rejected claim in particular, cannot be settled.

Response: `{ "claimId", "status": "SETTLED", "approvedAmount", "currency", "settledByOfficerId", "settledAt", "updatedAt" }`.

| ID | Scenario | Expected |
|---|---|---|
| TC-SET-01 | Settle an approved claim | **200** `SETTLED`; `settledByOfficerId` and `settledAt` set; a status-history row `APPROVED` to `SETTLED` (actor = the officer); a `SETTLED` entry in the assessment history; `GET /api/v1/claims/{claimId}` shows `settledAt`. |
| TC-SET-02 | Settle a claim that is `SUBMITTED`, `UNDER_REVIEW` or `MORE_INFO_REQUIRED` | **409** `INVALID_CLAIM_TRANSITION` ("A claim can only be settled after it is approved, but claim is ..."); nothing settled. |
| TC-SET-03 | Settle a **rejected** claim | **409** `INVALID_CLAIM_TRANSITION`, message "A rejected claim cannot be settled"; stays `REJECTED`. |
| TC-SET-04 | Settle twice | The second is **409**; the first `settledAt` is kept; no second history row. |
| TC-SET-05 | By an officer other than the assigned one | **409** `OFFICER_MISMATCH` (an approved claim can first be reassigned to another officer, TC-ASG-19). |
| TC-SET-06 | Invalid body / placeholder officer / over-long note / unknown claim | **400** / **400** / **400** / **404**; the Claim Service is not called for the 400s. |
| TC-SET-07 | A refused settlement keeps nothing | No entry in the assessment history. |
| TC-SET-08 | Two settlements at once | One **200**, one **409**; one `SETTLED` history row. |
| TC-SET-09 | An approval and a rejection race, then a settlement | The settlement works exactly when the approval won. |
| TC-SET-10 | A claimant, a manager, a service token / no token | **403** / **403** / **403** / **401**; nothing changes. |
| TC-SET-11 | Method and content type | `GET` **405**; `text/plain` **415**; no body **400**. |
| TC-SET-12 | The claim's own domain rules | Every move of the six-status table is allowed and every other is a conflict (`ClaimDomainTest.exactlyTheDocumentedMovesAreAllowedFromEachStatus`, written by hand from section 3, not derived from the code); `REJECTED` and `SETTLED` are the only terminal statuses. |

## 10h. Database migrations (Phase 1)

Each service owns its own Flyway folder; the Spring tests only ever start from an empty database, so the three data-changing migrations also have a test that builds the **previous** schema, inserts old rows and runs the new migration over them. All three ran on **H2 in PostgreSQL mode only**.

| ID | Migration | What it proves | Automated |
|---|---|---|---|
| TC-MIG-01 | User V2 | See above: `OFFICER` becomes `CLAIMS_OFFICER`, everyone is active | user-service `UserMigrationTest` |
| TC-MIG-02 | Claim V5 | Status `AWAITING_INFORMATION` becomes `MORE_INFO_REQUIRED` in `claims` **and** in the history rows (4 history rows kept, none lost); `description` becomes `incident_description`; `settled_amount` becomes `approved_amount` (a claim settled in one step keeps `900.00` and its `UNDER_REVIEW` to `SETTLED` history); `created_by` becomes `claimant_id` (no owner stays none); a settled claim gets `settled_by_officer_id` and `settled_at` from its decision; nobody else has a settlement; `policy_number` is null for old claims; the status constraint accepts the six statuses and refuses the old label; the edit-history table exists with its foreign key | claim-service `ClaimMigrationTest` |
| TC-MIG-03 | Assessment V2 | An old note is a `NOTE`; a `kind` outside the four is refused; the information-request tables, sequence and constraints exist (a fresh table: nothing is copied from the old Information Service's database) | assessment-service `AssessmentMigrationTest` |
| TC-MIG-04 | The services' schemas still validate | Hibernate `ddl-auto=validate` passes on every migrated schema in every Spring test (a column/entity mismatch fails the context) | all `@SpringBootTest` classes |

**Not tested: any of the new migrations on PostgreSQL.** They use only SQL that PostgreSQL also accepts (`RENAME COLUMN`, `DROP`/`ADD CONSTRAINT`, `ADD COLUMN ... DEFAULT ... NOT NULL`, `GENERATED BY DEFAULT AS IDENTITY`), but that is an argument, not a result.

## 11. End-to-end Swagger demo flow

Start the three services in order (`.\microservices\scripts\start-all.ps1`, or the commands in `README.md`; set `JWT_SECRET` first or let the script generate one for its run), open the Swagger UI of the service that owns each step (table in the introduction), expand the operation, choose **Try it out**, paste the body, and **Execute**. Steps 1-4, 10 and 12-14 use the Claim Service, steps 5-9 and 11 the Assessment Service. Copy IDs from responses; do not reuse the sample values. `microservices\scripts\smoke-test.ps1` runs this flow automatically, as real users.

**Step 0, who does what.** Log in on the User Service's Swagger UI (`POST /api/v1/auth/login`) as the administrator (`admin1` while the demo users are on), create a CLAIMS_OFFICER and a MANAGER with `POST /api/v1/users`, and register a claimant with `POST /api/v1/auth/register`; log each of them in. In each service's Swagger UI choose **Authorize** and paste the `accessToken` of the person who does the step: the **claimant** for steps 1, 2, 7, 8 and 14, the **manager** for steps 4 and 10, the **officer** for steps 3, 5, 6, 9, 11 and 13. Step 12 may be done as anyone with a token. (The Authorize button was not exercised by hand in this work; the same flow is run by `smoke-test.ps1`.)

| Step | Operation | Input | Expected |
|---|---|---|---|
| 1 | `POST /api/v1/claims` | The base request from section 4 | 201; copy `claimId` (call it `CLAIM`); `claimantId` is the logged-in claimant. |
| 2 | `GET /api/v1/claims/{claimId}` and `GET /api/v1/claims` | `CLAIM` | 200, status `SUBMITTED`; the claimant's own list shows it. Optionally `PATCH` the amount (endpoint 16). |
| 3 | `GET /api/v1/officer/claims` | `status=SUBMITTED&market=SG` | 200; `CLAIM` is listed (it is in the unassigned queue). |
| 4 | `POST .../{claimId}/assign` | as the **manager**: `CLAIM`, `{"officerId":"<officer's username>"}` | 200, still `SUBMITTED`, assigned. Naming the claimant's username gives 400 `INVALID_OFFICER`. |
| 5 | `POST .../{claimId}/review` | as the officer: `CLAIM`, `{"assessmentNotes":"..."}` | 200, `UNDER_REVIEW`. |
| 6 | `POST .../{claimId}/information-requests` | `CLAIM`, `{"question":"Please provide the repair estimate."}` | 201, `OPEN`; copy `informationRequestId` (`IR`). |
| 7 | `GET /api/v1/claims/{claimId}/information-requests` | `CLAIM` | 200; one `OPEN` request. Step 2 now shows `MORE_INFO_REQUIRED`. |
| 8 | `POST /api/v1/claims/{claimId}/information` | `CLAIM`, `{"informationRequestId":"<IR>","answer":"The repair estimate is 2,200 INR."}` | 200, `ANSWERED`; claim is back to `UNDER_REVIEW` automatically (no extra review call). |
| 9 | `POST .../{claimId}/decision` | `CLAIM`, the approval body from section 9 | 200, `APPROVED`, `approvedAmount` 2200.00. |
| 10 | `GET /api/v1/management/summary` | as the manager | 200; `CLAIM` is still outstanding, now at 2200.00 (the approved amount). |
| 11 | `POST .../{claimId}/settlement` | `CLAIM`, `{"settlementNote":"Paid."}` | 200, `SETTLED`; a second call is **409**; a decision of `REJECTED` now is **409** `INVALID_CLAIM_TRANSITION`. Step 10 again: `CLAIM` has left the outstanding liability. |
| 12 | `GET /api/v1/claims/CLM-2026-999999` | none | **404** `CLAIM_NOT_FOUND`. |
| 13 | `GET /api/v1/officer/claims/{claimId}/assessments` | `CLAIM` | 200; entries `NOTE`, `APPROVED`, `SETTLED`. |
| 14 | `GET /api/v1/claims/{claimId}/history` and `.../assignments` | `CLAIM` | 200; status rows SUBMITTED, UNDER_REVIEW, MORE_INFO_REQUIRED, UNDER_REVIEW, APPROVED, SETTLED; one assignment row for the officer, `actor` = the manager. |

The same flow as a PowerShell script is `microservices\scripts\smoke-test.ps1` (section 12 gives its real result). A request-by-request version of the whole journey with real responses is in sections 15 and 18.

## 12. Coverage and verified results

### Coverage by endpoint

Test classes are named once per module where they run: `claim-service` (**C**), `assessment-service` (**A**), `system-tests` (**S**), `user-service` (**U**), `claims-common` (**K**).

| # | Endpoint | Automated test classes | Missing |
|---|---|---|---|
| 1 | `POST /api/v1/claims` | C: ClaimCreationTest, EndpointContractTest, ClaimSecurityTest; S: ClaimCreationTest, EndpointContractTest | none |
| 2 | `GET /api/v1/claims/{claimId}` | C: ClaimCreationTest, ClaimSecurityTest, EndpointContractTest; S: ClaimWorkflowTest | none |
| 3 | `GET /api/v1/officer/claims` | C: OfficerSearchTest, UnassignedFilterTest, ClaimListTest; S: OfficerSearchTest, UnassignedFilterTest | TC-SRCH-09 (no match) has no dedicated test |
| 4 | `POST .../assign` | C: AssignmentRulesTest, EndpointContractTest, HistoryEndpointsTest; S: ClaimWorkflowTest, AssignmentHistoryTest, ServiceFailureTest | none |
| 5 | `POST .../review` | A: AssessmentServiceTest, AssessmentSecurityTest, AssessmentContractTest; C: ClaimDomainTest, InternalClaimApiTest; S: ClaimWorkflowTest, AssessmentNotesTest | none |
| 6 | `POST .../information-requests` | A: InformationRequestTest, AssessmentSecurityTest, AssessmentContractTest; S: ClaimWorkflowTest | none |
| 7 | `GET .../information-requests` | A: InformationRequestTest, AssessmentSecurityTest; S: ClaimWorkflowTest, DemoUsersFlowTest | none |
| 8 | `POST .../information` | A: InformationRequestTest, AssessmentSecurityTest, AssessmentContractTest; S: ClaimWorkflowTest, AuthenticationFlowTest | none |
| 9 | `POST .../decision` | A: AssessmentServiceTest, AssessmentContractTest; C: InternalClaimApiTest, ClaimDomainTest; S: ClaimWorkflowTest, CurrencyHandlingTest, EndpointContractTest | none |
| 10 | `GET /api/v1/management/summary` | C: ManagementReportTest; S: ManagementSummaryTest, CurrencyHandlingTest, DemoFlowTest, EndpointContractTest | none |
| 11 | `GET /api/v1/management/officers` | C: ManagementReportTest; S: OfficerMetricsTest | none (no time-based metric exists, so none is tested) |
| 12 | `GET .../history` | C: HistoryEndpointsTest, InternalClaimApiTest, ConcurrentTransitionTest; S: StatusHistoryTest | none |
| 13 | `GET .../assignments` | C: HistoryEndpointsTest, AssignmentRulesTest; S: AssignmentHistoryTest | none |
| 14 | `GET .../assessments` | A: AssessmentServiceTest, AssessmentSecurityTest; S: AssessmentNotesTest, DemoFlowTest | none |
| 15 | `GET /api/v1/claims` | C: ClaimListTest; S: AuthenticationFlowTest, DemoUsersFlowTest | none |
| 16 | `PATCH /api/v1/claims/{claimId}` | C: ClaimUpdateTest, ClaimDomainTest; S: AuthenticationFlowTest | none |
| 17 | `GET .../updates` | C: ClaimUpdateTest, ClaimSecurityTest; S: AuthenticationFlowTest | none |
| 18 | `POST .../settlement` | A: AssessmentServiceTest, AssessmentSecurityTest, AssessmentContractTest; C: InternalClaimApiTest, ClaimDomainTest, ConcurrentTransitionTest; S: AuthenticationFlowTest, DemoFlowTest | none |

Authentication and roles (section 3b) cover every one of the 18 endpoints through the per-service `*SecurityTest` classes and `AuthenticationFlowTest`, the User Service routes U1 to U8, and the internal routes:

| # | Endpoint | Automated test classes | Missing |
|---|---|---|---|
| U1 | `POST /api/v1/auth/register` | AuthTest, UserSecurityTest | none |
| U2 | `POST /api/v1/auth/login` | AuthTest, DemoUsersTest, UserActiveFlagTest, UserSecurityTest; S: AuthenticationFlowTest, DemoUsersFlowTest | none |
| U3 | `GET /api/v1/users/me` | UserAdminTest, UserActiveFlagTest, UserSecurityTest | none |
| U4 | `POST /api/v1/users` | UserAdminTest, UserSecurityTest | none |
| U5 | `GET /api/v1/users` | UserAdminTest, UserSecurityTest | none |
| U6 | `GET /api/v1/users/{username}` | UserAdminTest, UserActiveFlagTest, UserSecurityTest | none |
| U7 | `PUT /api/v1/users/{username}/role` | UserAdminTest, UserSecurityTest | none |
| U8 | `PUT /api/v1/users/{username}/active` | UserActiveFlagTest, UserAdminTest, UserSecurityTest; S: AuthenticationFlowTest | none |
| - | `GET /internal/v1/users/{username}` | UserActiveFlagTest; C: UserClientTest; S: AuthenticationFlowTest | none |
| - | `GET /internal/v1/claims/{claimId}/status`, `POST .../review`, `.../request-information`, `.../resume-after-information`, `.../decision`, `.../settlement` | C: InternalClaimApiTest, ClaimSecurityTest, EndpointContractTest | none |
| - | `GET /actuator/health` | S: ServiceBoundariesTest; K: SecurityChainTest | the `DOWN` answer |

What each class proves, in one line:

- **claims-common (K):** `JwtUtilTest` (token creation and refusal: tampered, expired, foreign secret/issuer, no expiry, HS384/512, unknown role, short or missing secret, SERVICE only from `generateServiceToken`); `SecurityChainTest` (401/403/200 by role on probe routes, the error body, Bearer only, open health and Swagger, internal routes SERVICE-only, service tokens on outgoing calls); `ClaimVisibilityTest` (the visibility rule and the officer-identity rule, TC-OWN-09, TC-IDN-*); `DownstreamClientTest` (503/502/business-error mapping, never a retry); `GlobalExceptionHandlerTest`.
- **user-service (U):** `AuthTest`, `UserAdminTest`, `UserActiveFlagTest`, `UserSecurityTest`, `AdminBootstrapTest`, `DemoUsersTest`, `DemoUsersUnitTest`, `UserMigrationTest` as in section 3b.
- **claim-service (C):** `ClaimDomainTest` (the transition table written by hand, terminal states, assigned-officer rules, claimant edits); `ClaimCreationTest`; `ClaimUpdateTest`; `ClaimListTest`; `AssignmentRulesTest`; `ClaimSecurityTest` (matrix, ownership, officer scope, internal routes, stats routes gone); `InternalClaimApiTest`; `ManagementReportTest`; `HistoryEndpointsTest`; `ConcurrentTransitionTest`; `EndpointContractTest`; `OfficerSearchTest`; `UnassignedFilterTest`; `MarketValidationTest`; `DefaultMarketTest`; `UserClientTest`; `ClaimMigrationTest`.
- **assessment-service (A):** `AssessmentServiceTest` (review, history, decision, settlement against a mocked Claim Service); `InformationRequestTest` (ask, list, answer, rollback, race, local open-request check); `AssessmentSecurityTest` (matrix, scope, officer identity, claimant ownership); `AssessmentContractTest` (the five body-taking POST routes: malformed, missing, empty, wrong content type; 405s; unknown route); `AssessmentMigrationTest`.
- **system-tests (S):** `AuthenticationFlowTest`, `DemoUsersFlowTest`, `DemoFlowTest` (whole journeys over real HTTP between the three services); `ServiceBoundariesTest` (tables per database, no shared database, which service serves which route, retired routes gone, health, OpenAPI documents, 18 + 8 operations, titles, Swagger UI); `ServiceFailureTest` (dependency down or misconfigured, both directions); `JwtSecretConfigurationTest` and `DatabasePerServiceConfigurationTest` (the packaged configuration, section 3b); and the replayed monolith classes **in their adapted form** (section 1): `AssessmentNotesTest`, `AssignmentHistoryTest`, `ClaimCreationTest`, `ClaimWorkflowTest`, `ConcurrentTransitionTest`, `CurrencyHandlingTest`, `EndpointContractTest`, `ManagementSummaryTest`, `OfficerMetricsTest`, `OfficerSearchTest`, `PlaceholderActorTest`, `StatusHistoryTest`, `UnassignedFilterTest`.

Not covered by any automated test: a forced `500 INTERNAL_ERROR`; **PostgreSQL** (the concurrency tests run on H2 only, and none of the new migrations has run on PostgreSQL); the `DOWN` health answer; **Kafka** (not implemented); the Swagger UI **Authorize** button; a token's behaviour under real clock drift between machines; brute-force or rate-limit behaviour (none exists).

### Verified results

Filled in only from a real run. The totals come from `.\mvnw.cmd -B -f microservices/pom.xml clean test` and the Surefire reports of each module. The owner's own User and Claim services were running from the repository's `target` folders at the time, so every build and test run below was done on a **copy of the sources in a scratch folder** (a path mapped to a short drive letter, because the Windows path was too long for Maven there) and not in the repository's own `target` folders; the repository's files were not touched by the runs.

- **Update 2026-10-27 (supersedes the two bullets marked superseded below, for commit `ee90578`):** `clean test` on the microservices, in a scratch copy (the owner's services were running): **BUILD FAILURE, 580 tests run, 578 passed, 2 failed, 0 errors, 0 skipped.** claims-common 54, user-service 62, claim-service 201 and assessment-service 90 all passed; system-tests ran 173 with 2 failures, both in `JwtSecretConfigurationTest` (`everyServiceReadsTheSameEnvironmentVariableAndHasNoDefaultSecret` and `noPackagedConfigurationFileOfAnyServiceContainsASecretOrACredentialLiteral`). **Cause:** the committed `application.properties` of the three services write `claims.security.jwt.secret` with a literal fallback after `JWT_SECRET:` (the value is deliberately not repeated here), so TC-CFG-01 and TC-CFG-03 fail, and a service started without `JWT_SECRET` starts instead of refusing (observed: a claim-service jar started with no `JWT_SECRET` answered health UP). The monolith: `mvnw test`, 142 tests, 0 failures. Real jars on spare ports: `smoke-test.ps1` **81 of 81 PASS** (twice). The live runs of sections 14 to 20 (two flows, 130 role-matrix cells, 210 scenarios, 16 downstream-failure cases, the Postman definitions) all matched, except that case OUT-1.3 returned 502 where 503 was expected (the documented design, section 17.2). Not run: PostgreSQL. While this was being done, the three `application.properties` were edited and then committed by someone other than the author of this document (commit `b33e4a3`, 2026-10-27 09:43; not pushed, `main` is 2 commits ahead of `origin/main`). That commit comments out the H2 defaults and hard-codes a PostgreSQL datasource with a **literal database password in each of the three files**, which contradicts `CLAUDE.md` sections 6 and 9: the password must be treated as exposed, and the commit should not be pushed as it is. It was **not tested** (everything in this update ran on `ee90578`); `docs/architecture.md` section 11.3 describes it without the values.
- **After Phase 1 (earlier run, 2026-10-26; superseded for commit `ee90578` by the update above): `clean test` on the microservices: BUILD SUCCESS, 580 tests, 0 failures, 0 errors, 0 skipped**, H2 in PostgreSQL mode only. By module and class:
  - claims-common 54: DownstreamClientTest 11, GlobalExceptionHandlerTest 4, ClaimVisibilityTest 7, JwtUtilTest 14, SecurityChainTest 18.
  - user-service 62: AdminBootstrapTest 5, AuthTest 16, DemoUsersTest 6, DemoUsersUnitTest 5, UserActiveFlagTest 11, UserAdminTest 15, UserMigrationTest 1, UserSecurityTest 3.
  - claim-service 201: AssignmentRulesTest 14, ClaimCreationTest 11, ClaimDomainTest 14, ClaimListTest 12, ClaimMigrationTest 1, ClaimSecurityTest 14, ClaimUpdateTest 16, ConcurrentTransitionTest 7, DefaultMarketTest 1, EndpointContractTest 35, HistoryEndpointsTest 6, InternalClaimApiTest 37, ManagementReportTest 11, MarketValidationTest 3, OfficerSearchTest 6, UnassignedFilterTest 8, UserClientTest 5.
  - assessment-service 90: ActingAsTest 4, AssessmentContractTest 22, AssessmentMigrationTest 1, AssessmentSecurityTest 23, AssessmentServiceTest 23, InformationRequestTest 17.
  - system-tests 173: AssessmentNotesTest 10, AssignmentHistoryTest 10, AuthenticationFlowTest 9, ClaimCreationTest 9, ClaimWorkflowTest 18, ConcurrentTransitionTest 5, CurrencyHandlingTest 5, DatabasePerServiceConfigurationTest 4, DemoFlowTest 1, DemoUsersFlowTest 5, EndpointContractTest 43, JwtSecretConfigurationTest 3, ManagementSummaryTest 2, OfficerMetricsTest 6, OfficerSearchTest 6, PlaceholderActorTest 4, ServiceBoundariesTest 13, ServiceFailureTest 6, StatusHistoryTest 6, UnassignedFilterTest 8. The 13 replayed classes still total 132 tests (counts unchanged) but their content was adapted (section 1); `DemoFlowTest` is adapted too (approve, then settle).
- **How this compares with the five-service system before Phase 1** (same method, earlier the same day): 464 tests, 0 failures. The counts per module are not comparable one for one: the Information and Reporting modules (39 + 14 tests) were merged away, their cases moved into `assessment-service` (`InformationRequestTest`, 17) and `claim-service` (`ManagementReportTest`, 11), and many cases were added for the new rules. The earlier baselines (325 tests before authentication, 142 monolith) remain in the history of `.claude/ai-journal.md`.
- **Original monolith:** `.\mvnw.cmd test` at the repository root, on the same kind of copy: **BUILD SUCCESS, 142 tests, 0 failures, 0 errors, 0 skipped** (16 test classes). The monolith's code and tests were not changed (git shows its tree as committed); the only change near it was removing the stray, untracked `src/main/java/UserServiceApplication.java` that the IDE had put there (approved), which the monolith had been compiling into its own classes without harm.
- **Real jars, re-run on the final code (after the independent review's fixes):** jars rebuilt with `-DskipTests clean package`, started by `start-all.ps1` on spare ports, `smoke-test.ps1` **81 of 81 checks PASS, exit code 0**; by hand on the same jars: an administrator reviewing an unassigned claim as `typo.name` or as a claimant got 400 `INVALID_OFFICER` and the claim stayed unassigned (as `officer1`: 200), a `PATCH` with a line break gave 200, and the Assessment Service log held one line per administrator request (TC-INT-07 to TC-INT-09). The earlier run below was made before those fixes.
- **Real jars, 2026-10-26 (first run, before the review's fixes):** the three runnable jars were built (`-DskipTests clean package`, BUILD SUCCESS) and started **by `start-all.ps1 -SkipBuild`** on spare ports 18181 (Claim), 18182 (Assessment) and 18185 (User), with H2 databases and no `JWT_SECRET` set (the script generated one for the run). After the script finished, `JWT_SECRET`, `CLAIM_SERVICE_URL` and `USER_SERVICE_URL` were unchanged in the PowerShell session. Observed: health `UP` on all three; `GET /actuator/env` 401 without a token; `/v3/api-docs` 200 with the titles *User Service API*, *Claim Service API*, *Assessment Service API* and Swagger UI 200 on each; the four demo users log in with the documented password and get `ADMIN`, `CLAIMS_OFFICER`, `MANAGER`, `CLAIMANT` (`expiresIn` 3600, `tokenType` Bearer); Flyway applied User V1 and V2, Claim V1 to V5 and Assessment V1 and V2 on start-up; searching the three logs for the demo password and for token text found nothing. **`smoke-test.ps1`: 81 checks** (78 check lines plus the health check run for three services). The first run passed 80 of 81: one comparison in the script was a text comparison of `2200.00` with `2200` (the service's answer was right; the script now compares numerically). A second run on the same running instances, after that fix, **passed all 81** (and shows the script can be re-run: it counts before and after instead of assuming an empty system). The processes were stopped afterwards with `stop-all.ps1`; the owner's services on 8185 and 8787 were not touched and not called.
- **Startup without a secret (observed 2026-10-26; superseded: not reproduced on 2026-10-27, see the update above):** the packaged Claim Service jar started with no `JWT_SECRET` in the environment **refused to start**: the message says that `claims.security.jwt.secret` (environment variable `JWT_SECRET`) must be at least 32 characters and the same for every service, the process exited, and port 18199 was never opened.
- **Not tested:** PostgreSQL for the new migrations (User V2, Claim V5, Assessment V2 ran on H2 only, including over pre-populated old data, and the concurrency tests run on H2 only); an **expired token against the running jars** (expired, foreign-secret and old-role tokens are refused in the unit, MockMvc and system tests only); the `DOWN` health answer; the Swagger UI **Authorize** button; the default Claim Service port 8181 and the owner's own local configuration (the runs used spare ports and in-memory databases); Kafka (**not implemented**; planned as a separate later phase). Authentication is implemented and tested as above; its limits are in `README.md`.

## 13. PostgreSQL setup

> **Status, stated plainly.** Nothing in this section was run against PostgreSQL on 2026-10-27. The statements and commands are derived from `application-postgres.properties`, the `application.properties` of commit `ee90578` (driven by environment variables; the current one differs, see the conflict note in 13.3) and the Flyway migrations, and the log lines quoted in 13.5 were observed on the default **H2** databases. Earlier sessions ran Claim V1 to V4 and User V1 on a local PostgreSQL 17.6 (journal Entries 14 and 15). Claim V5, User V2 and Assessment V2, claim creation (`nextval`), the transitions, `SELECT ... FOR UPDATE` locking, timestamp round trips, the concurrency tests and the whole Assessment database have **never** been run on PostgreSQL. Do not describe PostgreSQL as verified until the checks in 13.5 have been run and their results written down.

### 13.1 One database per service

Each service owns its own database; no service reads another's tables. **Never put two services in one database**: every service has a Flyway version 1 with different content and a database holds one `flyway_schema_history`, so the second service to start fails with "Migration checksum mismatch for migration version 1". Do not run Flyway `repair` or `clean` to fix that (repair only moves the failure to the other service, `clean` deletes data); give the service its own database.

| Service | Database | Flyway versions applied on first start | Tables and sequences it creates |
|---|---|---|---|
| `user-service` (8185) | `user_db` | V1, V2 | `users` |
| `claim-service` (8181) | `claim_db` | V1 to V5 | `claims`, `claim_status_history`, `claim_assignment_history`, `claim_update_history`, sequence `claim_number_seq` |
| `assessment-service` (8182) | `assessment_db` | V1, V2 | `claim_assessments`, `information_requests`, sequence `information_request_number_seq` |

Each database also gets Flyway's own `flyway_schema_history` table.

### 13.2 CREATE DATABASE scripts

Run as a PostgreSQL superuser, for example `psql -h localhost -U postgres`. **Ask before running these on a server that other people use**, and never `DROP`, `repair` or `clean` a database you were not asked to change.

```sql
-- the three databases, one per service
CREATE DATABASE user_db;
CREATE DATABASE claim_db;
CREATE DATABASE assessment_db;
```

Optional: an application role that owns the three databases, so the services do not connect as a superuser. On PostgreSQL 15 and later an ordinary role cannot create tables in the `public` schema of a database it does not own, so the role is made the **owner**. Choose the password yourself and keep it out of every file in the repository (use the environment variables in 13.3).

```sql
CREATE ROLE claims_app LOGIN PASSWORD '<choose-a-password>';
CREATE DATABASE user_db       OWNER claims_app;
CREATE DATABASE claim_db      OWNER claims_app;
CREATE DATABASE assessment_db OWNER claims_app;
```

Check that they exist:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname IN ('user_db', 'claim_db', 'assessment_db')
ORDER BY datname;      -- expect three rows
```

### 13.3 Configuration and environment variables

Every setting comes from the environment; **no password or secret is ever written in a properties file** (`JwtSecretConfigurationTest` is written to fail the build if one is).

| Service | `*_DB_URL` | `*_DB_USERNAME` | `*_DB_PASSWORD` |
|---|---|---|---|
| user-service | `USER_DB_URL=jdbc:postgresql://localhost:5432/user_db` | `USER_DB_USERNAME` | `USER_DB_PASSWORD` |
| claim-service | `CLAIM_DB_URL=jdbc:postgresql://localhost:5432/claim_db` | `CLAIM_DB_USERNAME` | `CLAIM_DB_PASSWORD` |
| assessment-service | `ASSESSMENT_DB_URL=jdbc:postgresql://localhost:5432/assessment_db` | `ASSESSMENT_DB_USERNAME` | `ASSESSMENT_DB_PASSWORD` |

Also needed by all three: the **same** `JWT_SECRET` (at least 32 characters). Optional: `USER_SERVICE_URL` (Claim Service, default `http://localhost:8185`), `CLAIM_SERVICE_URL` (Assessment Service, default `http://localhost:8181`), `DEMO_USERS=false` to switch the demo users off.

There are two equivalent ways to select PostgreSQL:

* **Environment only** (simplest): set the three variables of a service. The packaged `application.properties` reads them (`${USER_DB_URL:<H2 default>}` and so on) and the PostgreSQL driver is on the runtime classpath, so the URL is enough.
* **The `postgres` profile**: `SPRING_PROFILES_ACTIVE=postgres` (or `--spring.profiles.active=postgres`). `application-postgres.properties` then defaults the URL to the database names above and **requires** `*_DB_USERNAME` and `*_DB_PASSWORD` (no default: the service stops with an unresolved placeholder if one is missing).

PowerShell, one session for all three services (each service reads only its own `*_DB_*` variables, so setting all nine is safe). The secret is generated and the password is typed, so neither is written to a file or to the command history:

```powershell
# one signing secret for all three services (at least 32 characters); never printed or saved
$bytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:JWT_SECRET = -join ($bytes | ForEach-Object { $_.ToString('x2') })

# the database password is typed, not written down
$secure = Read-Host 'PostgreSQL password for claims_app' -AsSecureString
$plain  = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

$env:USER_DB_URL       = 'jdbc:postgresql://localhost:5432/user_db'
$env:USER_DB_USERNAME  = 'claims_app'
$env:USER_DB_PASSWORD  = $plain
$env:CLAIM_DB_URL      = 'jdbc:postgresql://localhost:5432/claim_db'
$env:CLAIM_DB_USERNAME = 'claims_app'
$env:CLAIM_DB_PASSWORD = $plain
$env:ASSESSMENT_DB_URL      = 'jdbc:postgresql://localhost:5432/assessment_db'
$env:ASSESSMENT_DB_USERNAME = 'claims_app'
$env:ASSESSMENT_DB_PASSWORD = $plain
```

The same in `cmd`: `set USER_DB_URL=jdbc:postgresql://localhost:5432/user_db`, `set USER_DB_USERNAME=claims_app`, `set USER_DB_PASSWORD=<password>`, and likewise for `CLAIM_DB_*` and `ASSESSMENT_DB_*`.

> **Conflict with the current repository.** Everything above describes the configuration as committed in `ee90578`, which reads these variables. Commit `b33e4a3` (the current `main`, not pushed) replaced it: `user-service` and `claim-service` now **hard-code** the datasource (databases `user_db` and `claimdb`, a user name and a **literal password**), and `assessment-service` hard-codes the same kind of values as the fallbacks of `${ASSESSMENT_DB_URL:...}`, `${ASSESSMENT_DB_USERNAME:...}` and `${ASSESSMENT_DB_PASSWORD:...}` (database `assessmentdb`). In the first two files **the environment variables of this section have no effect**, and the database names differ from the `claim_db` and `assessment_db` of 13.1 and 13.2. To follow this section as written, the datasource lines of those files must read the variables again (the H2 lines that did are still in the files, commented out). The password in the committed files must be treated as exposed (it is in the repository history and in a OneDrive-synced folder), and should be changed before anything is pushed. These files were not changed by this document. See `docs/architecture.md` section 11.3.

### 13.4 Start the services on PostgreSQL

Start in dependency order: user, claim, assessment. `start-all.ps1` starts the three processes from the current session, so they inherit the variables above:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'postgres'        # optional, see 13.3
.\microservices\scripts\start-all.ps1 -SkipBuild
```

or by hand, one terminal each (the same environment in every terminal):

```powershell
java -jar microservices\user-service\target\user-service-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=postgres
java -jar microservices\claim-service\target\claim-service-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=postgres
java -jar microservices\assessment-service\target\assessment-service-0.0.1-SNAPSHOT-exec.jar --spring.profiles.active=postgres
```

**Before the first start against a database that already holds data, take a backup.** Claim V5 renames a status in `claims` and in `claim_status_history`, renames three columns and adds others; User V2 renames a role; Assessment V2 adds a column. These migrations have only ever run on H2, including over old rows (`ClaimMigrationTest`, `UserMigrationTest`, `AssessmentMigrationTest`).

### 13.5 Startup verification steps

Run these in order and write the real results next to them; none has been run on PostgreSQL yet.

| # | Check | Command | Expected | Result on PostgreSQL |
|---|---|---|---|---|
| 1 | The role can reach each database | `psql -h localhost -U claims_app -d user_db -c "select 1"` (and `claim_db`, `assessment_db`) | one row, `1` | not run |
| 2 | Flyway ran on the right database | the service log (below) | `Database: jdbc:postgresql://localhost:5432/<db> (PostgreSQL ...)`, one `Migrating schema "public" to version ...` line per version, then `Successfully applied N migrations` | not run |
| 3 | Health | `Invoke-RestMethod http://localhost:8185/actuator/health` (and 8181, 8182) | `status` is `UP` (`DOWN` with HTTP 503 when the database cannot be reached) | not run |
| 4 | The migration history is complete and clean | `select installed_rank, version, description, success from flyway_schema_history order by installed_rank;` in each database | the rows listed below, all `success = t` | not run |
| 5 | The tables exist, in the right database | `\dt` in each database | the tables of 13.1 and nothing from another service | not run |
| 6 | Demo users were created (User Service, `DEMO_USERS` on) | `select username, role, active from users order by username;` | `admin1`, `claimant1`, `manager1`, `officer1`, all active, and a `DEMO USERS ARE ENABLED` WARN line in the log | not run |
| 7 | Login works and a token is accepted by another service | section 14.3, then `GET /api/v1/claims` on the Claim Service | 200 | not run |
| 8 | The claim sequence works | create one claim (section 15, step F1-01), then `select claim_id, claimant_id, status from claims;` and `select last_value from claim_number_seq;` | `CLM-<year>-000101`, `claimant1`, `SUBMITTED`; `101` on a fresh database | not run |
| 9 | Data survives a restart (unlike H2) | stop and start `claim-service`, `GET /api/v1/claims/<claimId>` as `claimant1` | 200, and the log shows no `Migrating` lines the second time | not run |
| 10 | Row locking and the concurrency behaviour | there is no PostgreSQL check; the concurrency tests run on H2 only | n/a | **not verified, do not rely on it** |

What the log shows on the default H2 databases (observed 2026-10-27; on PostgreSQL expect the same lines with the PostgreSQL URL on the first one):

```text
user-service        Database: jdbc:h2:mem:userdb (H2 2.2)
                    Migrating schema "public" to version "1 - create users"
                    Migrating schema "public" to version "2 - claims officer role and active flag"
                    Successfully applied 2 migrations to schema "public", now at version v2
claim-service       Database: jdbc:h2:mem:claimdb (H2 2.2)
                    Migrating schema "public" to version "1 - create claims schema"
                    Migrating schema "public" to version "2 - create claim status history"
                    Migrating schema "public" to version "3 - create claim assignment history"
                    Migrating schema "public" to version "4 - add claims created by"
                    Migrating schema "public" to version "5 - claim model phase1"
                    Successfully applied 5 migrations to schema "public", now at version v5
assessment-service  Database: jdbc:h2:mem:assessmentdb (H2 2.2)
                    Migrating schema "public" to version "1 - create claim assessments"
                    Migrating schema "public" to version "2 - information requests and assessment kind"
                    Successfully applied 2 migrations to schema "public", now at version v2
```

The `flyway_schema_history` rows of check 4 are therefore: `user_db` versions 1 and 2; `claim_db` versions 1 to 5; `assessment_db` versions 1 and 2, with the descriptions above.

### 13.6 If a start-up fails

| Symptom | Likely cause | What to do |
|---|---|---|
| `Migration checksum mismatch for migration version 1` | two services share one database | read-only diagnosis first: `select version, description, checksum from flyway_schema_history;`. Give each service its own database. **Do not run `repair` or `clean`.** |
| `database "claim_db" does not exist` | the `CREATE DATABASE` in 13.2 was not run | create it (after asking, on a shared server) |
| `password authentication failed` | wrong `*_DB_USERNAME` or `*_DB_PASSWORD` in this session | retype the password; check the variable names carry the service prefix |
| `Could not resolve placeholder 'USER_DB_USERNAME'` | the `postgres` profile is active and the variable is not set | set `*_DB_USERNAME` and `*_DB_PASSWORD`, or drop the profile and use the URL variable only |
| `permission denied for schema public` | PostgreSQL 15 or later and the role does not own the database | create the database `OWNER claims_app` (13.2) |
| `claims.security.jwt.secret ... must be set to at least 32 characters` | `JWT_SECRET` missing or short | set the same value (at least 32 characters) for every service; see the observation in section 12 about a fallback value in the committed properties |
| The environment variables seem to be ignored | the committed `application.properties` hard-code a datasource (commit `b33e4a3`) | see the conflict note in 13.3 |
| `/actuator/health` answers 503 `DOWN` | the database cannot be reached | check step 1 |

## 14. Demo users and login examples

### 14.1 The eight users

All eight use the documented public demo password `password123` in this document. It is demonstration data: anyone who can read the repository can log in as an administrator while the demo users are on (`DEMO_USERS` unset or `true`), which makes the system a demo and not a secure deployment. Set `DEMO_USERS=false` for anything else.

| Role | Users | How they exist |
|---|---|---|
| ADMIN | `admin1`, `admin2` | `admin1` is **seeded** at start-up by `DemoUsers`; `admin2` is created by `admin1` (14.2) |
| CLAIMS_OFFICER | `officer1`, `officer2` | `officer1` seeded; `officer2` created by `admin1` |
| MANAGER | `manager1`, `manager2` | `manager1` seeded; `manager2` created by `admin1` |
| CLAIMANT | `claimant1`, `claimant2` | `claimant1` seeded; `claimant2` created by `admin1` (or registered with `POST /api/v1/auth/register`) |

**Only the four `...1` users are seeded.** `DemoUsers` creates exactly `admin1`, `officer1`, `manager1` and `claimant1`, and `CLAUDE.md` section 4a forbids adding any other default credential or default administrator. The four `...2` users therefore do not exist after a start-up: create them once with the requests in 14.2 (this is Step 2 of the Swagger order in section 18). With the default in-memory H2 database they disappear when the User Service stops and must be created again; on PostgreSQL they persist. Making them seeded users would be a code change to `DemoUsers.java` and a change to that rule; it was not made.

### 14.2 Create the four extra users (as `admin1`)

`POST /api/v1/users` is ADMIN-only (`UserAccessRules`). Usernames are 3 to 50 characters of lower-case letters, digits, `.`, `_` and `-`; the password is 8 to 72 bytes. Log in as `admin1` first (14.3) and send `Authorization: Bearer <admin1 token>`. Real requests and responses:

**C-admin2. Create admin2 (ADMIN)**

User Service, as admin1: `POST /api/v1/users`

Request body:

```json
{
  "username": "admin2",
  "password": "password123",
  "role": "ADMIN"
}
```

Response `201`:

```json
{
  "username": "admin2",
  "role": "ADMIN",
  "active": true,
  "createdAt": "2026-10-27T04:21:34.985801500Z"
}
```

**C-officer2. Create officer2 (CLAIMS_OFFICER)**

User Service, as admin1: `POST /api/v1/users`

Request body:

```json
{
  "username": "officer2",
  "password": "password123",
  "role": "CLAIMS_OFFICER"
}
```

Response `201`:

```json
{
  "username": "officer2",
  "role": "CLAIMS_OFFICER",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.135680500Z"
}
```

**C-manager2. Create manager2 (MANAGER)**

User Service, as admin1: `POST /api/v1/users`

Request body:

```json
{
  "username": "manager2",
  "password": "password123",
  "role": "MANAGER"
}
```

Response `201`:

```json
{
  "username": "manager2",
  "role": "MANAGER",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.202411400Z"
}
```

**C-claimant2. Create claimant2 (CLAIMANT)**

User Service, as admin1: `POST /api/v1/users`

Request body:

```json
{
  "username": "claimant2",
  "password": "password123",
  "role": "CLAIMANT"
}
```

Response `201`:

```json
{
  "username": "claimant2",
  "role": "CLAIMANT",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.266241Z"
}
```

### 14.3 Login examples for all eight users

`POST /api/v1/auth/login` on the User Service (8185 by default). It needs no token. The response carries the `accessToken` (shown here as `<JWT>`), `expiresIn` in seconds and the `role` the token holds. Real requests and responses:

**L1. Log in as admin1**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "admin1",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "admin1",
  "role": "ADMIN"
}
```

**L2. Log in as admin2**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "admin2",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "admin2",
  "role": "ADMIN"
}
```

**L3. Log in as officer1**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "officer1",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "officer1",
  "role": "CLAIMS_OFFICER"
}
```

**L4. Log in as officer2**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "officer2",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "officer2",
  "role": "CLAIMS_OFFICER"
}
```

**L5. Log in as manager1**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "manager1",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "manager1",
  "role": "MANAGER"
}
```

**L6. Log in as manager2**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "manager2",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "manager2",
  "role": "MANAGER"
}
```

**L7. Log in as claimant1**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "claimant1",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "claimant1",
  "role": "CLAIMANT"
}
```

**L8. Log in as claimant2**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "claimant2",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "claimant2",
  "role": "CLAIMANT"
}
```

To get all eight tokens in PowerShell (the same pattern as the sequence in `docs/architecture.md` section 10):

```powershell
$users = 'admin1', 'admin2', 'officer1', 'officer2', 'manager1', 'manager2', 'claimant1', 'claimant2'
$token = @{}
foreach ($name in $users) {
    $r = Invoke-RestMethod -Method Post -Uri 'http://localhost:8185/api/v1/auth/login' -ContentType 'application/json' `
        -Body (@{ username = $name; password = 'password123' } | ConvertTo-Json)
    $token[$name] = $r.accessToken
}
$headers = @{ Authorization = "Bearer $($token['officer1'])" }     # for example, to call as officer1
```

Wrong password, unknown user and a deactivated user all give the same `401 INVALID_CREDENTIALS` (cases API-2.4 to API-2.6 in section 17); the user name is matched case-insensitively (API-2.2).

### 14.4 Register, who am I, list and get (User Service)

**UM1. Register a new claimant**

User Service, without a token: `POST /api/v1/auth/register`

Request body:

```json
{
  "username": "mei.tan",
  "password": "correct-horse-battery"
}
```

Response `201`:

```json
{
  "username": "mei.tan",
  "role": "CLAIMANT",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.832268900Z"
}
```

**UM2. Who am I**

User Service, as claimant1: `GET /api/v1/users/me`

Response `200`:

```json
{
  "username": "claimant1",
  "role": "CLAIMANT",
  "active": true,
  "createdAt": "2026-10-27T04:19:33.094551Z"
}
```

**UM3. List users, first page of 5**

User Service, as admin1: `GET /api/v1/users?page=0&size=5`

Response `200`:

```json
{
  "content": [
    {
      "username": "admin1",
      "role": "ADMIN",
      "active": true,
      "createdAt": "2026-10-27T04:19:32.686329Z"
    },
    {
      "username": "admin2",
      "role": "ADMIN",
      "active": true,
      "createdAt": "2026-10-27T04:21:34.985802Z"
    },
    {
      "username": "claimant1",
      "role": "CLAIMANT",
      "active": true,
      "createdAt": "2026-10-27T04:19:33.094551Z"
    },
    {
      "username": "claimant2",
      "role": "CLAIMANT",
      "active": true,
      "createdAt": "2026-10-27T04:21:35.266241Z"
    },
    {
      "username": "manager1",
      "role": "MANAGER",
      "active": true,
      "createdAt": "2026-10-27T04:19:32.995718Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 9,
  "totalPages": 2
}
```

**UM4. Get one user**

User Service, as admin1: `GET /api/v1/users/officer2`

Response `200`:

```json
{
  "username": "officer2",
  "role": "CLAIMS_OFFICER",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.135681Z"
}
```

Change role and activate or deactivate are shown in section 18, steps 14 and 15.

## 15. Complete end-to-end flows

Both flows were executed against freshly started services (jars built from commit `ee90578`, in-memory H2, spare ports). **Every request and response below is a real one**; only the access token is shortened. IDs and timestamps are from that run (a fresh database starts at `CLM-<year>-000101` and `IR-<year>-000001`): always use the values your own run returns. The examples show the method and path only; the run used the spare ports 28185 (User), 28181 (Claim) and 28182 (Assessment) instead of the defaults 8185, 8181 and 8182.

Preconditions: the eight users of section 14 exist and each has logged in. The step IDs (F1-01 and so on) are used by sections 18 and 20.

### 15.1 Flow 1: claimant1 submits, manager1 assigns, officer1 reviews, asks, claimant1 answers, officer1 approves and settles

| Step | Who | Service | Call | Result |
|---|---|---|---|---|
| F1-01 | claimant1 | Claim | `POST /api/v1/claims` | 201, `SUBMITTED`, `claimId` generated, `claimantId` = `claimant1` |
| F1-02 | claimant1 | Claim | `GET /api/v1/claims/{claimId}` | 200 |
| F1-03 | claimant1 | Claim | `GET /api/v1/claims` | 200, own claim only |
| F1-04 | claimant1 | Claim | `PATCH /api/v1/claims/{claimId}` | 200, amount corrected while `SUBMITTED` |
| F1-05 | claimant1 | Claim | `GET /api/v1/claims/{claimId}/updates` | 200, one edit recorded |
| F1-06 | officer1 | Claim | `GET /api/v1/officer/claims?assigned=false...` | 200, the claim is in the queue |
| F1-07 | manager1 | Claim | `POST /api/v1/officer/claims/{claimId}/assign` | 200, still `SUBMITTED`, assigned to officer1 |
| F1-08 | manager1 | Claim | `GET /api/v1/claims/{claimId}/assignments` | 200, `actor` = manager1 |
| F1-09 | officer1 | Assessment | `POST /api/v1/officer/claims/{claimId}/review` | 200, `UNDER_REVIEW` |
| F1-10 | officer1 | Assessment | `POST /api/v1/officer/claims/{claimId}/information-requests` | 201, `OPEN`; claim `MORE_INFO_REQUIRED` |
| F1-11 | claimant1 | Assessment | `GET /api/v1/claims/{claimId}/information-requests` | 200 |
| F1-12 | claimant1 | Assessment | `POST /api/v1/claims/{claimId}/information` | 200, `ANSWERED`; claim back to `UNDER_REVIEW` by itself |
| F1-13 | officer1 | Assessment | `POST /api/v1/officer/claims/{claimId}/decision` | 200, `APPROVED`, `approvedAmount` 2200.00 |
| F1-14 | manager1 | Claim | `GET /api/v1/management/summary` | 200, the claim is outstanding at its approved amount |
| F1-15 | officer1 | Assessment | `POST /api/v1/officer/claims/{claimId}/settlement` | 200, `SETTLED` |
| F1-16 to F1-18 | claimant1, officer1 | Claim, Assessment | final claim, status history, assessment history | 200 |

**F1-01. claimant1 submits a claim**

Claim Service, as claimant1: `POST /api/v1/claims`

Request body:

```json
{
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear-ended at a junction on Orchard Road",
  "incidentDate": "2026-10-20",
  "claimedAmount": 2400.00,
  "currency": "INR"
}
```

Response `201`:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantId": "claimant1",
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear-ended at a junction on Orchard Road",
  "incidentDate": "2026-10-20",
  "claimedAmount": 2400.00,
  "approvedAmount": null,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:36.276722400Z",
  "updatedAt": "2026-10-27T04:21:36.276722400Z"
}
```

**F1-02. claimant1 reads the claim back**

Claim Service, as claimant1: `GET /api/v1/claims/CLM-2026-000101`

Response `200`:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantId": "claimant1",
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear-ended at a junction on Orchard Road",
  "incidentDate": "2026-10-20",
  "claimedAmount": 2400.00,
  "approvedAmount": null,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:36.276722Z",
  "updatedAt": "2026-10-27T04:21:36.276722Z"
}
```

**F1-03. claimant1 lists their own claims**

Claim Service, as claimant1: `GET /api/v1/claims?page=0&size=5`

Response `200`:

```json
{
  "content": [
    {
      "id": 1,
      "claimId": "CLM-2026-000101",
      "claimantId": "claimant1",
      "claimantName": "Asha Rao",
      "policyNumber": "POL-2026-0001",
      "market": "SG",
      "claimType": "MOTOR",
      "claimedAmount": 2400.00,
      "approvedAmount": null,
      "currency": "INR",
      "status": "SUBMITTED",
      "assignedOfficerId": null,
      "createdAt": "2026-10-27T04:21:36.276722Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 1,
  "totalPages": 1
}
```

**F1-04. claimant1 corrects the amount while the claim is still SUBMITTED**

Claim Service, as claimant1: `PATCH /api/v1/claims/CLM-2026-000101`

Request body:

```json
{
  "claimedAmount": 2500.00,
  "incidentDescription": "Rear-ended at a junction on Orchard Road; the other driver left the scene"
}
```

Response `200`:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantId": "claimant1",
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear-ended at a junction on Orchard Road; the other driver left the scene",
  "incidentDate": "2026-10-20",
  "claimedAmount": 2500.00,
  "approvedAmount": null,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:36.276722Z",
  "updatedAt": "2026-10-27T04:21:36.533244400Z"
}
```

**F1-05. the edit is in the update history**

Claim Service, as claimant1: `GET /api/v1/claims/CLM-2026-000101/updates`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000101",
    "changedAt": "2026-10-27T04:21:36.541363Z",
    "actor": "claimant1",
    "changes": [
      "incidentDescription: changed",
      "claimedAmount: 2400.00 -> 2500.00"
    ]
  }
]
```

**F1-06. officer1 finds the claim in the unassigned queue**

Claim Service, as officer1: `GET /api/v1/officer/claims?assigned=false&status=SUBMITTED&page=0&size=5`

Response `200`:

```json
{
  "content": [
    {
      "id": 1,
      "claimId": "CLM-2026-000101",
      "claimantId": "claimant1",
      "claimantName": "Asha Rao",
      "policyNumber": "POL-2026-0001",
      "market": "SG",
      "claimType": "MOTOR",
      "claimedAmount": 2500.00,
      "approvedAmount": null,
      "currency": "INR",
      "status": "SUBMITTED",
      "assignedOfficerId": null,
      "createdAt": "2026-10-27T04:21:36.276722Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 1,
  "totalPages": 1
}
```

**F1-07. manager1 assigns the claim to officer1**

Claim Service, as manager1: `POST /api/v1/officer/claims/CLM-2026-000101/assign`

The status stays `SUBMITTED`; the Claim Service first asks the User Service whether `officer1` is an active claims officer.

Request body:

```json
{
  "officerId": "officer1"
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000101",
  "status": "SUBMITTED",
  "assignedOfficerId": "officer1",
  "updatedAt": "2026-10-27T04:21:36.726206200Z"
}
```

**F1-08. the assignment history names the manager as the actor**

Claim Service, as manager1: `GET /api/v1/claims/CLM-2026-000101/assignments`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000101",
    "previousOfficerId": null,
    "newOfficerId": "officer1",
    "changedAt": "2026-10-27T04:21:36.723621Z",
    "actor": "manager1"
  }
]
```

**F1-09. officer1 starts the review with a note**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000101/review`

No `officerId` is needed: an officer acts as the token's user.

Request body:

```json
{
  "assessmentNotes": "Photos match the description. Repair estimate still needed."
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000101",
  "status": "UNDER_REVIEW",
  "assignedOfficerId": "officer1",
  "updatedAt": "2026-10-27T04:21:37.351405Z"
}
```

**F1-10. officer1 asks the claimant for more information**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000101/information-requests`

Request body:

```json
{
  "question": "Please send the repair estimate from the workshop."
}
```

Response `201`:

```json
{
  "informationRequestId": "IR-2026-000001",
  "claimId": "CLM-2026-000101",
  "question": "Please send the repair estimate from the workshop.",
  "status": "OPEN",
  "requestedByOfficerId": "officer1",
  "createdAt": "2026-10-27T04:21:37.418523700Z",
  "answer": null,
  "answeredAt": null
}
```

The claim now waits for the claimant: `GET /api/v1/claims/CLM-2026-000101` returns `200` and `status` is `MORE_INFO_REQUIRED`.

**F1-11. claimant1 lists the information requests of the claim**

Assessment Service, as claimant1: `GET /api/v1/claims/CLM-2026-000101/information-requests`

Response `200`:

```json
[
  {
    "informationRequestId": "IR-2026-000001",
    "claimId": "CLM-2026-000101",
    "question": "Please send the repair estimate from the workshop.",
    "status": "OPEN",
    "requestedByOfficerId": "officer1",
    "createdAt": "2026-10-27T04:21:37.418524Z",
    "answer": null,
    "answeredAt": null
  }
]
```

**F1-12. claimant1 answers the request**

Assessment Service, as claimant1: `POST /api/v1/claims/CLM-2026-000101/information`

This was the last open request, so the claim returns to `UNDER_REVIEW` by itself; the officer does not call review again.

Request body:

```json
{
  "informationRequestId": "IR-2026-000001",
  "answer": "The workshop estimate is INR 2,200 for the bumper and the rear panel."
}
```

Response `200`:

```json
{
  "informationRequestId": "IR-2026-000001",
  "claimId": "CLM-2026-000101",
  "status": "ANSWERED",
  "answer": "The workshop estimate is INR 2,200 for the bumper and the rear panel.",
  "answeredAt": "2026-10-27T04:21:37.535075500Z"
}
```

The claim is back under review: `GET /api/v1/claims/CLM-2026-000101` returns `200` and `status` is `UNDER_REVIEW`.

**F1-13. officer1 approves 2200.00 of the 2500.00 claimed**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000101/decision`

Request body:

```json
{
  "decision": "APPROVED",
  "decisionReason": "Covered under the motor policy; amount follows the workshop estimate.",
  "approvedAmount": 2200.00,
  "currency": "INR"
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000101",
  "status": "APPROVED",
  "decisionReason": "Covered under the motor policy; amount follows the workshop estimate.",
  "approvedAmount": 2200.00,
  "currency": "INR",
  "decidedByOfficerId": "officer1",
  "decidedAt": "2026-10-27T04:21:37.638378200Z",
  "updatedAt": "2026-10-27T04:21:37.638378200Z"
}
```

**F1-14. manager1 reads the summary: the approved claim counts at its approved amount**

Claim Service, as manager1: `GET /api/v1/management/summary`

Response `200`:

```json
{
  "generatedAt": "2026-10-27T04:21:37.684314300Z",
  "liabilityNote": "Simple estimate: for claims not yet decided, the CLAIMED amount; for APPROVED claims awaiting settlement, the APPROVED amount; settled and rejected claims are left out. Grouped by currency, with no exchange-rate conversion. It is neither the expected settlement nor a booked reserve. Not an actuarial reserve.",
  "outstandingClaimCount": 1,
  "outstandingLiabilityByCurrency": [
    {
      "currency": "INR",
      "claimCount": 1,
      "estimatedLiability": 2200.00
    }
  ],
  "countsByStatus": {
    "SUBMITTED": 0,
    "UNDER_REVIEW": 0,
    "MORE_INFO_REQUIRED": 0,
    "APPROVED": 1,
    "REJECTED": 0,
    "SETTLED": 0
  }
}
```

**F1-15. officer1 settles the approved claim**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000101/settlement`

Request body:

```json
{
  "settlementNote": "Paid by bank transfer."
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000101",
  "status": "SETTLED",
  "approvedAmount": 2200.00,
  "currency": "INR",
  "settledByOfficerId": "officer1",
  "settledAt": "2026-10-27T04:21:37.718493600Z",
  "updatedAt": "2026-10-27T04:21:37.719492700Z"
}
```

**F1-16. final state of the claim**

Claim Service, as claimant1: `GET /api/v1/claims/CLM-2026-000101`

Response `200`:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantId": "claimant1",
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Rear-ended at a junction on Orchard Road; the other driver left the scene",
  "incidentDate": "2026-10-20",
  "claimedAmount": 2500.00,
  "approvedAmount": 2200.00,
  "currency": "INR",
  "status": "SETTLED",
  "assignedOfficerId": "officer1",
  "decisionReason": "Covered under the motor policy; amount follows the workshop estimate.",
  "decidedAt": "2026-10-27T04:21:37.638378Z",
  "settledAt": "2026-10-27T04:21:37.718494Z",
  "createdAt": "2026-10-27T04:21:36.276722Z",
  "updatedAt": "2026-10-27T04:21:37.719493Z"
}
```

**F1-17. status history**

Claim Service, as claimant1: `GET /api/v1/claims/CLM-2026-000101/history`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": null,
    "toStatus": "SUBMITTED",
    "changedAt": "2026-10-27T04:21:36.338264Z",
    "actor": "claimant1"
  },
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": "SUBMITTED",
    "toStatus": "UNDER_REVIEW",
    "changedAt": "2026-10-27T04:21:37.349809Z",
    "actor": "officer1"
  },
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": "UNDER_REVIEW",
    "toStatus": "MORE_INFO_REQUIRED",
    "changedAt": "2026-10-27T04:21:37.432366Z",
    "actor": "officer1"
  },
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": "MORE_INFO_REQUIRED",
    "toStatus": "UNDER_REVIEW",
    "changedAt": "2026-10-27T04:21:37.560901Z",
    "actor": "claimant1"
  },
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": "UNDER_REVIEW",
    "toStatus": "APPROVED",
    "changedAt": "2026-10-27T04:21:37.638378Z",
    "actor": "officer1"
  },
  {
    "claimId": "CLM-2026-000101",
    "fromStatus": "APPROVED",
    "toStatus": "SETTLED",
    "changedAt": "2026-10-27T04:21:37.718494Z",
    "actor": "officer1"
  }
]
```

**F1-18. assessment history (officers only)**

Assessment Service, as officer1: `GET /api/v1/officer/claims/CLM-2026-000101/assessments`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000101",
    "officerId": "officer1",
    "kind": "NOTE",
    "notes": "Photos match the description. Repair estimate still needed.",
    "createdAt": "2026-10-27T04:21:37.245857Z"
  },
  {
    "claimId": "CLM-2026-000101",
    "officerId": "officer1",
    "kind": "APPROVED",
    "notes": "Covered under the motor policy; amount follows the workshop estimate.",
    "createdAt": "2026-10-27T04:21:37.620089Z"
  },
  {
    "claimId": "CLM-2026-000101",
    "officerId": "officer1",
    "kind": "SETTLED",
    "notes": "Paid by bank transfer.",
    "createdAt": "2026-10-27T04:21:37.706157Z"
  }
]
```

### 15.2 Flow 2: claimant2 submits, manager2 assigns to officer2, officer2 reviews and rejects

| Step | Who | Service | Call | Result |
|---|---|---|---|---|
| F2-01 | claimant2 | Claim | `POST /api/v1/claims` | 201, `SUBMITTED` |
| F2-02 | manager2 | Claim | `POST /api/v1/officer/claims/{claimId}/assign` | 200 |
| F2-03 | officer2 | Assessment | `POST .../review` | 200, `UNDER_REVIEW` |
| F2-04 | officer2 | Assessment | `POST .../decision` with `REJECTED` and no amount | 200, `REJECTED`, `approvedAmount` null |
| F2-05 | officer2 | Assessment | `POST .../settlement` | **409**, a rejected claim can never be settled |
| F2-06 to F2-08 | claimant2, officer2 | Claim, Assessment | final claim, status history, assessment history | 200 |
| F2-09, F2-10 | manager2 | Claim | `GET /api/v1/management/summary`, `/officers` | 200, both flows counted |

**F2-01. claimant2 submits a claim**

Claim Service, as claimant2: `POST /api/v1/claims`

Request body:

```json
{
  "claimantName": "Wei Lim",
  "claimantEmail": "wei.lim@example.com",
  "policyNumber": "HK-PROP-0077",
  "market": "HK",
  "claimType": "PROPERTY",
  "incidentDescription": "Water damage from a burst pipe in the kitchen",
  "incidentDate": "2026-10-18",
  "claimedAmount": 18000.00,
  "currency": "HKD"
}
```

Response `201`:

```json
{
  "id": 2,
  "claimId": "CLM-2026-000102",
  "claimantId": "claimant2",
  "claimantName": "Wei Lim",
  "claimantEmail": "wei.lim@example.com",
  "policyNumber": "HK-PROP-0077",
  "market": "HK",
  "claimType": "PROPERTY",
  "incidentDescription": "Water damage from a burst pipe in the kitchen",
  "incidentDate": "2026-10-18",
  "claimedAmount": 18000.00,
  "approvedAmount": null,
  "currency": "HKD",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:37.785655Z",
  "updatedAt": "2026-10-27T04:21:37.785655Z"
}
```

**F2-02. manager2 assigns the claim to officer2**

Claim Service, as manager2: `POST /api/v1/officer/claims/CLM-2026-000102/assign`

Request body:

```json
{
  "officerId": "officer2"
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000102",
  "status": "SUBMITTED",
  "assignedOfficerId": "officer2",
  "updatedAt": "2026-10-27T04:21:37.810571100Z"
}
```

**F2-03. officer2 starts the review**

Assessment Service, as officer2: `POST /api/v1/officer/claims/CLM-2026-000102/review`

Request body:

```json
{
  "assessmentNotes": "The plumber report shows the pipe was already corroded; checking the policy exclusions."
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000102",
  "status": "UNDER_REVIEW",
  "assignedOfficerId": "officer2",
  "updatedAt": "2026-10-27T04:21:37.839924800Z"
}
```

**F2-04. officer2 rejects the claim (a rejection carries no approvedAmount)**

Assessment Service, as officer2: `POST /api/v1/officer/claims/CLM-2026-000102/decision`

Request body:

```json
{
  "decision": "REJECTED",
  "decisionReason": "Gradual corrosion is excluded by section 4.2 of the property policy.",
  "currency": "HKD"
}
```

Response `200`:

```json
{
  "claimId": "CLM-2026-000102",
  "status": "REJECTED",
  "decisionReason": "Gradual corrosion is excluded by section 4.2 of the property policy.",
  "approvedAmount": null,
  "currency": "HKD",
  "decidedByOfficerId": "officer2",
  "decidedAt": "2026-10-27T04:21:37.869984200Z",
  "updatedAt": "2026-10-27T04:21:37.870984200Z"
}
```

**F2-05. a rejected claim can never be settled**

Assessment Service, as officer2: `POST /api/v1/officer/claims/CLM-2026-000102/settlement`

Request body:

```json
{
  "settlementNote": "should be refused"
}
```

Response `409`:

```json
{
  "timestamp": "2026-10-27T04:21:37.918181700Z",
  "status": 409,
  "error": "Conflict",
  "code": "INVALID_CLAIM_TRANSITION",
  "message": "A rejected claim cannot be settled",
  "path": "/api/v1/officer/claims/CLM-2026-000102/settlement"
}
```

**F2-06. final state of the claim: REJECTED, approvedAmount null**

Claim Service, as claimant2: `GET /api/v1/claims/CLM-2026-000102`

Response `200`:

```json
{
  "id": 2,
  "claimId": "CLM-2026-000102",
  "claimantId": "claimant2",
  "claimantName": "Wei Lim",
  "claimantEmail": "wei.lim@example.com",
  "policyNumber": "HK-PROP-0077",
  "market": "HK",
  "claimType": "PROPERTY",
  "incidentDescription": "Water damage from a burst pipe in the kitchen",
  "incidentDate": "2026-10-18",
  "claimedAmount": 18000.00,
  "approvedAmount": null,
  "currency": "HKD",
  "status": "REJECTED",
  "assignedOfficerId": "officer2",
  "decisionReason": "Gradual corrosion is excluded by section 4.2 of the property policy.",
  "decidedAt": "2026-10-27T04:21:37.869984Z",
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:37.785655Z",
  "updatedAt": "2026-10-27T04:21:37.870984Z"
}
```

**F2-07. status history**

Claim Service, as claimant2: `GET /api/v1/claims/CLM-2026-000102/history`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000102",
    "fromStatus": null,
    "toStatus": "SUBMITTED",
    "changedAt": "2026-10-27T04:21:37.786663Z",
    "actor": "claimant2"
  },
  {
    "claimId": "CLM-2026-000102",
    "fromStatus": "SUBMITTED",
    "toStatus": "UNDER_REVIEW",
    "changedAt": "2026-10-27T04:21:37.838830Z",
    "actor": "officer2"
  },
  {
    "claimId": "CLM-2026-000102",
    "fromStatus": "UNDER_REVIEW",
    "toStatus": "REJECTED",
    "changedAt": "2026-10-27T04:21:37.869984Z",
    "actor": "officer2"
  }
]
```

**F2-08. assessment history**

Assessment Service, as officer2: `GET /api/v1/officer/claims/CLM-2026-000102/assessments`

Response `200`:

```json
[
  {
    "claimId": "CLM-2026-000102",
    "officerId": "officer2",
    "kind": "NOTE",
    "notes": "The plumber report shows the pipe was already corroded; checking the policy exclusions.",
    "createdAt": "2026-10-27T04:21:37.829666Z"
  },
  {
    "claimId": "CLM-2026-000102",
    "officerId": "officer2",
    "kind": "REJECTED",
    "notes": "Gradual corrosion is excluded by section 4.2 of the property policy.",
    "createdAt": "2026-10-27T04:21:37.860141Z"
  }
]
```

**F2-09. management summary after both flows**

Claim Service, as manager2: `GET /api/v1/management/summary`

Response `200`:

```json
{
  "generatedAt": "2026-10-27T04:21:37.965831500Z",
  "liabilityNote": "Simple estimate: for claims not yet decided, the CLAIMED amount; for APPROVED claims awaiting settlement, the APPROVED amount; settled and rejected claims are left out. Grouped by currency, with no exchange-rate conversion. It is neither the expected settlement nor a booked reserve. Not an actuarial reserve.",
  "outstandingClaimCount": 0,
  "outstandingLiabilityByCurrency": [],
  "countsByStatus": {
    "SUBMITTED": 0,
    "UNDER_REVIEW": 0,
    "MORE_INFO_REQUIRED": 0,
    "APPROVED": 0,
    "REJECTED": 1,
    "SETTLED": 1
  }
}
```

**F2-10. officer metrics after both flows**

Claim Service, as manager2: `GET /api/v1/management/officers`

Response `200`:

```json
{
  "generatedAt": "2026-10-27T04:21:37.978987100Z",
  "note": "Open workload is by CURRENT assignee (no reassignment history); decisions are by the deciding officer. Amounts are grouped by currency and never added across currencies.",
  "officers": [
    {
      "officerId": "officer1",
      "openClaimCount": 0,
      "openClaimsByStatus": {
        "SUBMITTED": 0,
        "UNDER_REVIEW": 0,
        "MORE_INFO_REQUIRED": 0,
        "APPROVED": 0
      },
      "openClaimedAmountByCurrency": [],
      "decidedCount": 1,
      "approvedCount": 1,
      "settledCount": 1,
      "rejectedCount": 0,
      "approvedAmountByCurrency": [
        {
          "currency": "INR",
          "claimCount": 1,
          "amount": 2200.00
        }
      ]
    },
    {
      "officerId": "officer2",
      "openClaimCount": 0,
      "openClaimsByStatus": {
        "SUBMITTED": 0,
        "UNDER_REVIEW": 0,
        "MORE_INFO_REQUIRED": 0,
        "APPROVED": 0
      },
      "openClaimedAmountByCurrency": [],
      "decidedCount": 1,
      "approvedCount": 0,
      "settledCount": 0,
      "rejectedCount": 1,
      "approvedAmountByCurrency": []
    }
  ]
}
```

### 15.3 What the two flows cover

The two flows (with section 14 and section 18 steps 14 and 15) execute **all 26 endpoints**: register, login, me, create user, list users, get user, change role, activate or deactivate (User Service); create claim, list, get, patch, history, assignment history, update history, officer claims, assign, management summary, officer metrics (Claim Service); review, assessment history, decision approve, decision reject, settlement, information request, information request list, information answer (Assessment Service). Section 20 maps each endpoint to its steps.

## 16. Role testing matrix

Every one of the 26 public endpoints was called as **no token, CLAIMANT (`claimant1`), CLAIMS_OFFICER (`officer1`), MANAGER (`manager1`) and ADMIN (`admin1`)**, each time on freshly prepared state that is valid for the call (for example a new `UNDER_REVIEW` claim assigned to `officer1` for the decision). That is **130 calls; each result matched the expectation written beforehand from the access rule tables**. The number in a cell is the HTTP status of a valid request by that identity.

| # | Endpoint | Service | No token | CLAIMANT | CLAIMS_OFFICER | MANAGER | ADMIN | Scope |
|---|---|---|---|---|---|---|---|---|
| 1 | `POST /api/v1/auth/register` | User | 201 | 201 | 201 | 201 | 201 | open: a token is ignored |
| 2 | `POST /api/v1/auth/login` | User | 200 | 200 | 200 | 200 | 200 | open: a token is ignored |
| 3 | `GET /api/v1/users/me` | User | 401 | 200 | 200 | 200 | 200 | the caller's own account |
| 4 | `POST /api/v1/users` | User | 401 | 403 | 403 | 403 | 201 | ADMIN only |
| 5 | `GET /api/v1/users` | User | 401 | 403 | 403 | 403 | 200 | ADMIN only |
| 6 | `GET /api/v1/users/{username}` | User | 401 | 403 | 403 | 403 | 200 | ADMIN only |
| 7 | `PUT /api/v1/users/{username}/role` | User | 401 | 403 | 403 | 403 | 200 | ADMIN only |
| 8 | `PUT /api/v1/users/{username}/active` | User | 401 | 403 | 403 | 403 | 200 | ADMIN only |
| 9 | `POST /api/v1/claims` | Claim | 401 | 201 | 403 | 403 | 201 | claimant (the claim is owned by the caller) |
| 10 | `GET /api/v1/claims` | Claim | 401 | 200 | 200 | 200 | 200 | claimant: own claims; officer: unassigned and own; manager, admin: all |
| 11 | `GET /api/v1/claims/{claimId}` | Claim | 401 | 200 | 200 | 200 | 200 | claimant: own; officer: unassigned or own; manager, admin: any |
| 12 | `PATCH /api/v1/claims/{claimId}` | Claim | 401 | 200 | 403 | 403 | 200 | claimant: own claim, while SUBMITTED |
| 13 | `GET /api/v1/claims/{claimId}/history` | Claim | 401 | 200 | 200 | 200 | 200 | same scope as the claim |
| 14 | `GET /api/v1/claims/{claimId}/assignments` | Claim | 401 | 200 | 200 | 200 | 200 | same scope as the claim |
| 15 | `GET /api/v1/claims/{claimId}/updates` | Claim | 401 | 200 | 200 | 200 | 200 | same scope as the claim |
| 16 | `GET /api/v1/officer/claims` | Claim | 401 | 403 | 200 | 200 | 200 | officer: unassigned and own; manager, admin: all |
| 17 | `POST /api/v1/officer/claims/{claimId}/assign` | Claim | 401 | 403 | 200 | 200 | 200 | officer: only to themselves; manager, admin: any active claims officer |
| 18 | `GET /api/v1/management/summary` | Claim | 401 | 403 | 403 | 200 | 200 | MANAGER, ADMIN |
| 19 | `GET /api/v1/management/officers` | Claim | 401 | 403 | 403 | 200 | 200 | MANAGER, ADMIN |
| 20 | `POST /api/v1/officer/claims/{claimId}/review` | Assessment | 401 | 403 | 200 | 403 | 200 | officer acts as themselves; admin must name the officer |
| 21 | `GET /api/v1/officer/claims/{claimId}/assessments` | Assessment | 401 | 403 | 200 | 403 | 200 | officer (in scope); admin. Never a claimant or a manager |
| 22 | `POST /api/v1/officer/claims/{claimId}/decision` | Assessment | 401 | 403 | 200 | 403 | 200 | the assigned officer; admin must name the officer |
| 23 | `POST /api/v1/officer/claims/{claimId}/settlement` | Assessment | 401 | 403 | 200 | 403 | 200 | the assigned officer; admin must name the officer |
| 24 | `POST /api/v1/officer/claims/{claimId}/information-requests` | Assessment | 401 | 403 | 201 | 403 | 201 | the assigned officer; admin must name the officer |
| 25 | `GET /api/v1/claims/{claimId}/information-requests` | Assessment | 401 | 200 | 200 | 200 | 200 | claimant: own claim; officer: unassigned or own; manager, admin: any |
| 26 | `POST /api/v1/claims/{claimId}/information` | Assessment | 401 | 200 | 403 | 403 | 200 | claimant: own claim; admin |

Reading the matrix:

* **No token** is `401 UNAUTHENTICATED` everywhere except the three open routes (register, login; `GET /actuator/health` is open as well).
* **403 `FORBIDDEN`** is the security chain refusing the role before any service code runs. Nothing is changed or stored.
* **200 / 201 means "allowed and in scope".** For a claimant the claim must be their own; for a claims officer it must be unassigned or assigned to them; a claim outside that scope answers **404 `CLAIM_NOT_FOUND`**, exactly like an unknown claim (section 17). A manager and an administrator see every claim.
* **ADMIN is allowed on every public route**, but acts for an officer only by naming one: on review, decision, settlement and information request an administrator who names no officer gets **400 `VALIDATION_FAILED`** (API-20.4, API-22.19, API-24.10), and an administrator naming an officer other than the one the claim is assigned to gets **409 `OFFICER_MISMATCH`** (API-20.10, API-22.17). In the matrix the administrator's body names `officer1`.
* **A claims officer** never has to send an officer ID; naming another officer is **403 `OFFICER_IDENTITY_MISMATCH`** (API-20.3, API-22.18, API-23.9, API-24.9). On `assign` an officer may only name themselves (**403 `ASSIGNMENT_NOT_PERMITTED`**, API-17.3).
* **The assessment history** (`GET .../assessments`) is never readable by a claimant or a manager (403).

### 16.1 The response codes, with real bodies

Every error has the body `{timestamp, status, error, code, message, path}` and never a stack trace.

| Status | Meaning here | Typical `code` values |
|---|---|---|
| **200** / **201** | success (201 for create claim, register, create user, information request) | n/a |
| **400** | invalid input, or a business rule on the input | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `NO_FIELDS_TO_UPDATE`, `CONFLICTING_FILTERS`, `INVALID_OFFICER`, `INVALID_ROLE`, `PASSWORD_TOO_LONG`, `CURRENCY_MISMATCH`, `INVALID_APPROVED_AMOUNT` |
| **401** | no valid token, or a failed login | `UNAUTHENTICATED`, `INVALID_CREDENTIALS` |
| **403** | known caller, not allowed | `FORBIDDEN`, `OFFICER_IDENTITY_MISMATCH`, `ASSIGNMENT_NOT_PERMITTED` |
| **404** | unknown claim, request or user, **or a claim outside the caller's scope** | `CLAIM_NOT_FOUND`, `INFORMATION_REQUEST_NOT_FOUND`, `USER_NOT_FOUND` |
| **405**, **415** | wrong method, wrong content type | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` |
| **409** | invalid lifecycle move or duplicate | `INVALID_CLAIM_TRANSITION`, `OFFICER_MISMATCH`, `CLAIM_NOT_EDITABLE`, `USERNAME_TAKEN`, `CANNOT_CHANGE_OWN_ROLE`, `CANNOT_DEACTIVATE_SELF` |
| **503** | a service this one needs is unreachable or too slow | `DOWNSTREAM_UNAVAILABLE` |
| **502** | a service this one needs answered with something it could not use | `DOWNSTREAM_ERROR` |

**E-401. 401 UNAUTHENTICATED: no token**

Claim Service, without a token: `GET /api/v1/claims`

Response `401`:

```json
{
  "timestamp": "2026-10-27T04:32:23.770971800Z",
  "status": 401,
  "error": "Unauthorized",
  "code": "UNAUTHENTICATED",
  "message": "A valid Bearer token is required. Log in with POST /api/v1/auth/login on the User Service.",
  "path": "/api/v1/claims"
}
```

**E-403. 403 FORBIDDEN: the role may not use the route**

Claim Service, as claimant1: `GET /api/v1/management/summary`

Response `403`:

```json
{
  "timestamp": "2026-10-27T04:32:23.821148500Z",
  "status": 403,
  "error": "Forbidden",
  "code": "FORBIDDEN",
  "message": "Your role is not allowed to use this route",
  "path": "/api/v1/management/summary"
}
```

**E-404. 404 CLAIM_NOT_FOUND: an unknown claim (a claim outside the caller's scope gives exactly the same body)**

Claim Service, as claimant1: `GET /api/v1/claims/CLM-2026-999999`

Response `404`:

```json
{
  "timestamp": "2026-10-27T04:32:23.832039100Z",
  "status": 404,
  "error": "Not Found",
  "code": "CLAIM_NOT_FOUND",
  "message": "Claim CLM-2026-999999 was not found",
  "path": "/api/v1/claims/CLM-2026-999999"
}
```

**E-404b. 404 CLAIM_NOT_FOUND for a claim that exists but belongs to someone else**

Claim Service, as claimant2: `GET /api/v1/claims/CLM-2026-000105`

Response `404`:

```json
{
  "timestamp": "2026-10-27T04:32:23.848850800Z",
  "status": 404,
  "error": "Not Found",
  "code": "CLAIM_NOT_FOUND",
  "message": "Claim CLM-2026-000105 was not found",
  "path": "/api/v1/claims/CLM-2026-000105"
}
```

**E-400. 400 VALIDATION_FAILED: every broken field is listed in the message**

Claim Service, as claimant1: `POST /api/v1/claims`

Request body:

```json
{
  "claimantName": "Asha Rao",
  "claimantEmail": "not-an-email",
  "policyNumber": "POL-1",
  "market": "sg",
  "claimType": "MOTOR",
  "incidentDescription": "x",
  "incidentDate": "2026-10-20",
  "claimedAmount": 0,
  "currency": "INR"
}
```

Response `400`:

```json
{
  "timestamp": "2026-10-27T04:32:23.911397Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "claimantEmail must be a well-formed email address; claimedAmount must be greater than 0; market must be a 2-letter upper-case market code, e.g. SG",
  "path": "/api/v1/claims"
}
```

**E-400b. 400 INVALID_APPROVED_AMOUNT: approving more than was claimed**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000105/decision`

Request body:

```json
{
  "decision": "APPROVED",
  "decisionReason": "x",
  "approvedAmount": 5000.00,
  "currency": "INR"
}
```

Response `400`:

```json
{
  "timestamp": "2026-10-27T04:32:23.944736800Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_APPROVED_AMOUNT",
  "message": "approvedAmount cannot exceed the claimed amount",
  "path": "/api/v1/officer/claims/CLM-2026-000105/decision"
}
```

**E-403b. 403 OFFICER_IDENTITY_MISMATCH: an officer names someone else**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000105/decision`

Request body:

```json
{
  "decision": "REJECTED",
  "decisionReason": "x",
  "currency": "INR",
  "decidedByOfficerId": "officer2"
}
```

Response `403`:

```json
{
  "timestamp": "2026-10-27T04:32:23.954720Z",
  "status": 403,
  "error": "Forbidden",
  "code": "OFFICER_IDENTITY_MISMATCH",
  "message": "A claims officer can only act as themselves; the officer ID in the request does not match the token",
  "path": "/api/v1/officer/claims/CLM-2026-000105/decision"
}
```

**E-409. 409 INVALID_CLAIM_TRANSITION: a second review of a claim already under review**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000105/review`

Request body:

```json
{}
```

Response `409`:

```json
{
  "timestamp": "2026-10-27T04:32:23.989473Z",
  "status": 409,
  "error": "Conflict",
  "code": "INVALID_CLAIM_TRANSITION",
  "message": "Review can only be started for a SUBMITTED or MORE_INFO_REQUIRED claim, but claim is UNDER_REVIEW",
  "path": "/api/v1/officer/claims/CLM-2026-000105/review"
}
```

The 409 example (`INVALID_CLAIM_TRANSITION`) for a rejected claim is F2-05 above, the 400 `INVALID_OFFICER` example is S14-4 in section 18, and the 503 examples are in section 17.2.

## 17. Positive and negative scenarios for every endpoint

### 17.1 Scenario catalogue

**210 scenarios were executed live, 45 positive and 165 negative; every status and every `code` in the tables matched the expectation.** The IDs are `API-<endpoint>.<n>`, where `<endpoint>` is the number of the row in the matrix of section 16 (User Service 1 to 8, Claim Service 9 to 19, Assessment Service 20 to 26). Each endpoint has at least one positive and at least two negative scenarios. "Expected status" is what the run returned; where a `code` is shown it was checked too.

Observations from running them:

* A claim **outside the caller's scope answers exactly like an unknown claim**: the same 404, the same `CLAIM_NOT_FOUND` and the same message shape, for another claimant (API-11.3) and for an officer who is not the assigned one (API-11.4). That is deliberate, so claim numbers cannot be probed.
* **Unknown JSON properties are ignored**: a `role` in the register body (API-1.2) and a `claimId` or `claimantId` in the create-claim body (API-9.2) have no effect; the server generates the first and the token supplies the second.
* **A second answer is refused as `INVALID_CLAIM_TRANSITION`** (API-26.2), not as `INFORMATION_REQUEST_ALREADY_ANSWERED`: only one request can be open at a time and answering the last one resumes the claim, so the claim is no longer `MORE_INFO_REQUIRED` by the time a second answer arrives. The code `INFORMATION_REQUEST_ALREADY_ANSWERED` exists in `InformationRequest.answer` but was not reachable through the API in these runs.
* `INVALID_MARKET` is not in the catalogue because it needs `claims.markets.allowed` to be set; with the default (empty) any 2-letter upper-case market is accepted. It is covered by `MarketValidationTest`.
* A wrong method gives `405` only for a caller allowed to reach the route: with an administrator token (API-1.11); with no token the same request is `401`.
* `415` was exercised on register and create claim only; the other body-taking routes share the same handler.

#### 1. `POST /api/v1/auth/register`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-1.1 | Register a new claimant | no token | 201 |  |
| API-1.2 | A `role` field in the body is ignored: the account is a CLAIMANT | no token | 201 |  |
| API-1.3 | Username already taken | no token | 409 | `USERNAME_TAKEN` |
| API-1.4 | Username too short (2 characters) | no token | 400 | `VALIDATION_FAILED` |
| API-1.5 | Username with an upper-case letter | no token | 400 | `VALIDATION_FAILED` |
| API-1.6 | Password of 7 characters | no token | 400 | `VALIDATION_FAILED` |
| API-1.7 | Password of 40 two-byte characters (80 bytes, over the 72-byte limit) | no token | 400 | `PASSWORD_TOO_LONG` |
| API-1.8 | Malformed JSON | no token | 400 | `MALFORMED_REQUEST` |
| API-1.9 | No body | no token | 400 | `MALFORMED_REQUEST` |
| API-1.10 | Content type `text/plain` | no token | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| API-1.11 | Wrong method (`GET`) with an administrator token | admin1 | 405 | `METHOD_NOT_ALLOWED` |

#### 2. `POST /api/v1/auth/login`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-2.1 | Valid credentials | no token | 200 |  |
| API-2.2 | Username in another case (matched case-insensitively) | no token | 200 |  |
| API-2.3 | A garbage `Authorization` header is ignored by login | no token | 200 |  |
| API-2.4 | Wrong password | no token | 401 | `INVALID_CREDENTIALS` |
| API-2.5 | Unknown user (same 401 and message as a wrong password) | no token | 401 | `INVALID_CREDENTIALS` |
| API-2.6 | Deactivated user (same 401 and message again) | no token | 401 | `INVALID_CREDENTIALS` |
| API-2.7 | Blank username | no token | 400 | `VALIDATION_FAILED` |
| API-2.8 | Password missing | no token | 400 | `VALIDATION_FAILED` |
| API-2.9 | Malformed JSON | no token | 400 | `MALFORMED_REQUEST` |

#### 3. `GET /api/v1/users/me`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-3.1 | Own account, as a claimant | claimant1 | 200 |  |
| API-3.2 | Own account, as an administrator | admin1 | 200 |  |
| API-3.3 | No token | no token | 401 | `UNAUTHENTICATED` |
| API-3.4 | Token that is not a JWT | garbage | 401 | `UNAUTHENTICATED` |

#### 4. `POST /api/v1/users`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-4.1 | Create an officer | admin1 | 201 |  |
| API-4.2 | Username already taken | admin1 | 409 | `USERNAME_TAKEN` |
| API-4.3 | Role `SERVICE` cannot be given to a person | admin1 | 400 | `INVALID_ROLE` |
| API-4.4 | Unknown role `SUPERUSER` | admin1 | 400 | `MALFORMED_REQUEST` |
| API-4.5 | Role missing | admin1 | 400 | `VALIDATION_FAILED` |
| API-4.6 | A manager may not create users | manager1 | 403 | `FORBIDDEN` |
| API-4.7 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 5. `GET /api/v1/users`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-5.1 | First page of 3 | admin1 | 200 |  |
| API-5.2 | `size=101` (maximum is 100) | admin1 | 400 | `VALIDATION_FAILED` |
| API-5.3 | `size=0` | admin1 | 400 | `VALIDATION_FAILED` |
| API-5.4 | `page=-1` | admin1 | 400 | `VALIDATION_FAILED` |
| API-5.5 | `page=abc` | admin1 | 400 | `INVALID_PARAMETER` |
| API-5.6 | A claims officer may not list users | officer1 | 403 | `FORBIDDEN` |

#### 6. `GET /api/v1/users/{username}`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-6.1 | An existing user | admin1 | 200 |  |
| API-6.2 | Unknown user | admin1 | 404 | `USER_NOT_FOUND` |
| API-6.3 | A claimant may not read another user | claimant1 | 403 | `FORBIDDEN` |

#### 7. `PUT /api/v1/users/{username}/role`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-7.1 | Change another user's role | admin1 | 200 |  |
| API-7.2 | An administrator cannot change their own role | admin1 | 409 | `CANNOT_CHANGE_OWN_ROLE` |
| API-7.3 | Role `SERVICE` | admin1 | 400 | `INVALID_ROLE` |
| API-7.4 | Unknown role value | admin1 | 400 | `MALFORMED_REQUEST` |
| API-7.5 | Role missing | admin1 | 400 | `VALIDATION_FAILED` |
| API-7.6 | Unknown user | admin1 | 404 | `USER_NOT_FOUND` |
| API-7.7 | A claimant cannot promote themselves | claimant1 | 403 | `FORBIDDEN` |

#### 8. `PUT /api/v1/users/{username}/active`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-8.1 | Reactivate or confirm an active user | admin1 | 200 |  |
| API-8.2 | Deactivate a user | admin1 | 200 |  |
| API-8.3 | An administrator cannot deactivate themselves | admin1 | 409 | `CANNOT_DEACTIVATE_SELF` |
| API-8.4 | `active` missing | admin1 | 400 | `VALIDATION_FAILED` |
| API-8.5 | `active` is not a boolean | admin1 | 400 | `MALFORMED_REQUEST` |
| API-8.6 | Unknown user | admin1 | 404 | `USER_NOT_FOUND` |
| API-8.7 | A manager may not switch accounts | manager1 | 403 | `FORBIDDEN` |

#### 9. `POST /api/v1/claims`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-9.1 | Submit a claim | claimant1 | 201 |  |
| API-9.2 | `claimId` and `claimantId` in the body are ignored: the server generates the first, the token gives the second | claimant1 | 201 |  |
| API-9.3 | policyNumber missing | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.4 | policyNumber `!!` | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.5 | market in lower case | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.6 | currency in lower case | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.7 | claimedAmount 0 | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.8 | claimedAmount negative | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.9 | claimedAmount with 3 decimals | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.10 | incidentDate in the future | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.11 | claimantEmail not an e-mail address | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.12 | claimType `BOAT` | claimant1 | 400 | `MALFORMED_REQUEST` |
| API-9.13 | incidentDescription of 2001 characters | claimant1 | 400 | `VALIDATION_FAILED` |
| API-9.14 | Malformed JSON | claimant1 | 400 | `MALFORMED_REQUEST` |
| API-9.15 | Content type `text/plain` | claimant1 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| API-9.16 | A claims officer may not submit a claim | officer1 | 403 | `FORBIDDEN` |
| API-9.17 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 10. `GET /api/v1/claims`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-10.1 | A claimant lists their own claims | claimant1 | 200 |  |
| API-10.2 | Filter `status=SUBMITTED&claimType=MOTOR&market=SG` | manager1 | 200 |  |
| API-10.3 | `assigned=false` together with `assignedOfficerId` | manager1 | 400 | `CONFLICTING_FILTERS` |
| API-10.4 | `status=BOGUS` | manager1 | 400 | `INVALID_PARAMETER` |
| API-10.5 | `size=101` | manager1 | 400 | `VALIDATION_FAILED` |
| API-10.6 | `page=-1` | manager1 | 400 | `VALIDATION_FAILED` |
| API-10.7 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 11. `GET /api/v1/claims/{claimId}`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-11.1 | The owner reads the claim | claimant1 | 200 |  |
| API-11.2 | Unknown claim `CLM-2026-999999` | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-11.3 | Another claimant (same 404 as an unknown claim) | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-11.4 | A claim assigned to officer1, read by officer2 (outside their scope) | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-11.5 | The same claim read by its assigned officer | officer1 | 200 |  |
| API-11.6 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 12. `PATCH /api/v1/claims/{claimId}`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-12.1 | Correct the claimed amount while SUBMITTED | claimant1 | 200 |  |
| API-12.2 | Empty body `{}` | claimant1 | 400 | `NO_FIELDS_TO_UPDATE` |
| API-12.3 | Only `market` (not changeable, so no field to update) | claimant1 | 400 | `NO_FIELDS_TO_UPDATE` |
| API-12.4 | claimedAmount 0 | claimant1 | 400 | `VALIDATION_FAILED` |
| API-12.5 | Blank claimantName | claimant1 | 400 | `VALIDATION_FAILED` |
| API-12.6 | A multi-line description is accepted | claimant1 | 200 |  |
| API-12.7 | Another claimant | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-12.8 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-12.9 | The claim is no longer SUBMITTED (an officer has started) | claimant1 | 409 | `CLAIM_NOT_EDITABLE` |
| API-12.10 | A claims officer may not edit a claim | officer1 | 403 | `FORBIDDEN` |

#### 13. `GET /api/v1/claims/{claimId}/history`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-13.1 | The owner reads it | claimant1 | 200 |  |
| API-13.2 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-13.3 | Another claimant | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-13.4 | An officer outside the claim's scope | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-13.5 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 14. `GET /api/v1/claims/{claimId}/assignments`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-14.1 | The owner reads it | claimant1 | 200 |  |
| API-14.2 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-14.3 | Another claimant | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-14.4 | An officer outside the claim's scope | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-14.5 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 15. `GET /api/v1/claims/{claimId}/updates`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-15.1 | The owner reads it | claimant1 | 200 |  |
| API-15.2 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-15.3 | Another claimant | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-15.4 | An officer outside the claim's scope | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-15.5 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 16. `GET /api/v1/officer/claims`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-16.1 | The unassigned queue | officer1 | 200 |  |
| API-16.2 | All claims of one officer | manager1 | 200 |  |
| API-16.3 | `assigned=false` together with `assignedOfficerId` | manager1 | 400 | `CONFLICTING_FILTERS` |
| API-16.4 | `claimType=BOAT` | manager1 | 400 | `INVALID_PARAMETER` |
| API-16.5 | `size=101` | officer1 | 400 | `VALIDATION_FAILED` |
| API-16.6 | A claimant may not use the staff list | claimant1 | 403 | `FORBIDDEN` |

#### 17. `POST /api/v1/officer/claims/{claimId}/assign`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-17.1 | A manager assigns to an active claims officer | manager1 | 200 |  |
| API-17.2 | An officer takes an unassigned claim for themselves | officer1 | 200 |  |
| API-17.3 | An officer names another officer | officer1 | 403 | `ASSIGNMENT_NOT_PERMITTED` |
| API-17.4 | A manager names a claimant | manager1 | 400 | `INVALID_OFFICER` |
| API-17.5 | A manager names a user that does not exist | manager1 | 400 | `INVALID_OFFICER` |
| API-17.6 | A manager names a deactivated officer | manager1 | 400 | `INVALID_OFFICER` |
| API-17.7 | `officerId` missing | manager1 | 400 | `VALIDATION_FAILED` |
| API-17.8 | `officerId` is the Swagger placeholder `string` | manager1 | 400 | `VALIDATION_FAILED` |
| API-17.9 | Unknown claim | manager1 | 404 | `CLAIM_NOT_FOUND` |
| API-17.10 | A claim assigned to officer1, taken by officer2 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-17.11 | A settled claim cannot be assigned | manager1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-17.12 | A manager reassigns an open claim to a different officer | manager1 | 200 |  |
| API-17.13 | A claimant may not assign | claimant1 | 403 | `FORBIDDEN` |

#### 18. `GET /api/v1/management/summary`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-18.1 | The summary | manager1 | 200 |  |
| API-18.2 | An officer may not read it | officer1 | 403 | `FORBIDDEN` |
| API-18.3 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 19. `GET /api/v1/management/officers`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-19.1 | Per-officer metrics | manager1 | 200 |  |
| API-19.2 | A claimant may not read them | claimant1 | 403 | `FORBIDDEN` |
| API-19.3 | No token | no token | 401 | `UNAUTHENTICATED` |

#### 20. `POST /api/v1/officer/claims/{claimId}/review`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-20.1 | An officer starts the review (unassigned claim, so it is assigned to them) | officer1 | 200 |  |
| API-20.2 | Review again while UNDER_REVIEW | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-20.3 | An officer names another officer | officer1 | 403 | `OFFICER_IDENTITY_MISMATCH` |
| API-20.4 | An administrator names no officer | admin1 | 400 | `VALIDATION_FAILED` |
| API-20.5 | An administrator names a claimant as the officer | admin1 | 400 | `INVALID_OFFICER` |
| API-20.6 | assessmentNotes of 2001 characters | officer1 | 400 | `VALIDATION_FAILED` |
| API-20.7 | `officerId` is the placeholder `string` | officer1 | 400 | `VALIDATION_FAILED` |
| API-20.8 | Unknown claim | officer1 | 404 | `CLAIM_NOT_FOUND` |
| API-20.9 | A claim assigned to officer1, reviewed by officer2 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-20.10 | An administrator acts as officer2 on a claim held by officer1 | admin1 | 409 | `OFFICER_MISMATCH` |
| API-20.11 | Review while a request is still OPEN (MORE_INFO_REQUIRED) | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-20.12 | A manager may not review | manager1 | 403 | `FORBIDDEN` |

#### 21. `GET /api/v1/officer/claims/{claimId}/assessments`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-21.1 | The assessment history | officer1 | 200 |  |
| API-21.2 | Unknown claim | officer1 | 404 | `CLAIM_NOT_FOUND` |
| API-21.3 | A claim assigned to officer1, read by officer2 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-21.4 | A manager may not read it | manager1 | 403 | `FORBIDDEN` |
| API-21.5 | A claimant may not read it | claimant1 | 403 | `FORBIDDEN` |

#### 22. `POST /api/v1/officer/claims/{claimId}/decision`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-22.1 | Approve for less than the claimed amount | officer1 | 200 |  |
| API-22.2 | Approve for exactly the claimed amount | officer1 | 200 |  |
| API-22.3 | Reject (no approvedAmount) | officer1 | 200 |  |
| API-22.4 | A second decision (the first is kept) | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-22.5 | Approve without an amount | officer1 | 400 | `INVALID_APPROVED_AMOUNT` |
| API-22.6 | Approve with amount 0 | officer1 | 400 | `INVALID_APPROVED_AMOUNT` |
| API-22.7 | Approve for more than the claimed amount | officer1 | 400 | `INVALID_APPROVED_AMOUNT` |
| API-22.8 | Reject with an amount | officer1 | 400 | `INVALID_APPROVED_AMOUNT` |
| API-22.9 | Decision currency differs from the claim currency | officer1 | 400 | `CURRENCY_MISMATCH` |
| API-22.10 | decisionReason missing | officer1 | 400 | `VALIDATION_FAILED` |
| API-22.11 | decision `MAYBE` | officer1 | 400 | `MALFORMED_REQUEST` |
| API-22.12 | currency in lower case | officer1 | 400 | `VALIDATION_FAILED` |
| API-22.13 | approvedAmount with 3 decimals | officer1 | 400 | `VALIDATION_FAILED` |
| API-22.14 | Decide a claim that is still SUBMITTED | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-22.15 | Unknown claim | officer1 | 404 | `CLAIM_NOT_FOUND` |
| API-22.16 | officer2 decides a claim held by officer1 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-22.17 | An administrator acts as officer2 on a claim held by officer1 | admin1 | 409 | `OFFICER_MISMATCH` |
| API-22.18 | An officer names another officer in the body | officer1 | 403 | `OFFICER_IDENTITY_MISMATCH` |
| API-22.19 | An administrator names no officer | admin1 | 400 | `VALIDATION_FAILED` |
| API-22.20 | A manager may not decide | manager1 | 403 | `FORBIDDEN` |

#### 23. `POST /api/v1/officer/claims/{claimId}/settlement`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-23.1 | Settle an approved claim | officer1 | 200 |  |
| API-23.2 | Settle without a note (the history records `Claim settled`) | officer1 | 200 |  |
| API-23.3 | Settle twice | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-23.4 | Settle a rejected claim | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-23.5 | Settle a claim that is only UNDER_REVIEW | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-23.6 | settlementNote of 2001 characters | officer1 | 400 | `VALIDATION_FAILED` |
| API-23.7 | Unknown claim | officer1 | 404 | `CLAIM_NOT_FOUND` |
| API-23.8 | officer2 settles a claim held by officer1 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-23.9 | An officer names another officer | officer1 | 403 | `OFFICER_IDENTITY_MISMATCH` |
| API-23.10 | A manager may not settle | manager1 | 403 | `FORBIDDEN` |

#### 24. `POST /api/v1/officer/claims/{claimId}/information-requests`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-24.1 | An officer asks for information | officer1 | 201 |  |
| API-24.2 | A second request while the claim is MORE_INFO_REQUIRED | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-24.3 | question blank | officer1 | 400 | `VALIDATION_FAILED` |
| API-24.4 | question missing | officer1 | 400 | `VALIDATION_FAILED` |
| API-24.5 | question of 2001 characters | officer1 | 400 | `VALIDATION_FAILED` |
| API-24.6 | The claim is still SUBMITTED | officer1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-24.7 | Unknown claim | officer1 | 404 | `CLAIM_NOT_FOUND` |
| API-24.8 | officer2 asks on a claim held by officer1 | officer2 | 404 | `CLAIM_NOT_FOUND` |
| API-24.9 | An officer names another officer | officer1 | 403 | `OFFICER_IDENTITY_MISMATCH` |
| API-24.10 | An administrator names no officer | admin1 | 400 | `VALIDATION_FAILED` |
| API-24.11 | A claimant may not ask | claimant1 | 403 | `FORBIDDEN` |

#### 25. `GET /api/v1/claims/{claimId}/information-requests`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-25.1 | A claim with an OPEN request, read by the claimant | claimant1 | 200 |  |
| API-25.2 | A claim with no requests (empty list) | claimant1 | 200 |  |
| API-25.3 | The assigned officer reads it | officer1 | 200 |  |
| API-25.4 | A manager reads it | manager1 | 200 |  |
| API-25.5 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-25.6 | Another claimant | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-25.7 | officer2 reads a claim held by officer1 | officer2 | 404 | `CLAIM_NOT_FOUND` |

#### 26. `POST /api/v1/claims/{claimId}/information`

| ID | Scenario | Caller | Expected status | Expected `code` |
|---|---|---|---|---|
| API-26.1 | The claimant answers (last open request, so the claim resumes) | claimant1 | 200 |  |
| API-26.2 | Answer again after the claim has resumed (the claim is no longer MORE_INFO_REQUIRED) | claimant1 | 409 | `INVALID_CLAIM_TRANSITION` |
| API-26.3 | Unknown request ID | claimant1 | 404 | `INFORMATION_REQUEST_NOT_FOUND` |
| API-26.4 | A request ID that belongs to another claim | claimant1 | 404 | `INFORMATION_REQUEST_NOT_FOUND` |
| API-26.5 | answer blank | claimant1 | 400 | `VALIDATION_FAILED` |
| API-26.6 | informationRequestId missing | claimant1 | 400 | `VALIDATION_FAILED` |
| API-26.7 | answer of 4001 characters | claimant1 | 400 | `VALIDATION_FAILED` |
| API-26.8 | Unknown claim | claimant1 | 404 | `CLAIM_NOT_FOUND` |
| API-26.9 | Another claimant tries to answer | claimant2 | 404 | `CLAIM_NOT_FOUND` |
| API-26.10 | A claims officer may not answer for the claimant | officer1 | 403 | `FORBIDDEN` |
| API-26.11 | The request is still OPEN after those refusals | claimant1 | 200 |  |

### 17.2 When a service this one depends on is down (503 and 502)

Run live by stopping the scratch User Service and then the scratch Claim Service (only processes started for the test). The dependency graph is Assessment calls Claim, Claim calls User, so the cases below are the ones that cross a service boundary.

| ID | Scenario | Caller | Observed status | Observed `code` |
|---|---|---|---|---|
| OUT-1.1 | User Service down: a manager assigns a claim (the officer cannot be checked) | manager1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-1.2 | User Service down: an administrator assigns | admin1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-1.3 | User Service down: an administrator reviews an unassigned claim as `officer1` through the **Assessment Service** (an assignment, so the Claim Service checks the officer) | admin1 | **502** | `DOWNSTREAM_ERROR` |
| OUT-1.4 | User Service down: an officer takes a claim for themselves (no lookup is needed) | officer1 | 200 | |
| OUT-1.5 | User Service down: an officer reviews an unassigned claim as themselves | officer1 | 200 | |
| OUT-1.6 | User Service down: the management summary (tokens issued earlier are still accepted, they are checked locally) | manager1 | 200 | |
| OUT-1.7 | User Service down: login | nobody | no HTTP answer, the connection is refused | |
| OUT-2.1 | Claim Service down: review | officer1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.2 | Claim Service down: assessment history | officer1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.3 | Claim Service down: decision | officer1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.4 | Claim Service down: settlement | officer1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.5 | Claim Service down: information request | officer1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.6 | Claim Service down: list information requests | claimant1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.7 | Claim Service down: answer an information request | claimant1 | 503 | `DOWNSTREAM_UNAVAILABLE` |
| OUT-2.8 | Claim Service down: the Assessment Service's own health (it checks only its own database) | nobody | 200 | |
| OUT-2.9 | Claim Service down: any Claim Service route | claimant1 | no HTTP answer, the connection is refused | |

**OUT-1.3 is a 502, not a 503, and that is the design, not a fault of the test.** The Assessment Service re-raises only the Claim Service's 400, 404 and 409 with the same status; any other answer, a 503 included, becomes `502 DOWNSTREAM_ERROR` ("Claim Service answered with HTTP 503 which this service cannot handle"). Here the Claim Service answered 503 because it could not reach the User Service, so a failure two hops away shows as 502 at the edge. OUT-1.1 and OUT-1.2 go straight to the Claim Service and show the 503 itself. The services never retry a call that changes state.

For OUT-2.1 to OUT-2.7, reading the code shows each request fails on its first step (asking the Claim Service where the claim is) before anything is written; the databases were not inspected in this run.

**D1. a manager assigns a claim while the User Service is down**

Claim Service, as manager1: `POST /api/v1/officer/claims/CLM-2026-000210/assign`

Request body:

```json
{
  "officerId": "officer1"
}
```

Response `503`:

```json
{
  "timestamp": "2026-10-27T04:27:57.317162700Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "DOWNSTREAM_UNAVAILABLE",
  "message": "User Service is unavailable or did not answer in time",
  "path": "/api/v1/officer/claims/CLM-2026-000210/assign"
}
```

**D2. an officer starts a review while the Claim Service is down**

Assessment Service, as officer1: `POST /api/v1/officer/claims/CLM-2026-000210/review`

Request body:

```json
{
  "assessmentNotes": "will not be saved"
}
```

Response `503`:

```json
{
  "timestamp": "2026-10-27T04:28:12.836002500Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "DOWNSTREAM_UNAVAILABLE",
  "message": "Claim Service is unavailable or did not answer in time",
  "path": "/api/v1/officer/claims/CLM-2026-000210/review"
}
```

## 18. Swagger test order

Open the Swagger UI of the service that owns each step (`http://localhost:8185/swagger-ui/index.html` for the User Service, `8181` for the Claim Service, `8182` for the Assessment Service). To act as a person, call `POST /api/v1/auth/login` on the User Service, copy the `accessToken` **without** the word `Bearer`, choose **Authorize** (the padlock) on the service you are about to use, paste it and confirm. Each Swagger UI keeps its own token, and **Authorize must be repeated whenever the acting person changes** (a pasted token survives a page reload, `persist-authorization`). The requests and responses of every step are in sections 14, 15 and below.

| Step | Swagger UI | Act as | Operation | Input | Expected | Real evidence |
|---|---|---|---|---|---|---|
| 1 | User | nobody, then `admin1` | `POST /api/v1/auth/login` | `{"username":"admin1","password":"password123"}`; copy `accessToken`, Authorize on the User Service | 200, `role` `ADMIN` | section 14.3, L1 |
| 2 | User | `admin1` | `POST /api/v1/users` four times | `officer2` CLAIMS_OFFICER, `manager2` MANAGER, `claimant2` CLAIMANT (and `admin2` ADMIN) | 201 each | section 14.2 |
| 3 | Claim | `claimant1` (log in, Authorize on the Claim Service) | `POST /api/v1/claims` | the body of F1-01; copy `claimId` | 201, `SUBMITTED` | F1-01 |
| 4 | Claim | `manager1` | `POST /api/v1/officer/claims/{claimId}/assign` | `{"officerId":"officer1"}` | 200, still `SUBMITTED` | F1-07 |
| 5 | Assessment | `officer1` | `POST /api/v1/officer/claims/{claimId}/review` | `{"assessmentNotes":"..."}` | 200, `UNDER_REVIEW` | F1-09 |
| 6 | Assessment | `officer1` | `POST /api/v1/officer/claims/{claimId}/information-requests` | `{"question":"..."}`; copy `informationRequestId` | 201, `OPEN`; the claim is `MORE_INFO_REQUIRED` | F1-10 |
| 7 | Assessment | `claimant1` | `POST /api/v1/claims/{claimId}/information` | `{"informationRequestId":"IR-...","answer":"..."}` | 200, `ANSWERED`; the claim is `UNDER_REVIEW` again by itself | F1-12 |
| 8 | Assessment | `officer1` | `POST /api/v1/officer/claims/{claimId}/decision` | `APPROVED`, `approvedAmount` not above the claimed amount, the claim's currency | 200, `APPROVED` | F1-13 |
| 9 | Assessment | `officer1` | `POST /api/v1/officer/claims/{claimId}/settlement` | `{"settlementNote":"..."}` | 200, `SETTLED` | F1-15 |
| 10 | Claim, Assessment | `claimant1`, `officer1` | verify histories: `GET /claims/{claimId}/history`, `/assignments`, `/updates` (Claim); `GET /officer/claims/{claimId}/assessments`, `GET /claims/{claimId}/information-requests` (Assessment) | the claim ID | 200: six status rows, one assignment row (`actor` manager1), one edit, assessment entries `NOTE`, `APPROVED`, `SETTLED` | F1-05, F1-08, F1-11, F1-17, F1-18 |
| 11 | Claim | `manager1` | verify reports: `GET /api/v1/management/summary`, `GET /api/v1/management/officers` | none | 200; the settled claim has left the outstanding liability; `officer1` shows 1 approved and 1 settled | F1-14, F2-09, F2-10 |
| 12 | Claim | `claimant2` | `POST /api/v1/claims` (a second claim) | the body of F2-01 | 201 | F2-01 |
| 13 | Claim, Assessment | `manager2`, then `officer2` | assign to `officer2`, review, then `POST .../decision` with `REJECTED` and no amount; then try a settlement | see F2-02 to F2-05 | 200, 200, 200 `REJECTED`; the settlement is **409** | F2-02 to F2-05 |
| 14 | User, Claim | `admin1`, then `manager2` | `PUT /api/v1/users/officer2/active` `{"active":false}`; log in as `officer2`; assign a new claim to `officer2`; reactivate | see S14-1 to S14-6 | 200; login **401** `INVALID_CREDENTIALS`; assignment **400** `INVALID_OFFICER`; 200; deactivating yourself **409** | S14-1 to S14-6 |
| 15 | User, Claim | `admin1`, `claimant2` | `PUT /api/v1/users/claimant2/role` to `CLAIMS_OFFICER`; the old token; a new login; submit a claim; demote | see S15-1 to S15-7 | 200; the old token still acts as a claimant (no revocation); the new login has the new role and cannot submit a claim (**403**); changing your own role **409**; role `SERVICE` **400** | S15-1 to S15-7 |

Steps 14 and 15 are the only ones that change accounts; the results (real requests and responses):

**S14-1. deactivate officer2**

User Service, as admin1: `PUT /api/v1/users/officer2/active`

Request body:

```json
{
  "active": false
}
```

Response `200`:

```json
{
  "username": "officer2",
  "role": "CLAIMS_OFFICER",
  "active": false,
  "createdAt": "2026-10-27T04:21:35.135681Z"
}
```

**S14-2. a deactivated user cannot log in (same 401 as a wrong password)**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "officer2",
  "password": "password123"
}
```

Response `401`:

```json
{
  "timestamp": "2026-10-27T04:21:38.078292500Z",
  "status": 401,
  "error": "Unauthorized",
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid username or password",
  "path": "/api/v1/auth/login"
}
```

**S14-3. claimant1 submits a third claim, to try the assignment**

Claim Service, as claimant1: `POST /api/v1/claims`

Request body:

```json
{
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Windscreen cracked by a stone on the expressway",
  "incidentDate": "2026-10-22",
  "claimedAmount": 600.00,
  "currency": "INR"
}
```

Response `201`:

```json
{
  "id": 3,
  "claimId": "CLM-2026-000103",
  "claimantId": "claimant1",
  "claimantName": "Asha Rao",
  "claimantEmail": "asha.rao@example.com",
  "policyNumber": "POL-2026-0001",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "Windscreen cracked by a stone on the expressway",
  "incidentDate": "2026-10-22",
  "claimedAmount": 600.00,
  "approvedAmount": null,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "decidedAt": null,
  "settledAt": null,
  "createdAt": "2026-10-27T04:21:38.089333800Z",
  "updatedAt": "2026-10-27T04:21:38.089333800Z"
}
```

**S14-4. a deactivated officer cannot be assigned a claim**

Claim Service, as manager2: `POST /api/v1/officer/claims/CLM-2026-000103/assign`

Request body:

```json
{
  "officerId": "officer2"
}
```

Response `400`:

```json
{
  "timestamp": "2026-10-27T04:21:38.109634400Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_OFFICER",
  "message": "officer2 is not an active claims officer",
  "path": "/api/v1/officer/claims/CLM-2026-000103/assign"
}
```

**S14-5. reactivate officer2**

User Service, as admin1: `PUT /api/v1/users/officer2/active`

Request body:

```json
{
  "active": true
}
```

Response `200`:

```json
{
  "username": "officer2",
  "role": "CLAIMS_OFFICER",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.135681Z"
}
```

**S14-6. an administrator cannot deactivate themselves**

User Service, as admin1: `PUT /api/v1/users/admin1/active`

Request body:

```json
{
  "active": false
}
```

Response `409`:

```json
{
  "timestamp": "2026-10-27T04:21:38.131508500Z",
  "status": 409,
  "error": "Conflict",
  "code": "CANNOT_DEACTIVATE_SELF",
  "message": "You cannot deactivate your own account",
  "path": "/api/v1/users/admin1/active"
}
```

**S15-1. promote claimant2 to CLAIMS_OFFICER**

User Service, as admin1: `PUT /api/v1/users/claimant2/role`

Request body:

```json
{
  "role": "CLAIMS_OFFICER"
}
```

Response `200`:

```json
{
  "username": "claimant2",
  "role": "CLAIMS_OFFICER",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.266241Z"
}
```

**S15-2. the token claimant2 already holds keeps the old role until it expires (documented limitation, no revocation)**

Claim Service, as claimant2: `GET /api/v1/claims?page=0&size=1`

The old token still works as a `CLAIMANT`: the list contains only claimant2's own claim.

Response `200`:

```json
{
  "content": [
    {
      "id": 2,
      "claimId": "CLM-2026-000102",
      "claimantId": "claimant2",
      "claimantName": "Wei Lim",
      "policyNumber": "HK-PROP-0077",
      "market": "HK",
      "claimType": "PROPERTY",
      "claimedAmount": 18000.00,
      "approvedAmount": null,
      "currency": "HKD",
      "status": "REJECTED",
      "assignedOfficerId": "officer2",
      "createdAt": "2026-10-27T04:21:37.785655Z"
    }
  ],
  "page": 0,
  "size": 1,
  "totalElements": 1,
  "totalPages": 1
}
```

**S15-3. a new login carries the new role**

User Service, without a token: `POST /api/v1/auth/login`

Request body:

```json
{
  "username": "claimant2",
  "password": "password123"
}
```

Response `200`:

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "username": "claimant2",
  "role": "CLAIMS_OFFICER"
}
```

**S15-4. a claims officer cannot submit a claim**

Claim Service, as claimant2 (now CLAIMS_OFFICER): `POST /api/v1/claims`

Request body:

```json
{
  "claimantName": "x",
  "claimantEmail": "x@example.com",
  "policyNumber": "POL-1",
  "market": "SG",
  "claimType": "MOTOR",
  "incidentDescription": "x",
  "incidentDate": "2026-10-01",
  "claimedAmount": 1.00,
  "currency": "INR"
}
```

Response `403`:

```json
{
  "timestamp": "2026-10-27T04:21:38.241922200Z",
  "status": 403,
  "error": "Forbidden",
  "code": "FORBIDDEN",
  "message": "Your role is not allowed to use this route",
  "path": "/api/v1/claims"
}
```

**S15-5. demote claimant2 back to CLAIMANT**

User Service, as admin1: `PUT /api/v1/users/claimant2/role`

Request body:

```json
{
  "role": "CLAIMANT"
}
```

Response `200`:

```json
{
  "username": "claimant2",
  "role": "CLAIMANT",
  "active": true,
  "createdAt": "2026-10-27T04:21:35.266241Z"
}
```

**S15-6. an administrator cannot change their own role**

User Service, as admin1: `PUT /api/v1/users/admin1/role`

Request body:

```json
{
  "role": "MANAGER"
}
```

Response `409`:

```json
{
  "timestamp": "2026-10-27T04:21:38.266114Z",
  "status": 409,
  "error": "Conflict",
  "code": "CANNOT_CHANGE_OWN_ROLE",
  "message": "You cannot change your own role",
  "path": "/api/v1/users/admin1/role"
}
```

**S15-7. SERVICE is not a role a user can have**

User Service, as admin1: `PUT /api/v1/users/claimant2/role`

Request body:

```json
{
  "role": "SERVICE"
}
```

Response `400`:

```json
{
  "timestamp": "2026-10-27T04:21:38.273681100Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ROLE",
  "message": "SERVICE is not a role a user can have",
  "path": "/api/v1/users/claimant2/role"
}
```

## 19. Postman collection

### 19.1 Variables

| Variable | Default | Meaning |
|---|---|---|
| `{{user-service}}` | `http://localhost:8185` | base URL of the User Service |
| `{{claim-service}}` | `http://localhost:8181` | base URL of the Claim Service |
| `{{assessment-service}}` | `http://localhost:8182` | base URL of the Assessment Service |
| `{{token}}` | empty | the access token of the person currently acting. Every Login request sets it; every other request sends it as a Bearer token (collection-level authorisation) |

The collection also uses five helper variables, all collection variables: `claimId` (set by Create Claim), `informationRequestId` (set by Information Request), `officerId` (default `officer1`, used by Assign Claim), `username` (default `officer2`, used by Get User, Change Role, Deactivate and Activate) and `role` (default `CLAIMS_OFFICER`, used by Change Role). Change the three base URLs if the services run on other ports.

### 19.2 Folders and requests (38 requests)

| Folder | Request | Method and path | Act as (log in first) | Expected |
|---|---|---|---|---|
| Authentication | Login admin1, admin2, officer1, officer2, manager1, manager2, claimant1, claimant2 | `POST {{user-service}}/api/v1/auth/login` | none | 200, sets `{{token}}` |
| Authentication | Register | `POST {{user-service}}/api/v1/auth/register` | none | 201 (the name uses `{{$timestamp}}` so it is new each time) |
| Authentication | Me | `GET {{user-service}}/api/v1/users/me` | any person | 200 |
| User Management | Create User admin2, officer2, manager2, claimant2 | `POST {{user-service}}/api/v1/users` | admin1 | 201, or 409 if it already exists |
| User Management | List Users | `GET {{user-service}}/api/v1/users?page=0&size=20` | admin1 | 200 |
| User Management | Get User | `GET {{user-service}}/api/v1/users/{{username}}` | admin1 | 200 |
| User Management | Change Role | `PUT {{user-service}}/api/v1/users/{{username}}/role` | admin1 | 200 |
| User Management | Deactivate User, Activate User | `PUT {{user-service}}/api/v1/users/{{username}}/active` | admin1 | 200 |
| Claim Management | Create Claim | `POST {{claim-service}}/api/v1/claims` | a claimant | 201, sets `{{claimId}}` |
| Claim Management | List Claims | `GET {{claim-service}}/api/v1/claims?page=0&size=20` | claimant, officer or manager | 200 |
| Claim Management | Get Claim | `GET {{claim-service}}/api/v1/claims/{{claimId}}` | claimant (own), officer, manager | 200 |
| Claim Management | Patch Claim | `PATCH {{claim-service}}/api/v1/claims/{{claimId}}` | the claimant, while `SUBMITTED` | 200 |
| Claim Management | Claim History, Assignment History, Update History | `GET {{claim-service}}/api/v1/claims/{{claimId}}/history`, `/assignments`, `/updates` | claimant (own), officer, manager | 200 |
| Claim Management | Officer Claims | `GET {{claim-service}}/api/v1/officer/claims?assigned=false&page=0&size=20` | officer or manager | 200 |
| Claim Management | Assign Claim | `POST {{claim-service}}/api/v1/officer/claims/{{claimId}}/assign` | manager (any active officer) or officer (self) | 200 |
| Assessment Management | Review | `POST {{assessment-service}}/api/v1/officer/claims/{{claimId}}/review` | the claims officer | 200 |
| Assessment Management | Assessment History | `GET {{assessment-service}}/api/v1/officer/claims/{{claimId}}/assessments` | the claims officer | 200 |
| Assessment Management | Information Request | `POST {{assessment-service}}/api/v1/officer/claims/{{claimId}}/information-requests` | the claims officer | 201, sets `{{informationRequestId}}` |
| Assessment Management | Information Request List | `GET {{assessment-service}}/api/v1/claims/{{claimId}}/information-requests` | claimant, officer, manager | 200 |
| Assessment Management | Information Answer | `POST {{assessment-service}}/api/v1/claims/{{claimId}}/information` | the claimant | 200 |
| Assessment Management | Decision Approve, Decision Reject | `POST {{assessment-service}}/api/v1/officer/claims/{{claimId}}/decision` | the claims officer | 200 |
| Assessment Management | Settlement | `POST {{assessment-service}}/api/v1/officer/claims/{{claimId}}/settlement` | the claims officer | 200 |
| Reports | Management Summary, Officer Metrics | `GET {{claim-service}}/api/v1/management/summary`, `/officers` | manager | 200 |

### 19.3 Run order

Because one `{{token}}` holds one person, **run a Login request each time the acting person changes**. Flow 1 (section 15.1): Login claimant1, Create Claim, Get Claim, List Claims, Patch Claim, Update History, Login officer1, Officer Claims, Login manager1 (`officerId` = `officer1`), Assign Claim, Assignment History, Login officer1, Review, Information Request, Login claimant1, Information Request List, Information Answer, Login officer1, Decision Approve, Settlement, Assessment History, Login claimant1, Claim History, Login manager1, Management Summary, Officer Metrics. Flow 2 (section 15.2): Login claimant2, Create Claim, Login manager2 (`officerId` = `officer2`), Assign Claim, Login officer2, Review, Decision Reject, Settlement, Login claimant2, Claim History. In Flow 2 the **Settlement request must show a failed built-in test**: its test expects 200, and the 409 it receives is the correct, expected refusal of settling a rejected claim. User management: Login admin1, the four Create User requests, Register, Me, List Users, Get User (`username` = `officer2`), Change Role (`username` = `claimant2`, `role` = `CLAIMANT`), Deactivate User and Activate User (`username` = `officer2`).

### 19.4 The collection (Postman v2.1)

Save the block as `chubb-claims.postman_collection.json` and import it (**Import**, then the file). Each request carries a test script that checks the status and, where needed, stores a variable.

```json
{
  "info": { "name": "Chubb APAC Claims Backend", "description": "26 public endpoints of the User, Claim and Assessment services. Log in as the right user first: the Login requests set {{token}}.", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json" },
  "auth": { "type": "bearer", "bearer": [ { "key": "token", "value": "{{token}}", "type": "string" } ] },
  "variable": [
    { "key": "user-service", "value": "http://localhost:8185" },
    { "key": "claim-service", "value": "http://localhost:8181" },
    { "key": "assessment-service", "value": "http://localhost:8182" },
    { "key": "token", "value": "" },
    { "key": "claimId", "value": "" },
    { "key": "informationRequestId", "value": "" },
    { "key": "officerId", "value": "officer1" },
    { "key": "username", "value": "officer2" },
    { "key": "role", "value": "CLAIMS_OFFICER" }
  ],
  "item": [
    { "name": "Authentication", "item": [
      { "name": "Login admin1",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"admin1\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login admin2",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"admin2\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login officer1",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"officer1\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login officer2",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"officer2\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login manager1",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"manager1\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login manager2",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"manager2\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login claimant1",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"claimant1\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Login claimant2",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"claimant2\",\"password\":\"password123\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/login"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });","pm.collectionVariables.set(\"token\", pm.response.json().accessToken);"]}}] },
      { "name": "Register",
        "request": {"method":"POST","auth":{"type":"noauth"},"header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"user.{{$timestamp}}\",\"password\":\"correct-horse-battery\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/auth/register"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 201\", function () { pm.response.to.have.status(201); });"]}}] },
      { "name": "Me",
        "request": {"method":"GET","url":"{{user-service}}/api/v1/users/me"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] }
    ] },
    { "name": "User Management", "item": [
      { "name": "Create User admin2",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"admin2\",\"password\":\"password123\",\"role\":\"ADMIN\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is one of 201 or 409\", function () { pm.expect(pm.response.code).to.be.oneOf([201, 409]); });"]}}] },
      { "name": "Create User officer2",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"officer2\",\"password\":\"password123\",\"role\":\"CLAIMS_OFFICER\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is one of 201 or 409\", function () { pm.expect(pm.response.code).to.be.oneOf([201, 409]); });"]}}] },
      { "name": "Create User manager2",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"manager2\",\"password\":\"password123\",\"role\":\"MANAGER\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is one of 201 or 409\", function () { pm.expect(pm.response.code).to.be.oneOf([201, 409]); });"]}}] },
      { "name": "Create User claimant2",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"username\":\"claimant2\",\"password\":\"password123\",\"role\":\"CLAIMANT\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is one of 201 or 409\", function () { pm.expect(pm.response.code).to.be.oneOf([201, 409]); });"]}}] },
      { "name": "List Users",
        "request": {"method":"GET","url":"{{user-service}}/api/v1/users?page=0&size=20"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Get User",
        "request": {"method":"GET","url":"{{user-service}}/api/v1/users/{{username}}"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Change Role",
        "request": {"method":"PUT","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"role\":\"{{role}}\"}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users/{{username}}/role"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Deactivate User",
        "request": {"method":"PUT","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"active\":false}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users/{{username}}/active"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Activate User",
        "request": {"method":"PUT","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"active\":true}","options":{"raw":{"language":"json"}}},"url":"{{user-service}}/api/v1/users/{{username}}/active"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] }
    ] },
    { "name": "Claim Management", "item": [
      { "name": "Create Claim",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"claimantName\":\"Asha Rao\",\"claimantEmail\":\"asha.rao@example.com\",\"policyNumber\":\"POL-2026-0001\",\"market\":\"SG\",\"claimType\":\"MOTOR\",\"incidentDescription\":\"Rear-ended at a junction on Orchard Road\",\"incidentDate\":\"2026-10-20\",\"claimedAmount\":2400.00,\"currency\":\"INR\"}","options":{"raw":{"language":"json"}}},"url":"{{claim-service}}/api/v1/claims"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 201\", function () { pm.response.to.have.status(201); });","pm.collectionVariables.set(\"claimId\", pm.response.json().claimId);"]}}] },
      { "name": "List Claims",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/claims?page=0&size=20"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Get Claim",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/claims/{{claimId}}"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Patch Claim",
        "request": {"method":"PATCH","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"claimedAmount\":2500.00}","options":{"raw":{"language":"json"}}},"url":"{{claim-service}}/api/v1/claims/{{claimId}}"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Claim History",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/claims/{{claimId}}/history"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Assignment History",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/claims/{{claimId}}/assignments"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Update History",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/claims/{{claimId}}/updates"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Officer Claims",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/officer/claims?assigned=false&page=0&size=20"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Assign Claim",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"officerId\":\"{{officerId}}\"}","options":{"raw":{"language":"json"}}},"url":"{{claim-service}}/api/v1/officer/claims/{{claimId}}/assign"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] }
    ] },
    { "name": "Assessment Management", "item": [
      { "name": "Review",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"assessmentNotes\":\"Photos match the description.\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/review"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Assessment History",
        "request": {"method":"GET","url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/assessments"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Information Request",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"question\":\"Please send the repair estimate.\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/information-requests"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 201\", function () { pm.response.to.have.status(201); });","pm.collectionVariables.set(\"informationRequestId\", pm.response.json().informationRequestId);"]}}] },
      { "name": "Information Request List",
        "request": {"method":"GET","url":"{{assessment-service}}/api/v1/claims/{{claimId}}/information-requests"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Information Answer",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"informationRequestId\":\"{{informationRequestId}}\",\"answer\":\"The workshop estimate is INR 2,200.\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/claims/{{claimId}}/information"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Decision Approve",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"decision\":\"APPROVED\",\"decisionReason\":\"Covered under the policy.\",\"approvedAmount\":2200.00,\"currency\":\"INR\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/decision"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Decision Reject",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"decision\":\"REJECTED\",\"decisionReason\":\"Excluded by the policy.\",\"currency\":\"INR\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/decision"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Settlement",
        "request": {"method":"POST","header":[{"key":"Content-Type","value":"application/json"}],"body":{"mode":"raw","raw":"{\"settlementNote\":\"Paid by bank transfer.\"}","options":{"raw":{"language":"json"}}},"url":"{{assessment-service}}/api/v1/officer/claims/{{claimId}}/settlement"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] }
    ] },
    { "name": "Reports", "item": [
      { "name": "Management Summary",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/management/summary"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] },
      { "name": "Officer Metrics",
        "request": {"method":"GET","url":"{{claim-service}}/api/v1/management/officers"},
        "event": [{"listen":"test","script":{"type":"text/javascript","exec":["pm.test(\"status is 200\", function () { pm.response.to.have.status(200); });"]}}] }
    ] }
  ]
}
```

### 19.5 What was and was not verified

* The JSON was parsed successfully (valid JSON, 5 folders, 38 requests).
* The **request definitions in it** (method, URL with variables, body, expected status, variable capture) were run through a small emulator that substitutes the variables and follows the run order of 19.3, against freshly started services: **48 requests, 0 failures** (an earlier attempt failed 14 requests because of a defect in the emulator, which sent an empty body with each GET; it was fixed and the run repeated). 37 of the 38 requests were executed that way; `Login admin2` was exercised separately (it returned `ADMIN`).
* **Not verified:** the collection was **not imported into Postman**, and the JavaScript test scripts were **not executed by Postman**. They are one-line `pm.test(...)` and `pm.collectionVariables.set(...)` calls, but that is reading, not a result.

## 20. Final coverage

**Total public APIs: 26 = User Service 8 + Claim Service 11 + Assessment Service 7** (the `/internal/v1` routes, 6 on the Claim Service and 1 on the User Service, are service-to-service, are listed in section 3a and are not counted). All 26 have a role-matrix row, positive and negative scenarios, a place in the flows or the Swagger order, and a Postman request.

| # (matrix) | Endpoint | Earlier numbering (sections 4 to 12) | Scenarios (positive + negative) | In the flows and steps | Postman request |
|---|---|---|---|---|---|
| 1 | `POST /api/v1/auth/register` | U1 | 11 (2 + 9) | UM1 | Register |
| 2 | `POST /api/v1/auth/login` | U2 | 9 (3 + 6) | L1 to L8 | Login (eight) |
| 3 | `GET /api/v1/users/me` | U3 | 4 (2 + 2) | UM2 | Me |
| 4 | `POST /api/v1/users` | U4 | 7 (1 + 6) | C-admin2 to C-claimant2 | Create User (four) |
| 5 | `GET /api/v1/users` | U5 | 6 (1 + 5) | UM3 | List Users |
| 6 | `GET /api/v1/users/{username}` | U6 | 3 (1 + 2) | UM4 | Get User |
| 7 | `PUT /api/v1/users/{username}/role` | U7 | 7 (1 + 6) | S15-1, S15-5 to S15-7 | Change Role |
| 8 | `PUT /api/v1/users/{username}/active` | U8 | 7 (2 + 5) | S14-1, S14-5, S14-6 | Deactivate User, Activate User |
| 9 | `POST /api/v1/claims` | 1 | 17 (2 + 15) | F1-01, F2-01, S14-3 | Create Claim |
| 10 | `GET /api/v1/claims` | 15 | 7 (2 + 5) | F1-03, S15-2 | List Claims |
| 11 | `GET /api/v1/claims/{claimId}` | 2 | 6 (2 + 4) | F1-02, F1-16, F2-06 | Get Claim |
| 12 | `PATCH /api/v1/claims/{claimId}` | 16 | 10 (2 + 8) | F1-04 | Patch Claim |
| 13 | `GET /api/v1/claims/{claimId}/history` | 12 | 5 (1 + 4) | F1-17, F2-07 | Claim History |
| 14 | `GET /api/v1/claims/{claimId}/assignments` | 13 | 5 (1 + 4) | F1-08 | Assignment History |
| 15 | `GET /api/v1/claims/{claimId}/updates` | 17 | 5 (1 + 4) | F1-05 | Update History |
| 16 | `GET /api/v1/officer/claims` | 3 | 6 (2 + 4) | F1-06 | Officer Claims |
| 17 | `POST /api/v1/officer/claims/{claimId}/assign` | 4 | 13 (3 + 10) | F1-07, F2-02, S14-4 | Assign Claim |
| 18 | `GET /api/v1/management/summary` | 10 | 3 (1 + 2) | F1-14, F2-09 | Management Summary |
| 19 | `GET /api/v1/management/officers` | 11 | 3 (1 + 2) | F2-10 | Officer Metrics |
| 20 | `POST /api/v1/officer/claims/{claimId}/review` | 5 | 12 (1 + 11) | F1-09, F2-03 | Review |
| 21 | `GET /api/v1/officer/claims/{claimId}/assessments` | 14 | 5 (1 + 4) | F1-18, F2-08 | Assessment History |
| 22 | `POST /api/v1/officer/claims/{claimId}/decision` | 9 | 20 (3 + 17) | F1-13 (approve), F2-04 (reject) | Decision Approve, Decision Reject |
| 23 | `POST /api/v1/officer/claims/{claimId}/settlement` | 18 | 10 (2 + 8) | F1-15, F2-05 | Settlement |
| 24 | `POST /api/v1/officer/claims/{claimId}/information-requests` | 6 | 11 (1 + 10) | F1-10 | Information Request |
| 25 | `GET /api/v1/claims/{claimId}/information-requests` | 7 | 7 (4 + 3) | F1-11 | Information Request List |
| 26 | `POST /api/v1/claims/{claimId}/information` | 8 | 11 (2 + 9) | F1-12 | Information Answer |

Scenarios total 210 (45 positive, 165 negative), plus the 16 downstream-failure cases of section 17.2 (503 and 502 included).

### 20.1 Results of this update (2026-10-27)

All of it was run in a scratch copy of the sources at commit `ee90578` (the later commit `b33e4a3`, which hard-codes the PostgreSQL datasource, was not tested), against jars built from it, on spare ports (28185, 28181, 28182), with in-memory H2 and a secret generated by `start-all.ps1`. Nothing was run on PostgreSQL.

| Check | Result |
|---|---|
| Two flows (sections 15.1, 15.2), steps 14 and 15 (section 18) | every request returned the status the document states (0 mismatches) |
| Role matrix, 26 endpoints by 5 identities (section 16) | 130 of 130 cells matched the hand-written expectation |
| Scenario catalogue (section 17.1) | 210 of 210 matched, status and `code` |
| Downstream failures (section 17.2) | 16 cases; 15 matched the expectation written beforehand, one (OUT-1.3) turned out to be 502 instead of 503, which is the documented design and is recorded as observed |
| Postman collection (section 19) | JSON valid; definitions run through an emulator, 48 requests, 0 failures; **not imported into Postman** |
| Existing automated suites (`clean test`, microservices) | **578 of 580 passed, 2 failed** (see the update in section 12); the monolith 142 of 142 |

### 20.2 Not covered, or not verified

* **PostgreSQL**: everything in section 13 is unverified; the migrations Claim V5, User V2 and Assessment V2, claim creation, row locking and the concurrency tests have never run there.
* **Postman and the Swagger UI by hand** (the Authorize button): not exercised.
* `INVALID_MARKET` (needs `claims.markets.allowed`), `INFORMATION_REQUEST_ALREADY_ANSWERED` (not reachable through the API, see section 17.1), a forced `500 INTERNAL_ERROR`, the `DOWN` health answer, an expired token against running jars, and the default ports (8181, 8182, 8185) live: not exercised in this update.
* The two failing automated tests and the fallback value for `JWT_SECRET` behind them are described in the update at the top of "Verified results" in section 12; they are not changed by this document.
