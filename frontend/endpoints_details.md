# Endpoint details

REST contract of the three existing Spring Boot services, as consumed by this Angular frontend. **The backend is not
changed by this project.**

> This file was empty when the frontend was generated. It was written from the backend source
> (`chubb_Assessment/microservices`: controllers, DTO records, access-rule tables and `API_TEST_CASES.md`). If the
> backend changes, the backend's `API_TEST_CASES.md` stays the authority; update this file and `src/app/models/` to match.

| Service | Port | Browser path (dev proxy) | Owns |
|---|---|---|---|
| User (Auth) | `8185` | `/svc/user/api/v1/...` | login, register, users, roles |
| Claim | `8181` | `/svc/claim/api/v1/...` | claims, assignment, histories, management reports |
| Assessment | `8182` | `/svc/assessment/api/v1/...` | review, assessment history, decision, settlement, information requests |

The browser never calls the ports directly: the backend sends no CORS headers, so the Angular dev server proxies the three
prefixes (see `proxy.conf.json`). There is no API gateway.

## Conventions

- **Auth:** `Authorization: Bearer <accessToken>` on every call except `POST /auth/login`, `POST /auth/register` and
  `GET /actuator/health`. One JWT (HS256, 60 min by default) is accepted by all three services. Added by `jwt.interceptor.ts`.
- **IDs:** `{claimId}` in a path is the public reference (`CLM-2026-000101`), never the numeric `id`. Information requests are
  `IR-2026-000001`.
- **Dates:** `incidentDate` is `yyyy-MM-dd`; every other timestamp is an ISO-8601 instant. **Money** is a JSON number
  with at most 2 decimals plus a 3-letter `currency`; amounts in different currencies are never added.
- **Paging:** `page` is zero-based, `size` 1-100 (default 20). Page body: `{ content, page, size, totalElements, totalPages }`.
- **Who sees which claim** (enforced by the backend, answered as `404 CLAIM_NOT_FOUND` otherwise): a claimant only their own, a claims
  officer the unassigned queue plus their own, a manager and an admin every claim.
- **Officer identity comes from the token.** The frontend never sends `officerId`, `requestedByOfficerId`, `decidedByOfficerId` or
  `settledByOfficerId`; naming another officer is `403 OFFICER_IDENTITY_MISMATCH`.
- **Error body (every 4xx/5xx):** `{ "timestamp", "status", "error", "code", "message", "path" }`.

## Roles

`CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER`, `ADMIN`. No hierarchy: a manager cannot review or decide, an officer cannot read the reports. `ADMIN` is
allowed on every public route in the backend; the frontend deliberately gives an admin only the admin screens.

## Claim lifecycle

```
SUBMITTED --review--> UNDER_REVIEW --information request--> MORE_INFO_REQUIRED
                          ^                                        |
                          +------ last OPEN request answered ------+   (automatic)
UNDER_REVIEW --approve--> APPROVED --settle--> SETTLED      (final)
UNDER_REVIEW --reject---> REJECTED                          (final)
```

Any other move is `409 INVALID_CLAIM_TRANSITION`. A claimant may edit only while `SUBMITTED`.

---

## User Service (`:8185`)

| Method and path | Roles | Request body | Success | Angular method |
|---|---|---|---|---|
| `POST /auth/login` | public | `{ username, password }` | `200 { accessToken, tokenType:"Bearer", expiresIn, username, role }` | `AuthService.login` |
| `POST /auth/register` | public | `{ username, password }` | `201 User` (always `CLAIMANT`) | `AuthService.register` |
| `GET /users/me` | any | - | `200 User` | `UserService.me` |
| `POST /users` | ADMIN | `{ username, password, role }` | `201 User` (the UI offers staff roles only) | `UserService.create` |
| `GET /users?page&size` | ADMIN | - | `200 Page<User>` ordered by username | `UserService.list` |
| `GET /users/{username}` | ADMIN | - | `200 User` | `UserService.get` |
| `PUT /users/{username}/role` | ADMIN | `{ role }` | `200 User` (see the role policy below) | `UserService.changeRole` |
| `PUT /users/{username}/active` | ADMIN | `{ active }` | `200 User` | `UserService.setActive` |

