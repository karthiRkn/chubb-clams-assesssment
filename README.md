# Chubb APAC Claims Management Backend

A Spring Boot REST backend for motor and property insurance claims across APAC markets, built as three business microservices: **User** (the authentication service: login, JWT, users and roles), **Claim** and **Assessment**. Every API call needs a signed token and each route is limited to the roles that may use it (section 2, "Authentication and roles"). There is no API gateway (section 3).

## 1. Overview and business problem

Claims handling involves three groups with different needs:

- **Claimants** submit a claim, correct it while it is new, track it with a reference number, and answer questions about it.
- **Claims officers** pick up submitted claims, review them, ask the claimant for more information, and approve (with an amount) or reject; an approved claim is then settled.
- **Managers** assign claims to officers and see how many claims are outstanding, the money involved per currency, and each officer's workload.

This backend gives each group an API for that, enforces the claim lifecycle and who may see or touch which claim on the server, and keeps a record in a database.

**Scope.** It is a time-boxed assessment project. It was first written as a single modular monolith, then split into four microservices, then a User Service was added for authentication (five services). **Phase 1 reduced it to three services:** the Information Service was merged into the Assessment Service and the Reporting Service into the Claim Service, the roles, statuses and claim fields were changed to the target model, and secrets and database credentials moved out of the configuration files into environment variables. The "what changed" table is in section 3 and the upgrade notes for an existing database are in section 8. The microservices live in [`microservices/`](microservices/). **The original monolith is still in the repository root (`src/`, `pom.xml`), untouched, and still builds** (section 12); it is not part of the microservices build and has no authentication. The project is **not** production-ready (section 14).

## 2. Implemented features

Every endpoint below is implemented and has automated tests. "Roles" is who may call it; **ADMIN may call every public route**. A claimant is further limited to their own claims and a claims officer to the unassigned queue and their own claims (see "Who sees which claim"); a claim outside that answers exactly like an unknown claim (404).

### Claim Service (default port 8181)

| Method and path | Purpose | Roles |
|---|---|---|
| `POST /api/v1/claims` | Submit a claim; the server generates `claimId`, the claimant is the logged-in user | CLAIMANT |
| `GET /api/v1/claims` | List claims, newest first, with filters and paging (`status`, `market`, `claimType`, `assignedOfficerId`, `assigned`, `page`, `size`); a claimant gets their own, an officer the queue and their own, a manager all | CLAIMANT, CLAIMS_OFFICER, MANAGER |
| `GET /api/v1/claims/{claimId}` | Track one claim | CLAIMANT (own), CLAIMS_OFFICER (visible), MANAGER |
| `PATCH /api/v1/claims/{claimId}` | Correct your own claim while it is `SUBMITTED` (send only the fields that change) | CLAIMANT (own) |
| `GET /api/v1/claims/{claimId}/history` | Status history: from, to, when, who | CLAIMANT (own), CLAIMS_OFFICER (visible), MANAGER |
| `GET /api/v1/claims/{claimId}/assignments` | Assignment history: previous officer, new officer, when, actor | CLAIMANT (own), CLAIMS_OFFICER (visible), MANAGER |
| `GET /api/v1/claims/{claimId}/updates` | The claimant's edits to the claim: when, who, which fields | CLAIMANT (own), CLAIMS_OFFICER (visible), MANAGER |
| `GET /api/v1/officer/claims` | Staff view of the same list as `GET /api/v1/claims` | CLAIMS_OFFICER, MANAGER |
| `POST /api/v1/officer/claims/{claimId}/assign` | Assign an officer (rules below) | CLAIMS_OFFICER (to themselves), MANAGER |
| `GET /api/v1/management/summary` | Outstanding claims and liability per currency | MANAGER |
| `GET /api/v1/management/officers` | Per-officer open workload and decisions, per currency | MANAGER |

### Assessment Service (default port 8182)

| Method and path | Purpose | Roles |
|---|---|---|
| `POST /api/v1/officer/claims/{claimId}/review` | Start (or resume) review, with optional assessment notes | CLAIMS_OFFICER |
| `GET /api/v1/officer/claims/{claimId}/assessments` | The assessment history: notes, decision, settlement (never shown to claimants) | CLAIMS_OFFICER |
| `POST /api/v1/officer/claims/{claimId}/decision` | Approve (with `approvedAmount`) or reject (with a reason) | CLAIMS_OFFICER |
| `POST /api/v1/officer/claims/{claimId}/settlement` | Record the settlement of an approved claim | CLAIMS_OFFICER |
| `POST /api/v1/officer/claims/{claimId}/information-requests` | Ask the claimant for information | CLAIMS_OFFICER |
| `GET /api/v1/claims/{claimId}/information-requests` | List a claim's information requests and answers | CLAIMANT (own), CLAIMS_OFFICER (visible), MANAGER |
| `POST /api/v1/claims/{claimId}/information` | The claimant answers a request (text only) | CLAIMANT (own) |

### User Service, the authentication service (default port 8185)

| Method and path | Purpose | Roles |
|---|---|---|
| `POST /api/v1/auth/register` | Create a CLAIMANT account | nobody needs to be logged in |
| `POST /api/v1/auth/login` | Exchange a username and password for a Bearer token | nobody needs to be logged in |
| `GET /api/v1/users/me` | The caller's own record | CLAIMANT, CLAIMS_OFFICER, MANAGER, ADMIN |
| `POST /api/v1/users` | Create a user with any role | ADMIN |
| `GET /api/v1/users` | List users (paged, ordered by username) | ADMIN |
| `GET /api/v1/users/{username}` | One user | ADMIN |
| `PUT /api/v1/users/{username}/role` | Change a user's role | ADMIN |
| `PUT /api/v1/users/{username}/active` | Switch an account on or off (an inactive person cannot log in) | ADMIN |

That is 18 claim-domain endpoints (11 + 7) and 8 user endpoints. Request and response examples for every endpoint, with positive and negative cases, are in **[API_TEST_CASES.md](API_TEST_CASES.md)**.

**Service-to-service API (`/internal/v1`).** Not for claimants or officers; it accepts only a service token (section 3). The Claim Service has `GET /internal/v1/claims/{claimId}/status` and `POST .../review`, `.../request-information`, `.../resume-after-information`, `.../decision`, `.../settlement`, all used by the Assessment Service. The User Service has `GET /internal/v1/users/{username}` (role and active flag, nothing secret), used by the Claim Service. The Assessment Service has none.

**Health.** Every service answers `GET /actuator/health` with `{"status":"UP"}` (or `DOWN` with HTTP 503 when its database is unreachable), **without a token and with no details**. Nothing else of the actuator is exposed.

### Authentication and roles

- **Login.** `POST /api/v1/auth/login` with `{"username": "...", "password": "..."}` returns `{accessToken, tokenType: "Bearer", expiresIn, username, role}`. Send `Authorization: Bearer <accessToken>` to **every** service. The token is a signed JWT (HMAC-SHA-256) holding the username, one role, the issuer and an expiry (60 minutes by default, `JWT_EXPIRY`). A wrong password, an unknown user and a deactivated user all get the same 401 `INVALID_CREDENTIALS`.
- **Roles:** `CLAIMANT` (submit, list, correct and track their own claims, answer information requests), `CLAIMS_OFFICER` (take unassigned claims, review, ask for information, approve or reject, settle, read the assessment history), `MANAGER` (assign claims to officers, management reports, read claims), `ADMIN` (every public route, and user management). There is no role hierarchy: a MANAGER cannot review or decide, a CLAIMS_OFFICER cannot read the reports. `SERVICE` is the identity one service uses towards another; it can never be given to a person and is accepted only on `/internal/v1`.
- **Accounts.** Anyone can `register`, which always creates a CLAIMANT. Only an ADMIN can create an officer, manager or administrator, change a role or switch an account off, and cannot change their own role (409 `CANNOT_CHANGE_OWN_ROLE`) or deactivate themselves (409 `CANNOT_DEACTIVATE_SELF`). Login names are lower-case, 3 to 50 characters. Passwords are 8 to 72 bytes and stored only as BCrypt hashes. Besides the demo users below, an administrator can be created at start-up from `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` (environment only); an existing user is never overwritten, by either mechanism.
- **Services do not trust each other blindly.** Every call from one service to another carries a short-lived (60 s) SERVICE token named after the calling service. The `/internal/v1` routes accept only that token: not a person's token, not even an ADMIN's. A person's role and which claims they may see are checked by the service they called; the internal routes trust that check.
- **Errors:** 401 `UNAUTHENTICATED` (no token, or an invalid, tampered, expired or old-format one) and 403 `FORBIDDEN` (a valid token whose role may not use the route), in the same JSON shape as every other error. A route nobody listed is ADMIN-only. Swagger UI and `/v3/api-docs` stay open on every service, and each Swagger UI has an **Authorize** button for the token.

#### Who sees which claim

| Caller | Sees | Anything else |
|---|---|---|
| CLAIMANT | only the claims they submitted (`claims.claimant_id`, set from the token, never from the request body) | 404, the same answer as for a claim that does not exist, on every route (claim, history, assignments, updates, information requests, answer, edit) |
| CLAIMS_OFFICER | unassigned claims (the queue they can pick up from) and the claims assigned to them | 404 as above, on every route including review, decision, settlement and information requests |
| MANAGER, ADMIN | every claim | |

A claim created before claimants were recorded has no owner and is visible to staff only. The list routes apply the same rule in SQL, so a list can never show what a single read would hide, and a filter cannot widen an officer's scope.

#### Who an action is recorded against