`User` = `{ username, role, active, createdAt }` (never a password or hash). Username: 3-50 chars, lower-case letters, digits, `.` `_` `-`,
starting with a letter or digit. Password: 8-72 **bytes**.

Errors: `401 INVALID_CREDENTIALS` (wrong password, unknown user **or** deactivated account, deliberately identical), `409 USERNAME_TAKEN`,
`409 CANNOT_CHANGE_OWN_ROLE`, `409 CANNOT_DEACTIVATE_SELF`, `404 USER_NOT_FOUND`, `400 INVALID_ROLE`, `400 PASSWORD_TOO_LONG`.

Limits (backend, documented there): a deactivated user's or re-roled user's **existing token keeps working until it expires**; there is no refresh,
revocation, lockout or rate limit.

**Role policy (frontend only).** The backend accepts any role on `POST /users` and `PUT /users/{username}/role` (it refuses only your own account, and
returns no "self-registered" marker). The Admin UI narrows that: a claimant is never moved to a staff role, a staff account never becomes a claimant,
and Create user offers `CLAIMS_OFFICER`, `MANAGER` and `ADMIN` only. The rule is `src/app/utils/role-policy.ts`; see [`docs/ROLE_POLICY.md`](docs/ROLE_POLICY.md).

## Claim Service (`:8181`)

| Method and path | Roles | Request | Success | Angular method |
|---|---|---|---|---|
| `POST /claims` | CLAIMANT | `CreateClaimRequest` | `201 Claim` | `ClaimService.create` |
| `GET /claims` | CLAIMANT, CLAIMS_OFFICER, MANAGER | query: `status, market, claimType, assignedOfficerId, assigned, page, size` | `200 Page<ClaimSummary>` newest first | `ClaimService.list` |
| `GET /claims/{claimId}` | claimant (own), officer (visible), manager | - | `200 Claim` | `ClaimService.get` |
| `PATCH /claims/{claimId}` | CLAIMANT (own) | `UpdateClaimRequest` (only changed fields) | `200 Claim` | `ClaimService.update` |
| `GET /claims/{claimId}/history` | as `GET /claims/{id}` | - | `200 ClaimHistoryEntry[]` | `ClaimService.history` |
| `GET /claims/{claimId}/assignments` | as above | - | `200 ClaimAssignmentEntry[]` | `ClaimService.assignments` |
| `GET /claims/{claimId}/updates` | as above | - | `200 ClaimUpdateEntry[]` | `ClaimService.updates` |
| `GET /officer/claims` | CLAIMS_OFFICER, MANAGER | same query as `GET /claims` | `200 Page<ClaimSummary>` | `ClaimService.listStaff` |
| `POST /officer/claims/{claimId}/assign` | CLAIMS_OFFICER (self only), MANAGER | `{ officerId }` | `200 { claimId, status, assignedOfficerId, updatedAt }` | `ClaimService.assign` |
| `GET /management/summary` | MANAGER | - | `200 ManagementSummary` | `ManagementService.summary` |
| `GET /management/officers` | MANAGER | - | `200 OfficerMetrics` | `ManagementService.officers` |

**CreateClaimRequest** - all required: `claimantName` (max 200), `claimantEmail` (valid, max 254), `policyNumber` (3-50 chars: letters, digits, `/` `_` `-`,
starting with a letter or digit), `market` (2 upper-case letters), `claimType` (`MOTOR` | `PROPERTY`), `incidentDescription` (max 2000), `incidentDate`
(not in the future), `claimedAmount` (> 0, 2 decimals), `currency` (3 upper-case letters). `claimId`, `claimantId` and `status` are server-set.

**UpdateClaimRequest** - optional: `claimantName, claimantEmail, policyNumber, claimType, incidentDescription, incidentDate, claimedAmount`. Market, currency, status
and assignment cannot be changed. Empty body: `400 NO_FIELDS_TO_UPDATE`; after review starts: `409 CLAIM_NOT_EDITABLE`.

**Claim** = `{ id, claimId, claimantId, claimantName, claimantEmail, policyNumber, market, claimType, incidentDescription, incidentDate, claimedAmount, approvedAmount|null,
currency, status, assignedOfficerId|null, decisionReason|null, decidedAt|null, settledAt|null, createdAt, updatedAt }`.

**ClaimSummary** (list row) = `{ id, claimId, claimantId, claimantName, policyNumber, market, claimType, claimedAmount, approvedAmount|null, currency, status, assignedOfficerId|null, createdAt }`.

Filters: `assigned=false` = unassigned, `assigned=true` = assigned; `assigned=false` with `assignedOfficerId` is `400 CONFLICTING_FILTERS`.

**Assignment rules:** a manager may name any *active* claims officer (`400 INVALID_OFFICER` otherwise, `503` if the User Service is down); an officer may only take a claim
for themselves (`403 ASSIGNMENT_NOT_PERMITTED`). Assignment is allowed until `SETTLED` or `REJECTED`.

**ManagementSummary** = `{ generatedAt, liabilityNote, outstandingClaimCount, outstandingLiabilityByCurrency: [{ currency, claimCount, estimatedLiability }], countsByStatus: { STATUS: n } }`.
A not-yet-decided claim counts at its claimed amount, an `APPROVED` one at its approved amount, a settled or rejected one not at all. "Simple estimate, not an actuarial reserve."

**OfficerMetrics** = `{ generatedAt, note, officers: [{ officerId, openClaimCount, openClaimsByStatus, openClaimedAmountByCurrency: [{ currency, claimCount, amount }], decidedCount, approvedCount, settledCount, rejectedCount, approvedAmountByCurrency }] }`.
There is no time-based metric.

## Assessment Service (`:8182`)

| Method and path | Roles | Request | Success | Angular method |
|---|---|---|---|---|
| `POST /officer/claims/{claimId}/review` | CLAIMS_OFFICER | `{ assessmentNotes? }` (max 2000) | `200 WorkflowResponse` | `AssessmentService.startReview` |
| `GET /officer/claims/{claimId}/assessments` | CLAIMS_OFFICER | - | `200 AssessmentEntry[]` oldest first | `AssessmentService.assessments` |
| `POST /officer/claims/{claimId}/decision` | CLAIMS_OFFICER | `{ decision, decisionReason, approvedAmount?, currency }` | `200 DecisionResponse` | `AssessmentService.decide` |
| `POST /officer/claims/{claimId}/settlement` | CLAIMS_OFFICER | `{ settlementNote? }` (max 2000) | `200 SettlementResponse` | `AssessmentService.settle` |
| `POST /officer/claims/{claimId}/information-requests` | CLAIMS_OFFICER | `{ question }` (max 2000) | `201 InformationRequest` | `AssessmentService.requestInformation` |
| `GET /claims/{claimId}/information-requests` | claimant (own), officer (visible), manager | - | `200 InformationRequest[]` | `AssessmentService.informationRequests` |
| `POST /claims/{claimId}/information` | CLAIMANT (own) | `{ informationRequestId, answer }` (max 4000) | `200 AnswerResponse` | `AssessmentService.answerInformation` |

- **Review:** `SUBMITTED` or `MORE_INFO_REQUIRED` (only when no request is `OPEN`) to `UNDER_REVIEW`; reviewing an unassigned claim also assigns it to the officer.
- **Decision:** `UNDER_REVIEW` only, by the assigned officer. `APPROVED` needs `approvedAmount` > 0 and <= claimed, in the claim's currency; `REJECTED` must carry no amount. Both need a reason.
  Errors: `400 INVALID_APPROVED_AMOUNT`, `400 CURRENCY_MISMATCH`, `409 OFFICER_MISMATCH`, `409 INVALID_CLAIM_TRANSITION`.
- **Settlement:** `APPROVED` to `SETTLED` by the assigned officer. A rejected claim can never be settled.
- **Information requests** are text only (no upload). Answering the last `OPEN` request returns the claim to `UNDER_REVIEW` automatically; a second answer is `409 INFORMATION_REQUEST_ALREADY_ANSWERED`.
- The assessment history is **officer-only**: managers and claimants get `403`.