- **The officer comes from the token.** A claims officer's review, information request, decision and settlement are recorded against their own login name. The officer ID field in those request bodies (`officerId`, `requestedByOfficerId`, `decidedByOfficerId`, `settledByOfficerId`) may be left out by an officer; repeating their own name is accepted; naming anybody else is 403 `OFFICER_IDENTITY_MISMATCH`.
- **An administrator acting for an officer must name that officer** in the same field (400 `VALIDATION_FAILED` otherwise), so an administrator can never become the "assigned officer" of a claim by leaving the field out. Naming an officer who is not the claim's assigned officer is refused by the Claim Service (409 `OFFICER_MISMATCH`).
- Only the officer a claim is assigned to may request information, decide or settle it.
- History `actor` values are therefore real login names (the claimant's own name for creation and for answering information; the officer for review, decision and settlement; the caller for an assignment). They are as trustworthy as the token, which is signed with a shared secret (section 14).
- **When an administrator acts for an officer, the history shows the officer, not the administrator**: the history columns hold an officer ID and nothing more. So that the administrator is not anonymous, the Assessment Service writes one log line per such request (`ADMIN 'admin1' requested 'review' on claim CLM-... as officer 'officer1'`, written before the Claim Service answers, so it also records a request that was refused). That log line is the only record of the administrator's part, and a log is not a table: it is as durable as the log files are.

#### Assignment rules

- A **manager** (or an administrator) may assign a claim to any officer, who must be an **active CLAIMS_OFFICER**. The Claim Service asks the User Service (`GET /internal/v1/users/{username}`) before locking the claim; an unknown, inactive or non-officer name is 400 `INVALID_OFFICER`, and an unreachable User Service is 503 `DOWNSTREAM_UNAVAILABLE` (nothing is assigned).
- A **claims officer** may only take a claim for themselves (naming anybody else is 403 `ASSIGNMENT_NOT_PERMITTED`), and only an unassigned claim or one already theirs; a claim assigned to someone else is a 404 for them. This needs no User Service call: their role and active state are already in the token.
- **Starting a review of an unassigned claim also assigns it** to the reviewing officer, so the same rule applies there. For a claims officer it is their own token, which was just checked, and nothing more is asked. For an **administrator naming an officer**, the Assessment Service says the name is unverified and the Claim Service asks the User Service before it locks the claim: a name that is not an active claims officer (a typo, a claimant, a deactivated person) is 400 `INVALID_OFFICER` and the claim stays unassigned. A claim that already has an assignee is not asked about again (a different officer is the usual 409 `OFFICER_MISMATCH`, which no longer names the assigned officer).
- A claim can be assigned until it is `SETTLED` or `REJECTED` (an `APPROVED` claim can still be handed to another officer to settle). Every change of assignee is recorded in the assignment history with the caller as the actor; assigning the same officer again adds nothing.

### Getting a token: the login route and the demo users

The login route is `POST http://localhost:8185/api/v1/auth/login` on the **User Service** (not on the Claim or Assessment Service, which only *check* tokens: asking them for `/api/v1/auth/login` gives 401 with no token, or 404 with one, and never a token). It is documented in the User Service's Swagger UI (`http://localhost:8185/swagger-ui/index.html`) under **Authentication**, with request examples for each demo user and the 200, 400 and 401 responses. Quickest path: open it, run **Try it out** with the *Officer* example, copy `accessToken`, press **Authorize** (on that page and on the Swagger UI of each service you call) and paste the token. The Authorize dialog keeps the token when you reload the page. (The documents behind this were checked; clicking through the Swagger UI in a browser was not done.)

**Demo users** are created by the User Service at start-up, so a fresh start can log in at once. **They are for demonstration only:** they share one well-known password, so while they exist anyone who can reach port 8185 can log in as an administrator.

| Username | Role | Password |
|---|---|---|
| `admin1` | ADMIN | `password123` |
| `officer1` | CLAIMS_OFFICER | `password123` |
| `manager1` | MANAGER | `password123` |
| `claimant1` | CLAIMANT | `password123` |

- They are on by default in the packaged configuration (`claims.auth.demo-users.enabled=${DEMO_USERS:true}`). Set `DEMO_USERS=false` to switch them off, and `DEMO_USERS_PASSWORD` to change the password (at least 8 characters). The code default is off, and the automated tests start from an empty users table except the tests that check them.
- They are created only if missing and never reset, so a changed password survives a restart. The password is stored as a BCrypt hash and never written to the log; a start-up WARN line names the users and says that they are enabled. The demo password is the only credential written in any file in this repository, and it is public by design.
- With H2 (the default) all users disappear when the User Service stops, and the demo users are created again at the next start. With PostgreSQL they persist.
- **There is no default signing secret.** Every service reads `claims.security.jwt.secret=${JWT_SECRET:}` and **refuses to start** without a `JWT_SECRET` of at least 32 characters, **the same value in every service** (section 6 says how it is set). A test (`JwtSecretConfigurationTest`) fails if any service's packaged file has a different line or a literal secret or password.

### Key rules

- **Two IDs.** `id` is the internal `BIGINT` identity key. `claimId` (for example `CLM-2026-000101`) is the public reference, generated by the Claim Service from the database sequence `claim_number_seq` (starts at 101, atomic, safe under concurrency, not `count + 1`) with a unique constraint; a client never supplies it. Every `{claimId}` in a path is the public ID; the numeric `id` in a path returns 404. Information requests get their own ID (`IR-2026-000001`, from the Assessment Service's `information_request_number_seq`) and carry the parent `claimId`. Sequence numbers are not reused after a rollback, so gaps are possible. Other services refer to a claim only by `claimId`.
- **The claim record** (create request and response): `claimId` (generated), `claimantId` (the logged-in user, never accepted from the body), `claimantName`, `claimantEmail`, `policyNumber` (**required** on create: 3 to 50 characters, letters, digits, `/`, `_`, `-`, starting with a letter or digit; empty for claims created before it existed), `market`, `claimType` (`MOTOR` or `PROPERTY`), `incidentDescription`, `incidentDate`, `claimedAmount`, `approvedAmount` (null until approved, and for a rejected claim), `currency`, `status`, `assignedOfficerId`, `decisionReason`, `decidedAt`, `settledAt`, `createdAt`, `updatedAt`. The claimant's name, claimant ID and policy number are three separate fields. Assessment notes are never part of it.
- **Lifecycle** (enforced in the `Claim` class inside the Claim Service, not in controllers):

  ```text
  SUBMITTED --review--> UNDER_REVIEW --information request--> MORE_INFO_REQUIRED
                            ^                                          |
                            +------ last OPEN request answered --------+   (automatic)
  UNDER_REVIEW --approve--> APPROVED --settle--> SETTLED      (SETTLED is final)
  UNDER_REVIEW --reject---> REJECTED                          (REJECTED is final)
  ```

  Any other move is a 409 `INVALID_CLAIM_TRANSITION`, so a rejected claim can never be settled (its message says so). A hand-written test lists the allowed moves from each of the six statuses and checks that every other move is refused and changes nothing.
- **After information is answered the claim resumes automatically** to `UNDER_REVIEW` once no `OPEN` request remains; the officer does not need to call `/review` again. The Assessment Service owns the requests, so it decides when none is `OPEN` and then asks the Claim Service to resume the claim.
- **Review from `MORE_INFO_REQUIRED`** is only allowed when no request is `OPEN` (the Assessment Service looks that up in its own database and tells the Claim Service, which decides).
- **Decisions** are only allowed from `UNDER_REVIEW`, and only by the assigned officer. `APPROVED` needs an `approvedAmount` that is positive and not above the claimed amount; `REJECTED` must not carry one; both need a reason. The decision currency must equal the claim currency. A second decision is a 409 and the first is kept. These rules need the claim's amount and currency, so the Claim Service checks them (the Assessment Service validates the request body and forwards it). Approval requires a reason and an amount; there is no separate "findings" prerequisite.
- **Settlement** (`POST .../settlement`) is only allowed from `APPROVED`, by the assigned officer; it records who and when (`settledAt`). A second settlement is a 409.
- **Claimant edits** (`PATCH`): only while the claim is `SUBMITTED` (409 `CLAIM_NOT_EDITABLE` after an officer starts), only these fields: `claimantName`, `claimantEmail`, `policyNumber`, `claimType`, `incidentDescription`, `incidentDate`, `claimedAmount`; the claim number, claimant, market, currency, status, assignment and everything an officer records cannot be changed. The same validation as at creation applies; an empty request is 400 `NO_FIELDS_TO_UPDATE`. The claim is row-locked first, so an edit and the start of a review cannot interleave. Each edit that changes something adds a line to the edit history (`GET .../updates`): one line per field, `field: old -> new` for short values and `field: changed` for free text, so names, e-mail addresses and descriptions are not copied. There is no delete: a submitted claim cannot be removed.
- **Money** is `BigDecimal` with 2 decimals and an explicit 3-letter currency. Currencies are never added together.
- **Management summary** (`GET /api/v1/management/summary`) counts the claims not yet `SETTLED` or `REJECTED`, groups liability by currency, and labels it "Simple estimate ... Not an actuarial reserve". A claim not yet decided counts at its **claimed** amount, an `APPROVED` claim awaiting settlement at its **approved** amount, and a settled or rejected claim not at all. It is computed by SQL `GROUP BY` queries in the Claim Service.
- **Officer metrics** (`GET /api/v1/management/officers`): open workload by the current assignee and status (an `APPROVED` claim is still open work until settled), and decisions by the deciding officer: `decidedCount` (approved plus rejected), `approvedCount` (approved, whether still `APPROVED` or since `SETTLED`), `settledCount`, `rejectedCount` and `approvedAmountByCurrency`. There is no reassignment history and no time-based metric.
- **Search and list** filters (`status`, `market`, `claimType`, `assignedOfficerId`, `assigned`) and paging run in SQL. `assigned=false` lists claims nobody has picked up; a non-boolean value is a 400 `INVALID_PARAMETER`, and `assigned=false` together with `assignedOfficerId` is a 400 `CONFLICTING_FILTERS`. `size` defaults to 20 and is capped at 100. `market` matching is exact and case-sensitive.
- **Status history** (`claim_status_history`) gets one row per status change, written in the same Claim Service transaction as the change: creation (`fromStatus` null, actor the claimant's login name), review, information request, answer-resume (actor the person who answered), approval or rejection, settlement. Assignment does not change status and is not recorded there; it has its own history.
- **Assessment history** (`claim_assessments`, Assessment Service): one append-only entry per officer action, each with a `kind`: `NOTE` (the optional `assessmentNotes` of a review, max 2000 characters, blank means none), `APPROVED` or `REJECTED` (the decision reason) and `SETTLED` (the optional `settlementNote`, otherwise "Claim settled"). An entry is written before the Claim Service is called and committed only if it accepts, so a refused action (404, 409, 400, 503) saves nothing. The history is read with `GET .../assessments`, which accepts only CLAIMS_OFFICER and ADMIN tokens (a MANAGER and a CLAIMANT get 403), and is deliberately **not** part of any claimant-facing response.
- **Information requests** are text only (no document upload). A claim must be `UNDER_REVIEW` to ask, and asking moves it to `MORE_INFO_REQUIRED`; only the claim's own claimant answers; two answers to one request are serialised by a row lock and the second is a 409.
- **Placeholder officer IDs:** the Swagger UI default `"string"` (any case) is rejected with 400 `VALIDATION_FAILED` on every officer ID field.
- **Currency:** every claim has an explicit 3-letter currency (`NOT NULL`, format-checked, returned with every amount). INR is used in the examples; the API accepts any well-formed code because the brief does not name supported currencies, and no exchange rates exist.
- **Market validation** is configurable: `claims.markets.allowed` (comma separated, Claim Service) is empty by default, which keeps accepting any 2-letter upper-case code. When set, other codes are a 400 `INVALID_MARKET`. The brief mentions six markets but does not name them, so none is built in.
- **Errors** share one JSON shape: `{timestamp, status, error, code, message, path}`. Status codes: 400 invalid input, **401 `UNAUTHENTICATED` / `INVALID_CREDENTIALS`**, **403 `FORBIDDEN`** (and `OFFICER_IDENTITY_MISMATCH`, `ASSIGNMENT_NOT_PERMITTED`), 404 unknown claim or request (also a claim the caller may not see), 405 wrong method, 409 invalid transition, wrong officer or duplicate action, 415 wrong content type, **503 `DOWNSTREAM_UNAVAILABLE`** when a service this one depends on cannot be reached or is too slow, and **502 `DOWNSTREAM_ERROR`** when it answers with something unusable. Errors the other service reports for business reasons (unknown claim, invalid transition, bad amount) are passed on with their original status and code.

## 3. Architecture

Three Spring Boot applications, each with its own database, calling each other over synchronous REST. The shared code is only `claims-common` (error model, exception handler, REST client support, the JWT security chain and the claim-visibility rule); it contains no domain code and no database access.

```text
  claimants, officers, managers, admins   (plain HTTP; no gateway; a Bearer token on every call)
            |                    |                       |
            v                    v                       v
     +-------------+      +-------------+        +----------------+
     |    User     |      |    Claim    |        |   Assessment   |
     | (Auth) 8185 |      |    8181     |        |      8182      |
     +-------------+      +-------------+        +----------------+
     | users       |      | claims      |        | claim_         |
     |             |      | status      |        |  assessments   |
     |             |      |  history    |        | information_   |
     |             |      | assignment  |        |  requests      |
     |             |      |  history    |        |                |
     |             |      | update      |        |                |
     |             |      |  history    |        |                |
     +-------------+      +-------------+        +----------------+

  Calls between services (synchronous REST, each with a short-lived SERVICE token):
    Assessment --> Claim   claim status and owner, review, request-information,
                           resume-after-information, decision, settlement
    Claim      --> User    "is this person an active claims officer?"  (assignment by a manager)
  Nobody calls Assessment. The User Service calls nobody. Tokens are NOT checked by calling the User
  Service: it issues them, and every service checks them itself with the shared JWT secret.
```

There are no cycles, so the services can be started in dependency order (section 6).

| Service | Owns | Does | Calls |
|---|---|---|---|
| **User** (`user-service`, 8185) | `users` | Registration, login, token issuing, user, role and active-flag management, the demo users, the officer lookup for other services | nothing |
| **Claim** (`claim-service`, 8181) | `claims`, `claim_status_history`, `claim_assignment_history`, `claim_update_history`, `claim_number_seq` | The claim record and its **lifecycle rules** (the `Claim` entity), creation, listing, tracking, claimant edits, assignment, the three histories, the decision and settlement rules, and the management reports | User (only to check an officer for a manager's assignment) |
| **Assessment** (`assessment-service`, 8182) | `claim_assessments`, `information_requests`, `information_request_number_seq` | Review, the assessment history, information requests and answers, the decision and the settlement | Claim |

**Why there is no API gateway.** The brief asks for a simple architecture, and a gateway would be a fourth component to build, run and secure without a requirement behind it: each service already checks the JWT itself and never reads an identity from a header (the only header it reads is `Authorization`), so there is no spoofable "who is calling" header for a gateway to guard, and the gateway could not replace the per-service checks anyway. Clients call the service that owns an endpoint (the tables in section 2 and the URLs in section 6). The system tests contain a small path router that shows what a gateway's routing table would be.

### What changed in Phase 1

| Topic | Before | Now |
|---|---|---|
| Services | five: User, Claim, Information, Assessment, Reporting | three: User, Claim, Assessment. Information is part of Assessment (same routes, now on port 8182), Reporting is part of Claim (same routes, now on 8181). Ports 8183 and 8184 are gone. |
| Officer role | `OFFICER` | `CLAIMS_OFFICER`. The four roles are `CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER` and `ADMIN` (who administers users and may call every public route). |
| Waiting for the claimant | status `AWAITING_INFORMATION` | `MORE_INFO_REQUIRED` |
| Approval | one step: the decision `SETTLED` moved `UNDER_REVIEW` to `SETTLED` | two steps: the decision `APPROVED` (with `approvedAmount`) moves it to `APPROVED`; a separate settlement moves it to `SETTLED`. `APPROVED` is not final. |
| Decision body | `decision` `SETTLED` or `REJECTED`, `settledAmount`, `decidedByOfficerId` required | `decision` `APPROVED` or `REJECTED`, `approvedAmount`; the officer ID field is optional for an officer (from the token) |
| Claim fields | `description`, `settledAmount`, no policy number, owner hidden | `incidentDescription`, `approvedAmount`, `policyNumber` (required), `claimantId` shown |
| Claim routes | create, get, search (staff), assign, histories | plus `GET /claims` (a claimant's own list), `PATCH /claims/{id}` (edit while `SUBMITTED`) and `GET .../updates` |
| Liability | claimed amount of every claim not settled or rejected | claimed amount until decided, **approved** amount for an `APPROVED` claim, settled and rejected left out |
| Officer metrics | `settledCount`, `settledAmountByCurrency` | `approvedCount`, `settledCount`, `rejectedCount`, `approvedAmountByCurrency`; an approved, unsettled claim is still open work |
| Who sees a claim | claimants: own; every officer and manager: all | claimants: own; officers: the queue and their own; managers: all |
| Who an action is recorded against | the officer ID typed in the body, unverified | the token (officers); an administrator names one |
| Who assigns | any officer, to any ID | managers to an active claims officer (checked with the User Service); an officer only to themselves |
| Users | role only | plus `active`: an inactive account cannot log in |
| History actors | the literal `CLAIMANT`, or the typed officer ID | the claimant's login name, or the officer's login name; the assignment actor is the caller |
| Secrets and credentials | a demo JWT secret in every properties file; a hard-coded PostgreSQL URL, user and password in two files | environment variables only; no default secret (a service refuses to start without one) |
| Health | none | `GET /actuator/health` on every service |

The old monolith's tests were adapted to this model in `system-tests` (field and status names, approval then settlement, lower-case officer login names); the monolith itself was not touched.

### Where the earlier services' code went

| Earlier | Now |
|---|---|
| `information-service` (requests, answers, the `OPEN` check, its `/internal/.../open` route and `InformationClient`) | `assessment-service`, package `assessment.information`; the "any request `OPEN`?" question is now a local query, so the internal route and the client are gone |
| `reporting-service` (the two reports, `ClaimStatsClient`, a copy of the status enum) | `claim-service`, package `claim.management`, reading the claims table directly; the `/internal/v1/claims/stats/*` routes, the client and the copied enum are gone, and the reports no longer fail when "the Claim Service" is down because they are part of it |
| `user-service` | kept as it is (it is the Auth Service); not renamed, because that would touch the modules, packages, scripts and every document for no functional gain |
| `claims-common` | kept: it holds the security chain, the error model and the visibility rule that all three services share |

### Why the lifecycle stays in one service

Every state change must be atomic and serialised per claim. The Claim Service keeps exactly that: each transition (`review`, `request-information`, `resume-after-information`, `decision`, `settlement`) loads the claim with a pessimistic row lock, lets `Claim` enforce the rule, and writes its history row in one local transaction. Nothing about the lifecycle is spread over two services, so no distributed transaction is needed. Other services never change a claim's status directly; they ask the Claim Service to.

### Request flows

- **Review** (`POST .../review` on Assessment): resolve the officer from the token; ask the Claim Service for the claim (404 if unknown or not visible to the caller); look up in its own database whether a request is `OPEN`; insert the note (if any); call Claim `POST /internal/v1/claims/{id}/review`; commit. A refusal rolls the note back.
- **Information request** (`POST .../information-requests`): check visibility; insert the request; call Claim `request-information`; commit.
- **Answer** (`POST .../information`): lock the request row; ask Claim for the status and owner; save the answer; if no request is left `OPEN`, call Claim `resume-after-information` (naming the answerer); commit.
- **Decision and settlement**: check visibility; insert the history entry; call Claim `decision` or `settlement`; commit.
- **Assignment** (`POST .../assign` on Claim): decide who may name whom; for a manager, ask the User Service whether the officer is an active claims officer (before the claim is locked, so no database lock is held across the call); then lock the claim, check visibility, change the assignee and write the assignment history in one transaction.
- **Reports** (Claim): read-only SQL aggregate queries, one transaction per report.

### Consistency and failure behaviour

Each service writes its own database in a local transaction and calls the owning service before that transaction commits, so a refusal or an outage undoes the local write: **nothing is stored when a call fails** (tested with real refused connections, section 10). Calls use a 2 s connect and 10 s read timeout (`claims.downstream.*`) and are **never retried**, because they change state and repeating one that may already have succeeded is unsafe. There are no sagas, outbox or idempotency keys. The windows this leaves, which are real:

1. **Commit fails after the Claim Service already accepted** (an Assessment review, decision, settlement, information request or answer). The local write is lost but the claim has moved: a review or decision without its history entry; a claim `MORE_INFO_REQUIRED` with no request (an officer can recover with `review`, because no request is `OPEN`); or a claim resumed while its answered request is still `OPEN`. This needs the service's own database to fail in the instant between two statements.
2. **A timeout where the other side did succeed.** The caller reports 503 and rolls back, but the claim already changed; a retry then gets a 409.
3. **An assignment by a manager needs the User Service**, and so does a review an administrator starts for an officer on an unassigned claim. If it is down the assignment is refused with 503 (an officer taking a claim for themselves, or starting the review of an unassigned claim as themselves, still works). The check happens before the claim is locked, so a person who is deactivated in the instant between the check and the write can still be assigned.
4. **An Assessment action needs the Claim Service** (it asks about the claim first). If it is down, every Assessment route that names a claim answers 503.
5. **Deactivating a person does not end their session**: a token already issued works until it expires (section 14). A deactivated officer keeps the claims already assigned to them until a manager reassigns them, and can still act on them, and take unassigned claims for themselves, until their token expires.
6. **A database connection is held while the other service is called.** An Assessment action that writes (review with a note, decision, settlement, information request, answer) opens its transaction first and calls the Claim Service before committing, which is what makes a refusal roll the local write back. The connection stays in use during that call, up to the 10 s read timeout. If the Claim Service hangs rather than refuses, enough simultaneous Assessment requests (about ten, with the default connection pool) use up the pool until the timeouts fire. The Claim Service's own assignment path avoids this by asking the User Service before it opens a transaction.

### Service-to-service API

`/internal/v1` routes (listed in each service's Swagger UI under **Internal (service-to-service)**; contracts in `API_TEST_CASES.md`). They accept **only a SERVICE token**: a person's token, an ADMIN's included, gets 403, and no token gets 401. Each service mints its own SERVICE token with the shared `JWT_SECRET` for every call it makes (a `RestClientCustomizer` in `claims-common` adds it, so the client classes know nothing about it). This is real protection against a client calling an internal route directly, but it is **shared-secret trust**: any process that knows `JWT_SECRET` can mint a token with any role, so keep the secret out of source control and keep the ports on a trusted network. A service that holds a different secret fails its calls with 502 `DOWNSTREAM_ERROR` and changes nothing (tested for Assessment to Claim and for Claim to User).

### Other design points

- JPA entities are never returned directly; each endpoint uses a DTO record. Each service keeps only the fields of another service's data that it needs, and tolerates new fields.
- Flyway owns each service's schema (its own folder `db/migration/user`, `.../claim`, `.../assessment`); Hibernate only validates it (`ddl-auto=validate`). An applied migration is never edited; changes are new versions.
- There is **no foreign key across services**. `information_requests` and `claim_assessments` store the public `claim_id` as text; the owning service is asked whether the claim exists.
- Indexes: Claim `(status, market)`, `assigned_officer_id`, `claim_type`, `created_at`, `(status, currency)` for the aggregates and `(claimant_id, created_at)` for a claimant's own list; Assessment `(claim_id, status)` on information requests and `(claim_id, created_at)` on assessment entries.

## 4. Technology stack and prerequisites

| Item | Version / detail |
|---|---|
| Java | 17 (verified with OpenLogic OpenJDK 17.0.16) |
| Spring Boot | 3.3.5 (Web, Data JPA, Validation, Security, Actuator for the health endpoint only) |
| Authentication | JWT (HS256) with `jjwt` 0.12.6; BCrypt password hashes (Spring Security Crypto); stateless, no sessions |
| Build | Maven multi-module (`microservices/pom.xml`), via the Maven Wrapper at the repository root (`mvnw.cmd`, downloads Maven 3.9.9 on first use) |
| Service calls | Spring `RestClient` over the JDK HTTP client (HTTP/1.1) |
| Migrations | Flyway (`flyway-core`, `flyway-database-postgresql`), one folder per service |
| Databases | One H2 (in memory, default) per service, and PostgreSQL (configurable, only partly verified, section 8) |
| API docs | `springdoc-openapi-starter-webmvc-ui` 2.6.0, one Swagger UI per service |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Mockito, AssertJ |

Prerequisites: a JDK 17 and internet access the first time (to download Maven, dependencies and Maven's packaging plugins from Maven Central). You do **not** need to install Maven, PostgreSQL or Kafka to run the services or the tests.

## 5. Project structure

```text
.
├── README.md                  this file
├── API_TEST_CASES.md          contracts and test cases for all endpoints
├── DESIGN_DECISIONS.md        why it is built this way
├── CLAUDE.md                  development rules for this repository
├── .claude/ai-journal.md      AI working journal
├── design-i.docx              the original assessment brief
├── mvnw, mvnw.cmd, .mvn/      Maven Wrapper (used for everything, also for the microservices)
├── pom.xml, src/              the ORIGINAL monolith, untouched (reference; not part of the microservices build)
└── microservices/
    ├── pom.xml                parent POM and module list
    ├── claims-common/         ApiException, ApiError, GlobalExceptionHandler, OfficerIds, DownstreamClient,
    │                          and security/ (Role, JwtUtil, JwtFilter, SecurityConfig, AccessRule(s), ClaimVisibility,
    │                          service tokens); its test-jar holds the shared test helpers (token factory, role-matrix checker)
    ├── user-service/          port 8185, own database (db/migration/user): the Auth Service
    ├── claim-service/         port 8181, own database (db/migration/claim): claims, assignment, management reports
    ├── assessment-service/    port 8182, own database (db/migration/assessment): review, information, decision, settlement
    ├── system-tests/          the three services in one JVM, driven over real HTTP
    └── scripts/               start-all.ps1, stop-all.ps1, smoke-test.ps1
```

Each service module has `src/main/java/com/chubb/claims/<service>/...`, `src/main/resources/application.properties` (port, database, downstream URLs), `application-postgres.properties` (opt-in) and its own tests. Each service's `*Application` class must stay in its own module.

## 6. Build, startup order and run

Everything is run from the repository root with the Maven Wrapper.

### Build and test

```bat
.\mvnw.cmd -f microservices/pom.xml clean test              :: all modules, all tests (a few minutes)
.\mvnw.cmd -f microservices/pom.xml -DskipTests package     :: also builds a runnable jar per service
```

The runnable jars are `microservices/<service>/target/<service>-0.0.1-SNAPSHOT-exec.jar`. **Do not run `clean` while you have a service running from the IDE:** it deletes that module's `target/classes` under the running process (run `test` or `package` without `clean` instead).

**"cannot find symbol" for `ClaimVisibility` or `Role.CLAIMS_OFFICER` (and then errors in `DemoUsers`, the `*AccessRules` classes and the services that use them)?** The source is fine; the compiler is reading an **old `claims-common`**. A module built on its own (`-pl user-service` without `-am`, or IntelliJ with the module not loaded as part of `microservices/pom.xml`) compiles against the jar last *installed* into `~/.m2\repository\com\chubb\claims-common`, and a jar from before Phase 1 has neither symbol. Reproduced on 2026-10-26: 29 distinct compile errors in three modules with the old jar (Maven prints each twice), 0 after installing the current one; the old jar also lacks `ApiException.forbidden` and `AuthenticatedUser.actingOfficerId`. Fix: `.\mvnw.cmd -f microservices/pom.xml clean install` (everything, with tests) or `.\mvnw.cmd -f microservices/pom.xml -pl claims-common -am install` (just the shared module), then in IntelliJ **Reload All Maven Projects** and **Build > Rebuild Project**. `install` only writes to the local Maven repository on your machine.

### Startup order

Start in dependency order. The services only call each other when a request arrives, so a different order also starts, but requests that need a service that is not up yet get a 503.

| Order | Service | Port | Needs running |
|---|---|---|---|
| 1 | `user-service` | 8185 | nothing |
| 2 | `claim-service` | 8181 | the User Service, only for a manager's assignment |
| 3 | `assessment-service` | 8182 | the Claim Service |

**Script (verified, on spare ports):**

```powershell
.\microservices\scripts\start-all.ps1                  # builds (tests skipped) and starts all three in order
.\microservices\scripts\start-all.ps1 -SkipBuild       # jars already built
.\microservices\scripts\start-all.ps1 -SkipBuild -UserPort 18185 -ClaimPort 18181 -AssessmentPort 18182   # a port is taken: the others are pointed at the new ones
.\microservices\scripts\smoke-test.ps1                 # the demo flow, as real users, against the running services
.\microservices\scripts\smoke-test.ps1 -UserUrl http://localhost:18185 -ClaimUrl http://localhost:18181 -AssessmentUrl http://localhost:18182
.\microservices\scripts\stop-all.ps1                   # stops only the processes start-all.ps1 started
```

The script refuses to start if a port is busy and says which process holds it; it never stops a process it did not start. It waits for each service's `/actuator/health`. Logs and PID files go to `microservices/logs` (git-ignored).

**Credentials.** If `JWT_SECRET` is set in your PowerShell session the script passes it to all three services (at least 32 characters, otherwise it stops). If it is not set, **the script generates a random one for this run** and gives it to the three processes only: it is not printed, not written to a file and not left in your session, and tokens from this run stop working when you stop it. Set your own `JWT_SECRET` to keep tokens valid across restarts. It also passes `CLAIM_SERVICE_URL` and `USER_SERVICE_URL` for the ports you chose (and puts your session's values back afterwards). Everything else in your session (`USER_DB_*`, `CLAIM_DB_*`, `ASSESSMENT_DB_*`, `DEMO_USERS`, `DEMO_USERS_PASSWORD`, `BOOTSTRAP_ADMIN_*`) is inherited by the services.

**By hand (one terminal per service, in this order).** Every window needs the **same** `JWT_SECRET` before the service starts (a value that differs between windows makes the other services answer 401 to a token from the User Service, and an Assessment call to the Claim Service fail with 502):

```powershell
# one value, copied into every window (at least 32 characters); nothing is written to a file
$bytes = New-Object byte[] 48; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:JWT_SECRET = -join ($bytes | ForEach-Object { $_.ToString('x2') })
# optional: no demo users, and your own first administrator instead
$env:DEMO_USERS = 'false'; $env:BOOTSTRAP_ADMIN_USERNAME = '<an administrator>'; $env:BOOTSTRAP_ADMIN_PASSWORD = '<at least 8 characters>'
java -jar microservices\user-service\target\user-service-0.0.1-SNAPSHOT-exec.jar
java -jar microservices\claim-service\target\claim-service-0.0.1-SNAPSHOT-exec.jar
java -jar microservices\assessment-service\target\assessment-service-0.0.1-SNAPSHOT-exec.jar
```

Stop each with `Ctrl+C`. A service started without `JWT_SECRET` (or with one under 32 characters) stops at once with a message naming `JWT_SECRET` and never opens its port (observed on the real claim-service jar, section 12).

### Running from the IDE (IntelliJ)

The verified routes are the scripts and the jars above; running from the IDE was not exercised by me. What you need for each Run configuration (Environment variables field), one configuration per service, with **"Use classpath of module"** set to that service's module (`user-service`, `claim-service`, `assessment-service`) and `microservices/pom.xml` loaded as a Maven project so `claims-common` is built from source:

| Service | Environment variables | Notes |
|---|---|---|
| User | `JWT_SECRET` (required); optionally `USER_DB_URL`, `USER_DB_USERNAME`, `USER_DB_PASSWORD`, `DEMO_USERS`, `DEMO_USERS_PASSWORD`, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD`, `JWT_EXPIRY` | port 8185 |
| Claim | the **same** `JWT_SECRET`; optionally `CLAIM_DB_URL`, `CLAIM_DB_USERNAME`, `CLAIM_DB_PASSWORD`; `USER_SERVICE_URL` if the User Service is not on 8185 | port 8181 |
| Assessment | the **same** `JWT_SECRET`; optionally `ASSESSMENT_DB_URL`, `ASSESSMENT_DB_USERNAME`, `ASSESSMENT_DB_PASSWORD`; `CLAIM_SERVICE_URL` if the Claim Service is not on 8181 | port 8182 |

- **Without the `*_DB_URL` variables each service runs on its own in-memory H2 database** and loses its data when it stops. To use PostgreSQL set that service's three variables (one database per service, section 7).
- **The Claim Service's default port is 8181 again.** It used to be 8787 because of a hard-coded local file. If you want to keep 8787 (for example because something else uses 8181), add the program argument `--server.port=8787` to its run configuration (the same mechanism the script uses; `SERVER_PORT=8787` as an environment variable is standard Spring Boot behaviour but was not run here), and then set `CLAIM_SERVICE_URL=http://localhost:8787` in the Assessment run configuration. The monolith in the repository root also defaults to 8181, so do not run both on it.
- Delete any old run configuration for the Information and Reporting services (those modules no longer exist).
- History worth knowing: on 2026-10-26 the IDE moved `UserServiceApplication.java` into the monolith's `src/main/java`, which left `user-service` without a main class, and a run configuration named `UserServiceApplication` then ran the **monolith's** classpath on port 8181 (no login route, 404 on `/api/v1/auth/login`) while nothing listened on 8185. The class was restored in `microservices/user-service` and the stray copy in the monolith was **removed in Phase 1 with your approval**. If a run configuration still behaves like that, fix its "Use classpath of module".

### Ports and configuration

Defaults are in each service's `src/main/resources/application.properties`. Override with `--server.port=...` or the environment variables below. **Nothing secret is written in any file.**

| Setting | Where | Default |
|---|---|---|
| `server.port` | every service | 8181 (Claim), 8182 (Assessment), 8185 (User) |
| `JWT_SECRET` | **every service** (the same value) | **none**: a service refuses to start without at least 32 characters |
| `JWT_EXPIRY` | User | `60m` (lifetime of a login token) |
| `USER_DB_URL`, `USER_DB_USERNAME`, `USER_DB_PASSWORD` | User | in-memory H2 (`userdb`), `sa`, empty |
| `CLAIM_DB_URL`, `CLAIM_DB_USERNAME`, `CLAIM_DB_PASSWORD` | Claim | in-memory H2 (`claimdb`), `sa`, empty |
| `ASSESSMENT_DB_URL`, `ASSESSMENT_DB_USERNAME`, `ASSESSMENT_DB_PASSWORD` | Assessment | in-memory H2 (`assessmentdb`), `sa`, empty |
| `DEMO_USERS`, `DEMO_USERS_PASSWORD` | User | `true`, `password123`: creates `admin1`, `officer1`, `manager1`, `claimant1` (demo only) |
| `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` | User | unset: no extra administrator is created |
| `USER_SERVICE_URL` | Claim | `http://localhost:8185` |
| `CLAIM_SERVICE_URL` | Assessment | `http://localhost:8181` |
| `claims.downstream.connect-timeout`, `claims.downstream.read-timeout` | Claim, Assessment | `2s`, `10s` |
| `claims.markets.allowed` | Claim | empty (any 2-letter code) |

### URLs

| Service | API base | Swagger UI | OpenAPI JSON | Health |
|---|---|---|---|---|
| User | `http://localhost:8185` | `/swagger-ui/index.html` | `/v3/api-docs` | `/actuator/health` |
| Claim | `http://localhost:8181` | `/swagger-ui/index.html` | `/v3/api-docs` | `/actuator/health` |
| Assessment | `http://localhost:8182` | `/swagger-ui/index.html` | `/v3/api-docs` | `/actuator/health` |

`/swagger-ui.html` redirects to the Swagger UI on each. Each service documents only its own routes. The Swagger UI, the API docs and the health endpoint need no token; to try a protected route in Swagger, log in on the User Service's Swagger UI (`POST /api/v1/auth/login`), copy `accessToken`, and paste it into **Authorize** on the service you want to call (not exercised by hand in this work).

## 7. Databases: one per service, H2 by default

Each service owns its data and no service reads another's tables.

| Service | Tables | Flyway folder | Default H2 name | Environment variables (PostgreSQL) |
|---|---|---|---|---|
| User | `users` | `db/migration/user` (V1-V2) | `userdb` | `USER_DB_URL`, `USER_DB_USERNAME`, `USER_DB_PASSWORD` |
| Claim | `claims`, `claim_status_history`, `claim_assignment_history`, `claim_update_history` | `db/migration/claim` (V1-V5) | `claimdb` | `CLAIM_DB_URL`, `CLAIM_DB_USERNAME`, `CLAIM_DB_PASSWORD` |
| Assessment | `claim_assessments`, `information_requests` | `db/migration/assessment` (V1-V2) | `assessmentdb` | `ASSESSMENT_DB_URL`, `ASSESSMENT_DB_USERNAME`, `ASSESSMENT_DB_PASSWORD` |

```properties
# claim-service (the others are the same pattern)
spring.datasource.url=${CLAIM_DB_URL:jdbc:h2:mem:claimdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}
spring.datasource.username=${CLAIM_DB_USERNAME:sa}
spring.datasource.password=${CLAIM_DB_PASSWORD:}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.locations=classpath:db/migration/claim
```

- **The hard-coded PostgreSQL settings are gone.** Until Phase 1 the Claim and User Services' `application.properties` held a local PostgreSQL URL, user name and password (and the Claim Service the port 8787). They now use the variables above. If you had been running those services against your PostgreSQL through those files, **set `CLAIM_DB_*` and `USER_DB_*` in your run configurations**; without them the services start on empty in-memory H2 databases (your PostgreSQL data is neither used nor touched). That password sat in a file in an untracked, OneDrive-synced folder, so **treat it as exposed and change it** on the server. A guard test (`JwtSecretConfigurationTest`) now fails the build if any packaged configuration holds a secret or credential literal.
- Flyway creates the tables and sequences at start-up; Hibernate then validates them.
- **With H2, data lives in memory and is lost when a service stops.** There is nothing to install or create. Stopping one service loses only that service's data, which also means the three databases can disagree after a partial restart (for example claims in the Claim Service whose information requests were in a restarted Assessment Service). Treat the in-memory default as a demo setup.
- H2 is a `runtime`-scope dependency. `MODE=PostgreSQL` only approximates PostgreSQL. `LOCK_TIMEOUT=10000` lets a transition wait for a concurrent one's row lock instead of failing after H2's short default.
- There is **no data migration** from the monolith. Its default database was in memory, so nothing needed carrying over.

## 8. Database: PostgreSQL (configured, only partly verified)

**One PostgreSQL database per service, never two services in one database** (see "Flyway: checksum mismatch" below). Create them once, connected to the maintenance database as a role that may create databases (for example with `psql -U postgres`), skipping any that exist (your existing Claim database may have another name; use that one):

```sql
CREATE DATABASE user_db;
CREATE DATABASE claim_db;
CREATE DATABASE assessment_db;
```

Then set that service's three variables in your own shell or run configuration (do not commit them or paste them into chat), for example:

```powershell
$env:CLAIM_DB_URL      = 'jdbc:postgresql://localhost:5432/claim_db'
$env:CLAIM_DB_USERNAME = '<a role that may create tables in it>'
$env:CLAIM_DB_PASSWORD = '<set in your own session only>'
```

Setting the URL is enough to select PostgreSQL. The optional `postgres` profile (`--spring.profiles.active=postgres`, file `application-postgres.properties`) additionally defaults the URL to `localhost:5432/<service>_db` and makes the user name and password mandatory. Flyway creates the tables on first start.

**What has and has not been run on PostgreSQL.** A local PostgreSQL 17.6 exists on the development machine. Against it, on 2026-10-26 and **before Phase 1**: the Claim Service's migrations V1 to V4 applied and validated (run by the owner; read back by me: four successful history rows whose checksums equal the files'); the Claim Service started with Hibernate's `ddl-auto=validate` and answered an authenticated paged officer search from PostgreSQL; the User Service's V1 applied in its own database `user_db`, its four demo users were inserted through JPA, and logging in and calling the Claim Service with the resulting tokens worked. **Not run on PostgreSQL (NOT TESTED):** creating and transitioning claims (the `nextval('claim_number_seq')` native query, `SELECT ... FOR UPDATE` locking, `TIMESTAMP WITH TIME ZONE` round trips), the concurrency tests, the Assessment Service's database, and **the three Phase 1 migrations** (user V2, claim V5, assessment V2): those were run and verified on H2 only, including over a database pre-filled with old rows (section 12). They use plain `ALTER TABLE`, `UPDATE`, `CREATE TABLE` and `CREATE INDEX` statements that PostgreSQL also accepts, but that is reasoning, not a test. Treat everything not listed as run as untested.

### Upgrading an existing database (Phase 1)

**Back up your databases first.** Flyway applies the new migrations automatically the next time each service starts against its database, and I did not apply them to your PostgreSQL (Phase 1 wrote nothing to your server).

| Database | Migration | What it does to existing data |
|---|---|---|
| User | `V2__claims_officer_role_and_active_flag` | drops and re-adds the role check, renames the stored role `OFFICER` to `CLAIMS_OFFICER` for every user, adds `active` (every existing user stays active) |
| Claim | `V5__claim_model_phase1` | drops and re-adds the status check with the six statuses; renames `AWAITING_INFORMATION` to `MORE_INFO_REQUIRED` in `claims` **and in `claim_status_history`** (the one place history rows are touched: the meaning of each row is unchanged, only the name of that status); renames columns `description` to `incident_description`, `settled_amount` to `approved_amount` (a claim that was `SETTLED` in one step keeps its amount as its approved amount and stays `SETTLED`, with its history still reading `UNDER_REVIEW` to `SETTLED`), `created_by` to `claimant_id`; adds `policy_number` (empty for old claims), `settled_by_officer_id` and `settled_at` (for a settled claim they are filled from the decision's officer and time); adds an index for a claimant's own list and the `claim_update_history` table |
| Assessment | `V2__information_requests_and_assessment_kind` | adds `kind` to `claim_assessments` (every old entry becomes a `NOTE`) and creates `information_requests` and its sequence. **Nothing is copied from the old Information Service's database**: its data lived in memory unless you had pointed it at a database with `INFORMATION_DB_URL`; if you did, export it yourself and insert it into the Assessment Service's `information_requests` table |

Consequences to expect when you restart:

- **Everyone logs in again.** The role in old tokens is called `OFFICER`, which is no longer a role, so old tokens get 401.
- **A claim that was waiting for information** (`AWAITING_INFORMATION`, now `MORE_INFO_REQUIRED`) keeps its status, but its question was stored in the old Information Service's database, which is not copied. The claimant sees no question and cannot answer; no request is `OPEN`, so an officer can resume it with `review` and ask again.
- **Old assigned officer IDs stay as they were.** A claim assigned to `OFF-1007` (a free-text ID typed before this change) keeps that assignee, but there is no such login, so no officer can see or act on it (it is neither unassigned nor theirs); a manager or administrator has to reassign it to a real active claims officer.
- **Claims without an owner** (created before owners were recorded) are visible to staff only, as before. Claims created before `policyNumber` existed have none; the API requires it only for new claims.
- The old Information (8183) and Reporting (8184) services are no longer started; call the same routes on 8182 and 8181.

### Flyway: "Migration checksum mismatch for migration version 1"

**Why it happens here.** Flyway keeps one history table (`flyway_schema_history`) per database and checks each service's migration files against it at start-up. Every service has a **version 1** with different content (Claim: claims schema; User: users; Assessment: assessments). If two services use the same database, the first one records its V1 and the second then fails with
`Migration checksum mismatch for migration version 1 -> Applied to database : 1039668195 -> Resolved locally : -1274364295` (the numbers are the Claim and User V1 files' checksums). This was reproduced on 2026-10-26, on a throwaway in-memory database, and is recorded by `DatabasePerServiceConfigurationTest`.

**Do not "fix" it with Flyway `repair`.** Run from the failing service's side, `repair` succeeds by relabelling version 1 as that service's, so the **other** service now fails with the same error, and the repaired service believes its tables exist when they were never created (reproduced: after the repair the Claim Service failed and the `users` table did not exist). `clean` deletes everything in the schema.

**The fix:** one database per service. Concretely, give the failing service its own database (`CREATE DATABASE user_db;`) and point its `*_DB_URL` at it (on 2026-10-26 the User Service was moved from `postgres`, shared with the Claim Service, to `user_db`; the Claim Service stayed in `postgres` with its data untouched). A guard test fails the build if two services' packaged datasource URLs are the same.

**If you see a mismatch, work out which case it is first** (read-only):

```sql
select installed_rank, version, description, script, checksum, success from flyway_schema_history order by installed_rank;
```

| What the history shows | Cause | What to do |
|---|---|---|
| Rows for migrations that belong to **another service** (other descriptions, another service's V1) | Two services share the database | One database per service, as above. In a throwaway dev database you may instead reset it (`DROP SCHEMA public CASCADE; CREATE SCHEMA public;` deletes **everything** in that database), then start each service once against its own database. |
| The service's own rows, but a checksum differs from the file | A migration that was already applied has been **edited** | Restore the original file (`git checkout`, or your editor's history). Never edit an applied migration; add a new version. Only if the edit was harmless (a comment or whitespace) and you accept it, realign by hand with the number Flyway prints as "Resolved locally": `update flyway_schema_history set checksum = <that number> where version = '<the version>';` (this is what `flyway repair` does for a checksum; it was not run against your database). |
| A failed row (`success = false`) | Not expected on PostgreSQL, which runs each migration in one transaction so a failing migration is rolled back completely (per Flyway's documentation; not exercised here). It can still happen if a migration contains a statement that cannot run in a transaction | Fix the cause. Flyway `repair` removes a failed row; any objects the migration created before it failed must be dropped by hand before restarting. |

The history table is the only thing Flyway's validation reads, and the data tables are separate, so resetting one service's database never affects another's. (These repair steps were not executed against your PostgreSQL; the Flyway behaviour behind them was reproduced on in-memory H2.)

## 9. Kafka

**Kafka is not implemented.** There is no producer, consumer, topic, dependency or configuration for it, and nothing in this repository has been tested against a broker. It is planned as a later phase and has not been started. The synchronous REST calls described in section 3 are the only communication between services.

Possible future use (a proposal only): publish claim events (for example `ClaimSubmitted`, status changes) so reporting could be fed by events instead of reading the claims table. That would need an event schema, idempotent consumers, a failure strategy (for example an outbox or retries) and verification against a real running broker before it could be called working.

## 10. Tests

```bat
.\mvnw.cmd -f microservices/pom.xml clean test
```

Reports: each module's `target/surefire-reports/` contains `TEST-*.xml` and `*.txt` per test class. No PostgreSQL or Kafka is needed. Do not run `clean` while a service runs from the IDE (section 6).

| Module (tests) | What it tests | How |
|---|---|---|
| `claims-common` (54) | `DownstreamClient`'s failure policy against a real local HTTP server (business errors re-raised, 502, 503 for refused connection and timeout, a downstream 401/403 becomes a 502 with no detail); the exception handler; `JwtUtil` (round trip, expiry and clock skew, tampered, wrong secret, wrong issuer, `alg=none`, HS512, no expiry, unknown role, short secret); **the whole security chain once** against probe routes (401 vs 403, error body shape, internal routes SERVICE-only, unlisted routes ADMIN-only, the health route open and other actuator routes not, no generated-password user, service tokens on outgoing calls); **`ClaimVisibilityTest`: the visibility rule and the officer-identity rule** | JUnit; a probe Spring Boot app in the test sources |
| `user-service` (62) | Register and login (validation, duplicates, hashing, identical answer for unknown user, wrong password and deactivated user), user administration, role changes and their limits, **the active flag (including that a deactivated account still costs a password check), the SERVICE-only officer lookup**, `/me`, the role matrix, bootstrap administrator, the demo users, **`UserMigrationTest`: V2 run over a database holding old users (including an `OFFICER`)** | MockMvc + own H2; Flyway directly for the migration |
| `claim-service` (201) | Create/get/list with policy number and claimant from the token, **claimant edits and their history, assignment rules (manager, officer, eligibility via a mocked User Service, 503 when it is down), the visibility rules on every read route and list, the `Claim` lifecycle (a hand-written table of the allowed moves from each of the six statuses), approval, rejection and settlement through the internal API, the assigned-officer rule, the management reports against real data (including an approved claim counting at its approved amount)**, histories, market policy, the HTTP contract of every JSON POST, concurrent transitions (two decisions, two settlements, an edit racing a review), the role matrix per route, **the review route's officer check (a verified caller, an unverified one, an already assigned claim, the User Service down)**, **`UserClientTest` (the real client against a real local HTTP server), `ClaimMigrationTest` (V5 run over a database holding old claims in several statuses with their history)** | MockMvc + own H2; the User Service replaced by a mock client |
| `assessment-service` (90) | Review with and without notes, note rollback on refusal/outage, the assessment history (note, decision and settlement entries), decision forwarding and error pass-through, settlement, **information requests and answers (formerly the Information Service's tests): error precedence, rollback when the Claim Service refuses or is down, two concurrent answers**, the role matrix, **officer scoping and officer identity on every officer route, claimant ownership on list and answer**, **`ActingAsTest` (the administrator's log line, and that a line break in a name cannot forge one)**, **`AssessmentMigrationTest`: V2 run over a database holding an old note** | MockMvc + own H2; the Claim Service replaced by a mock client |
| `system-tests` (173) | **The three services running together** (own in-memory database and random port each, calling each other over real HTTP, including Claim calling User). The monolith's HTTP test classes, adapted to the new model, plus data ownership, per-service OpenAPI/Swagger (including the bearer scheme), the health endpoints, a full demo flow, failure tests with a dependency that is really down or holds a different secret (Assessment to Claim, Claim to User), **`AuthenticationFlowTest`: real logins against the running User Service driving a claim from submission through assignment, review, information, approval and settlement, each step by the right role, plus a deactivated officer, a rejected claim that cannot be settled and old-format tokens**, **`DemoUsersFlowTest`**, **`JwtSecretConfigurationTest`** (no service ships a secret or credential literal, every service reads `JWT_SECRET` only) and **`DatabasePerServiceConfigurationTest`** (no two services share a datasource URL; records on in-memory H2 why: the Flyway version 1 collision and the repair that only moves it) | JUnit; the original MockMvc code runs against `HttpMvc`, a small MockMvc look-alike that sends real requests |

Every module's role test works two ways, on purpose. One test calls each route as every role (and with no token) and checks that the service enforces its own rule table. A second test compares that table with a **hand-written copy of the documented role matrix**, because the first one alone cannot notice a wrong table (found by deliberately widening one rule: only the hand-written comparison failed). `RouteCoverage` additionally fails if any `/api/v1` route has no rule.

**The original monolith tests.** The monolith's own 142 tests are unchanged and still pass in the monolith (section 12). Its HTTP test classes were also replayed in `system-tests` through a path router; in Phase 1 those copies were **adapted** (they are not "run unchanged" any more): officer names became lower-case login names that exist as real accounts (`off-1007`, seeded by the test harness), `AWAITING_INFORMATION` became `MORE_INFO_REQUIRED`, decision `SETTLED` became `APPROVED` followed by a settlement where the test needs a final claim, field names changed, and a decision is made by the officer the claim is assigned to. A request that sets no `Authorization` header is given a default identity (ADMIN on public routes, SERVICE on `/internal`) so the business-rule tests are about the rule, not login; a test that cares who is calling sets its own header, and an empty header means "no credentials". Consequence to keep in mind: the replayed business-rule tests prove the rules for an authorised caller, not that a claimant is kept out; the role tests above do that. The endpoint-by-endpoint coverage table is in `API_TEST_CASES.md`.

Notes on the system tests: the modules each ship an `application.properties`, which cannot coexist on one classpath, so the harness passes every setting explicitly; the services' real configuration files are exercised by starting the packaged jars (sections 11 and 12) and read by `JwtSecretConfigurationTest` and `DatabasePerServiceConfigurationTest`. Concurrency tests run on H2 and must not be presented as PostgreSQL proof. Run `system-tests` (and any module that uses the shared test helpers) from the reactor (`-f microservices/pom.xml`, or with `-pl <module> -am`). No signing secret is stored anywhere in the test code or configuration: every test JVM generates a random one.

## 11. Manual testing and demo flow

### Smoke test script

With the three services running (section 6). It logs in as the administrator you give it with `-AdminUsername` and `-AdminPassword`, else as `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` from your session, else as the demo user `admin1`:

```powershell
.\microservices\scripts\smoke-test.ps1                      # default ports
.\microservices\scripts\smoke-test.ps1 -UserUrl http://localhost:18185 -ClaimUrl http://localhost:18181 -AssessmentUrl http://localhost:18182
```

It creates an officer and a manager (unique names, so it can be run repeatedly; they stay in the User Service's database), registers two claimants, and runs the demo flow as the right person at each step: health, login, create, read, list, correct, assign by a manager (and the refusals), review with a note, information request and answer, the officer-only assessment history, the management reports, approval, a duplicate decision, settlement, a rejected claim that cannot be settled, history, an unknown claim, and switching an officer off. It counts before and after instead of assuming an empty system. It runs in 10 stages (0 to 9) with **81 checks**, prints PASS/FAIL per check and exits 1 on any difference.

### Swagger UI

Open the Swagger UI of the service that owns each endpoint (section 6), authorize with a token from the User Service and follow the demo flow in `API_TEST_CASES.md`.

### Hand-made requests

```powershell
# log in as three of the demo users (the User Service is on 8185)
$json = @{'Content-Type'='application/json'}
$claimant = (Invoke-RestMethod -Method Post "http://localhost:8185/api/v1/auth/login" -Headers $json -Body '{"username":"claimant1","password":"password123"}').accessToken
$officer  = (Invoke-RestMethod -Method Post "http://localhost:8185/api/v1/auth/login" -Headers $json -Body '{"username":"officer1","password":"password123"}').accessToken
$manager  = (Invoke-RestMethod -Method Post "http://localhost:8185/api/v1/auth/login" -Headers $json -Body '{"username":"manager1","password":"password123"}').accessToken
# the claimant submits a claim, the manager assigns it, the officer reviews it (Assessment Service), the manager reads the summary (Claim Service)
$c = Invoke-RestMethod -Method Post "http://localhost:8181/api/v1/claims" -Headers @{Authorization="Bearer $claimant"; 'Content-Type'='application/json'} -Body '{"claimantName":"Mei Tan","claimantEmail":"mei.tan@example.com","policyNumber":"POL-2026-0042","market":"SG","claimType":"MOTOR","incidentDescription":"Rear bumper damaged.","incidentDate":"2026-10-12","claimedAmount":2400.00,"currency":"INR"}'
$id = $c.claimId
Invoke-RestMethod -Method Post "http://localhost:8181/api/v1/officer/claims/$id/assign" -Headers @{Authorization="Bearer $manager"; 'Content-Type'='application/json'} -Body '{"officerId":"officer1"}'
Invoke-RestMethod -Method Post "http://localhost:8182/api/v1/officer/claims/$id/review" -Headers @{Authorization="Bearer $officer"; 'Content-Type'='application/json'} -Body '{"assessmentNotes":"Photos match."}'
Invoke-RestMethod "http://localhost:8181/api/v1/management/summary" -Headers @{Authorization="Bearer $manager"}
```

A call without a token, or with the wrong role, is refused (401 / 403). To make your own users, log in as `admin1` and `POST /api/v1/users`. These exact commands were not run as written; the verified flow is `smoke-test.ps1`. In Windows PowerShell 5.1, `Invoke-WebRequest` was observed to lose the body of a 401 that carries `WWW-Authenticate` (the status code is still right); use `curl.exe` or `HttpClient` to read the error code.

## 12. Build and test results

Recorded from real runs (Windows 11, Java 17.0.16, Maven wrapper, Maven 3.9.9). **The Phase 1 builds and tests were run in a copy of the sources made for the purpose**, not in the repository folder, because your own services were running from the repository's `target/` folders; the copy held the same files as the repository (same file count), and your running services were not touched.

- **Phase 1, the microservices:** `.\mvnw.cmd -f microservices/pom.xml clean test`: **BUILD SUCCESS. Tests run: 580, Failures: 0, Errors: 0, Skipped: 0.** Per module: claims-common 54, user-service 62, claim-service 201, assessment-service 90, system-tests 173. H2 only. Per class, see the Surefire reports and `API_TEST_CASES.md`. For comparison: 325 tests before authentication, 464 after it (five services).
- **Phase 1, the original monolith:** `.\mvnw.cmd test` at the repository root: **BUILD SUCCESS. Tests run: 142, Failures: 0, Errors: 0, Skipped: 0** (16 test classes). Before the stray `UserServiceApplication.java` was removed it also passed, with that class compiled into the monolith's classes. The monolith's `src/`, `pom.xml` and the wrapper show no change in `git status`.
- **Real runnable jars:** `.\mvnw.cmd -f microservices/pom.xml -DskipTests clean package` built the three `*-exec.jar` files. They were started with the real `start-all.ps1 -SkipBuild -UserPort 18185 -ClaimPort 18181 -AssessmentPort 18182` (spare ports, in-memory H2, a JWT secret generated by the script; nothing left in the PowerShell session afterwards) and `smoke-test.ps1` was run against them: **all 81 checks PASS, exit code 0**. This was done twice: first on jars built before an independent code review's fixes (and run twice there, the second time on the same instances, which shows the script can be repeated), then again on **jars rebuilt from the final code** (81 of 81, exit code 0). On those final jars three more things were observed by hand: an administrator reviewing an unassigned claim as `typo.name` or as a claimant got 400 `INVALID_OFFICER` and the claim stayed `SUBMITTED` and unassigned, while naming `officer1` gave 200 and assigned it; a claimant's `PATCH` with a line break in the description gave 200; and the Assessment Service log held one line per administrator request naming the administrator. Also observed on the earlier running jars: `/actuator/health` answered `UP` on all three, `/actuator/env` was 401 without a token; `/v3/api-docs` (titles `User Service API`, `Claim Service API`, `Assessment Service API`) and `/swagger-ui/index.html` answered 200 on all three; the four demo users logged in with `password123` and got `ADMIN`, `CLAIMS_OFFICER`, `MANAGER` and `CLAIMANT` (`expiresIn` 3600); Flyway applied user V1-V2, claim V1-V5 and assessment V1-V2 on start-up; the three logs contained no password, token or bearer string. `stop-all.ps1` stopped only those three processes.
- **Start-up without a secret:** the real `claim-service` jar started with no `JWT_SECRET` exited with the message "claims.security.jwt.secret (environment variable JWT_SECRET) must be set to at least 32 characters; every service of the system needs the same value" and never opened its port.
- **Migrations over old data (H2):** `UserMigrationTest`, `ClaimMigrationTest` and `AssessmentMigrationTest` build a database as it was before Phase 1, fill it with old rows, run the new migration and check the result (roles renamed, statuses and history renamed, columns renamed, settled claims keep their amount and get their settlement, old assessment notes become `NOTE`, constraints reject the old values).
- **Independent review:** after the code was complete, a separate reviewer who had not seen my reasoning read the security-critical code and the migrations and reported nine findings. None was an ownership or visibility hole. Fixed: an administrator could assign a claim to any name by starting its review (now checked, section 2); `PATCH` refused multi-line text that create accepts; a deactivated account answered login measurably faster than a wrong password (now it costs the same password check); `OFFICER_MISMATCH` no longer names the other officer; an answer takes its row lock only after the visibility check; the administrator's name is logged. Documented as limits, not fixed: an administrator's name is not in the history tables, a database connection is held during the call to the Claim Service, a deactivated officer's token works until it expires, and legacy data (old free-text assignees, requests from the old Information database).
- **Before Phase 1 (2026-10-26):** five services, 464 tests passing; real jars on spare ports with `smoke-test.ps1` 47 of 47 checks; the Flyway checksum-mismatch investigation on the local PostgreSQL 17.6 (reproduced on a throwaway in-memory database, fixed with one approved `CREATE DATABASE user_db`; the real User Service jar then validated and applied V1 there and the owner's running Claim Service accepted its tokens; nothing in the Claim Service's database was written by me).
- **NOT TESTED:**
  - **PostgreSQL**: the three Phase 1 migrations, the Assessment Service's database, claim creation and transitions, `nextval`, row locking, `TIMESTAMP WITH TIME ZONE` round trips and the concurrency tests (section 8).
  - **Expired-token rejection against the running jars.** It is covered by unit, MockMvc and system tests (a token whose expiry is in the past is 401 on every service), but it was not sent to the running jars, because the generated secret is deliberately not available to the tester.
  - Clicking through the Swagger UI and its Authorize button in a browser (the documents behind it were checked).
  - Running the services from IntelliJ, and the Claim Service on the port 8787 you used before.
  - Kafka (not implemented), any load or penetration testing, token behaviour under real clock drift between machines.

If you change code, re-run the tests and update these numbers from the new output.

## 13. Assumptions

- `IND` is not supported: `market` is a 2-letter code in this design and the brief names no market, so adding `IND` would be a guess. If India is one of the six, its code goes into `claims.markets.allowed`; the six-market list is therefore still incomplete.
- `market` is a 2-letter upper-case code (for example `SG`, `HK`, `AU`) and `currency` a 3-letter upper-case code. `currency` is not checked against a list; `market` is checked only if `claims.markets.allowed` is set.
- The claimed amount may be any positive value with up to 2 decimals; the currency's own minor-unit rules are not applied.
- A policy number is only checked for its characters and length (3 to 50); its format belongs to the insurer, and it is not checked against any policy system.
- An officer is identified by their **login name** (lower-case, 3 to 50 characters); there is no separate officer table. The officer ID in a request body must therefore be a login name, and a manager's assignment checks it against the User Service.
- One information request can be open per claim at a time (the claim must be `UNDER_REVIEW` to ask, and asking moves it to `MORE_INFO_REQUIRED`).
- "Approval requires the necessary assessment information" is implemented as: a reason and an approved amount within the claimed amount and currency. There is no structured findings record and no recommended amount.
- Anyone may register, and registration only ever creates a CLAIMANT. There is no email check, no approval step and no account recovery.
- The three services are deployed together on a trusted network and started in the order in section 6. Their URLs are configuration, not discovered. They share one `JWT_SECRET`.

## 14. Known limitations

- **Authentication is shared-secret JWT, with these limits:**
  - **The demo users are on out of the box:** four accounts with a well-known password (`password123`), so anyone who can reach the User Service can log in as `admin1` until you set `DEMO_USERS=false`. They are there so a fresh start works, and must be turned off for anything that is not a demo. (The signing secret no longer has a default.)
  - One `JWT_SECRET` signs and checks every token, so any service (or person) that holds it can mint a token with any role, including a SERVICE token. Public-key signing, where services can only verify, is the proper next step and was not done. `start-all.ps1` generates a random secret per run, so a secret is not in any file, but the processes' environment holds it while they run.
  - **No revocation, logout or refresh.** A token is valid until it expires (60 minutes by default). **Changing a user's role or switching their account off does not affect tokens already issued**: they keep their old role and keep working until they expire. A deactivated officer also keeps the claims assigned to them until a manager reassigns them.
  - **No rate limiting or lockout** on login or registration, so passwords can be guessed at the speed the service answers. No password reset, no multi-factor login. Self-registration is open (it only creates a CLAIMANT).
  - **No TLS.** Tokens and passwords travel in clear text unless something in front of the services adds HTTPS.
  - **Swagger UI, `/v3/api-docs` and `/actuator/health` need no token** on any service (health shows only UP or DOWN).
  - The `/internal/v1` routes are protected by the service token only, not by the network; keep the ports private.
  - Visibility is coarse: an officer sees the unassigned queue and their own claims, a manager every claim; there is no team or market scope.
  - **An administrator acting for an officer appears in the history as that officer** (the history stores an officer ID only); the administrator's login name is in the Assessment Service's log, not in a table.
- **The manager's assignment depends on the User Service** (section 3, window 3). If it is down, a manager cannot assign.
- **No API gateway**: clients must know which service and port owns each endpoint (section 2).
- **Synchronous coupling.** Assessment needs the Claim Service for every action that names a claim, and Claim needs the User Service for a manager's assignment. There is no caching, retry, circuit breaker, service discovery or load balancing, and each service runs as a single instance.
- **Cross-service consistency is best effort**, see "Consistency and failure behaviour" in section 3: a failed call stores nothing, but a failure of a service's own database at the very moment after the Claim Service accepted can leave a claim and its history entry or request out of step, and a timeout can hide a success.
- **PostgreSQL integration is only partly tested** (section 8), and the default H2 databases lose all data on restart and are independent of each other. The Phase 1 migrations ran on H2 only. No data migration from the monolith's database exists, and nothing from the old Information Service's database is carried into the Assessment Service.
- **Kafka is not implemented.**
- **Additional information is text only.** There is no document or photo upload.
- Locking was only exercised on H2. Concurrent creation and competing transitions on one claim, two answers to one request, an edit racing the start of a review and two settlements are tested (`ConcurrentTransitionTest` in `claim-service` and `system-tests`, `ClaimUpdateTest`, `InformationRequestTest`), but never on PostgreSQL.
- Assessment is free-text notes plus the decision reason: no structured findings, no recommended amount, and an entry cannot be edited. Claimant edits record which fields changed but not the old and new text.
- The six APAC markets are not named in the brief, so no market list is built in; set `claims.markets.allowed` once they are known.
- No rate limiting, no pagination on the information-request list, and no API versioning beyond `/api/v1`.
- `market` filtering is case-sensitive.
- The original monolith remains in the repository root for reference; it duplicates much of the code, has no authentication and uses the same default port 8181 as the Claim Service.
- This system is **not** secure enough to call production-ready: it is an assessment project with the limits above.