Shapes: `AssessmentEntry` = `{ claimId, officerId, kind: NOTE|APPROVED|REJECTED|SETTLED, notes|null, createdAt }`.
`InformationRequest` = `{ informationRequestId, claimId, question, status: OPEN|ANSWERED, requestedByOfficerId, createdAt, answer|null, answeredAt|null }`.
`DecisionResponse` = `{ claimId, status, decisionReason, approvedAmount|null, currency, decidedByOfficerId, decidedAt, updatedAt }`.
`SettlementResponse` = `{ claimId, status, approvedAmount|null, currency, settledByOfficerId, settledAt, updatedAt }`.

## Request Information dialog: what each failure shows

`POST /officer/claims/{claimId}/information-requests` fails inside the dialog (no extra toast); the dialog stays open so a typed question is never lost.

| HTTP | Shown | Then |
|---|---|---|
| 400 | the backend's validation message under the **Question** field | the person edits and retries |
| 401 | nothing here: the session ends and the app goes to `/login?returnUrl=...` | sign in again |
| 403 | "Access denied. You do not have permission to request information on this claim." | sending is disabled; Cancel reloads the workspace |
| 404 | "Claim not found. It may have been removed, or it is assigned to another officer." | sending is disabled; Cancel reloads the workspace |
| 409 | the backend's business message (for example the claim is no longer under review) | sending is disabled; Cancel reloads the workspace |
| 503 | "The service is temporarily unavailable. Please try again in a few moments." | the person can try again |
| network | "Cannot reach the server. Check that the backend services are running." | the person can try again |

## Error codes the UI reacts to

| HTTP | `code` | Frontend behaviour |
|---|---|---|
| 401 | `INVALID_CREDENTIALS` | shown inline on the login form |
| 401 | `UNAUTHENTICATED` | session ended, redirect to `/login?returnUrl=...` |
| 403 | `FORBIDDEN`, `OFFICER_IDENTITY_MISMATCH`, `ASSIGNMENT_NOT_PERMITTED` | toast with the backend message; session kept |
| 404 | `CLAIM_NOT_FOUND`, `USER_NOT_FOUND` | "not found" empty state (a claim outside the caller's scope looks identical) |
| 409 | `INVALID_CLAIM_TRANSITION`, `OFFICER_MISMATCH`, `CLAIM_NOT_EDITABLE`, `USERNAME_TAKEN`, ... | toast with the backend message (username-taken also marks the field) |
| 400 | `VALIDATION_FAILED`, `INVALID_OFFICER`, `INVALID_APPROVED_AMOUNT`, ... | toast with the backend message; forms are validated first with the same rules |
| 502 / 503 | `DOWNSTREAM_ERROR`, `DOWNSTREAM_UNAVAILABLE` | toast: a backend service is unavailable |
| 0 | - | toast: cannot reach the server |

Service-to-service routes (`/internal/v1/**`) accept only a SERVICE token and are never called by the frontend.

---

# Endpoint to screen mapping

Which screen each endpoint serves, and where it lives in the app. The mapping itself was supplied with the project documents; the **Route**
column is what is implemented. (The supplied mapping ends at the heading "View Information"; the last two rows below are inferred from the
Assessment Service's information endpoints.)

## User Service

| Endpoint | Screen | Route |
|---|---|---|
| `POST /auth/login` | Login page | `/login` |
| `POST /auth/register` | Register page | `/register` |
| `GET /users/me` | Profile page | `/profile` |
| `POST /users` | Admin > Create User (staff roles only) | `/admin/users/new` |
| `GET /users` | Admin > User Management | `/admin/users` |
| `GET /users/{username}` | Admin > User Detail | `/admin/users/:username` |
| `PUT /users/{username}/role` | Admin > Edit User (staff accounts only; a claimant gets an explanation instead) | `/admin/users/:username/edit` |
| `PUT /users/{username}/active` | Admin > User Management (row action) and User Detail | `/admin/users`, `/admin/users/:username` |

## Claim Service

| Endpoint | Screen | Route |
|---|---|---|
| `POST /claims` | Claimant > Create Claim | `/claimant/new-claim` |
| `GET /claims` | Claimant > My Claims; Manager > Claims | `/claimant/claims`, `/manager/claims` |
| `GET /claims/{claimId}` | Claim Detail page (per role) | `/claimant/claims/:id`, `/officer/claims/:id` (Review Workspace), `/manager/claims/:id` |
| `PATCH /claims/{claimId}` | Claim Edit page, **only while `SUBMITTED`** (otherwise the page explains why not) | `/claimant/claims/:id/edit` |
| `GET /claims/{claimId}/history` | Claim Detail > **Status history** tab (the status timeline) | all three Claim Detail pages |
| `GET /claims/{claimId}/assignments` | Claim Detail > **Assignment history** tab | all three Claim Detail pages |
| `GET /claims/{claimId}/updates` | Claim Detail > **Update history** tab | all three Claim Detail pages |
| `GET /officer/claims` | Officer Dashboard, Claims Queue, Assigned Claims; Manager Dashboard (waiting-to-assign count) and Assignment Dashboard | `/officer/dashboard`, `/officer/queue`, `/officer/assigned`, `/manager/assignments` |
| `POST /officer/claims/{claimId}/assign` | Manager > Assignment Dashboard (assign / reassign); also the officer's "Take claim" in the queue | `/manager/assignments`, `/officer/queue` |
| `GET /management/summary` | Manager Dashboard (Outstanding Claims, Liability By Currency, Claim Count) and Liability Reports | `/manager/dashboard`, `/manager/liability` |
| `GET /management/officers` | Manager > Officer Metrics (Workload, Approvals, Settlements, Rejections); workload chart on the dashboard; capacity chips in the assign dialog | `/manager/officers` |

## Assessment Service

| Endpoint | Screen | Visible when | Route |
|---|---|---|---|
| `POST /officer/claims/{claimId}/review` | Officer > Review Workspace > Start review (also "Resume review" when a claim is waiting for information but nothing is still open) | `SUBMITTED` | `/officer/claims/:id` |
| `GET /officer/claims/{claimId}/assessments` | Officer > Assessment History (and a preview in the Review Workspace) | any, officer only | `/officer/claims/:id/assessments`, `/officer/assessments` |
| `POST /officer/claims/{claimId}/decision` (`APPROVED`) | Officer > Review Workspace > **Review actions** panel (Approve) | `UNDER_REVIEW`, assigned to me | `/officer/claims/:id` |
| `POST /officer/claims/{claimId}/decision` (`REJECTED`) | Officer > Review Workspace > **Review actions** panel (Reject) | `UNDER_REVIEW`, assigned to me | `/officer/claims/:id` |
| `POST /officer/claims/{claimId}/settlement` | Officer > **Settlement page** | `APPROVED`, assigned to me | `/officer/claims/:id/settlement` |
| `POST /officer/claims/{claimId}/information-requests` | Officer > **Request Additional Information dialog**, opened by the **Request Information** button that sits beside Approve and Reject in the Review actions panel (shown to a `CLAIMS_OFFICER` only). Body `{ "question" }`: required, max 2000. On success: toast "Information request sent successfully", the claim reloads as `MORE_INFO_REQUIRED` and the Information Requests table refreshes. Errors are explained inside the dialog (see below) | `UNDER_REVIEW`, assigned to me | `/officer/claims/:id` |
| `GET /claims/{claimId}/information-requests` | View Information: Claimant > Information Requests and Claim Detail (cards, with the answer form); in the Officer Review Workspace and the Manager claim overview as the read-only **Information Requests** table (Request ID, Question, Status, Created, Answer, Answered; empty state "No information requests") | `MORE_INFO_REQUIRED` for the claimant's answer form | `/claimant/information-requests`, Claim Detail pages |
| `POST /claims/{claimId}/information` | Claimant > Information Requests (answer a request); the claim returns to `UNDER_REVIEW` once the last open request is answered | request is `OPEN` | `/claimant/information-requests`, `/claimant/claims/:id` |
