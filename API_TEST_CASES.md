# API Test Cases

Test cases for all 13 implemented endpoints of the Chubb APAC Claims backend. They were written from the actual controllers, DTOs, services and exception handler, not from the planned API list.

- **Base URL:** `http://localhost:8181` (`server.port=8181` in `application.properties`)
- **Swagger UI:** `http://localhost:8181/swagger-ui/index.html` (`/swagger-ui.html` redirects there)
- **Headers:** `Content-Type: application/json` on every `POST`. No other headers are needed. There is **no authentication or authorization**, so no token or API key is required.
- **IDs:** `{claimId}` in a path is the public business ID (for example `CLM-2026-000101`), never the internal numeric `id`. Sample IDs and timestamps below are illustrative; always use the values the running app returns.
- **Error body (all 4xx/5xx):** `{ "timestamp", "status", "error", "code", "message", "path" }`. No stack traces.

## 1. How the cases are verified

| Kind | What it is | Status |
|---|---|---|
| **Automated** | MockMvc tests in `src/test/java/com/chubb/claims` run against the full Spring context with H2 (PostgreSQL mode) | Last run: see section 12 |
| **Manual (Swagger)** | The end-to-end flow in section 11 | Run via an equivalent PowerShell script, not through the Swagger UI itself (see section 12) |

Each case below lists the automated test class that covers it. A case marked "(manual only)" has no dedicated automated test.

## 2. Error codes used in the cases

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation failed on a body (including an officer ID that is the placeholder `string`), or a constrained query parameter was out of range |
| 400 | `MALFORMED_REQUEST` | Body missing, invalid JSON, unknown enum value or unparseable date/number |
| 400 | `INVALID_PARAMETER` | Query parameter has the wrong type (for example `page=abc`, `status=NOPE`) |
| 400 | `INVALID_MARKET` | `market` is not in the configured `claims.markets.allowed` list (only when the list is set) |
| 400 | `CONFLICTING_FILTERS` | `assigned=false` combined with `assignedOfficerId` |
| 400 | `INVALID_SETTLED_AMOUNT` | Settled amount missing/not positive/over the claimed amount, or present on a rejection |
| 400 | `CURRENCY_MISMATCH` | Decision currency differs from the claim currency |
| 404 | `CLAIM_NOT_FOUND` | No claim with that `claimId` |
| 404 | `INFORMATION_REQUEST_NOT_FOUND` | No such information request for that claim |
| 404 | `NOT_FOUND` | Unknown route |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for the route |
| 409 | `INVALID_CLAIM_TRANSITION` | The claim's current status does not allow this action |
| 409 | `OFFICER_MISMATCH` | Review started by an officer other than the assigned one |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not JSON |
| 500 | `INTERNAL_ERROR` | Unexpected failure (no automated test forces this) |

## 3. Claim lifecycle (what the cases rely on)

```text
SUBMITTED --review--> UNDER_REVIEW --information-request--> AWAITING_INFORMATION
                          ^                                          |
                          +------ last OPEN request answered --------+   (automatic)
UNDER_REVIEW --decision--> SETTLED | REJECTED   (terminal)
```

- `assign` is allowed in any non-terminal state and does not change the status.
- `review` works from `SUBMITTED`, and from `AWAITING_INFORMATION` only when no request is `OPEN`.
- Answering the last `OPEN` information request resumes the claim to `UNDER_REVIEW` automatically.

## 4. Endpoint 1 - Create a claim

`POST /api/v1/claims` - automated: `ClaimCreationTest`, `EndpointContractTest`

Request fields: `claimantName` (required, max 200), `claimantEmail` (required, valid email, max 254), `market` (2 upper-case letters), `claimType` (`MOTOR` or `PROPERTY`), `description` (required, max 2000), `incidentDate` (`yyyy-MM-dd`, not in the future), `claimedAmount` (positive, at most 2 decimals), `currency` (3 upper-case letters). The client never sends `id` or `claimId`.

Base request used below:

```json
{
  "claimantName": "Mei Tan",
  "claimantEmail": "mei.tan@example.com",
  "market": "SG",
  "claimType": "MOTOR",
  "description": "Rear bumper damaged in a low-speed collision.",
  "incidentDate": "2026-10-12",
  "claimedAmount": 2400.00,
  "currency": "INR"
}
```

| ID | Scenario | Change to the base request | Expected |
|---|---|---|---|
| TC-CRE-01 | Valid claim | none | **201**. Body has `id` (number), `claimId` matching `CLM-\d{4}-\d{6}`, `status` `SUBMITTED`, `assignedOfficerId` null, `createdAt`, `updatedAt`. |
| TC-CRE-02 | Two claims get different IDs | create twice | Two different `claimId` values, increasing; `id` differs from `claimId`. |
| TC-CRE-03 | Concurrent creation | many parallel creates | All `claimId` values unique (`ClaimCreationTest`). |
| TC-CRE-04 | Client tries to set IDs/status | add `"claimId":"CLM-HACK-1","status":"SETTLED"` | **201**; extra fields ignored; generated `claimId` is not `CLM-HACK-1`; status `SUBMITTED`. |
| TC-CRE-05 | Amount zero or negative | `claimedAmount` `0` or `-5.00` | **400** `VALIDATION_FAILED`. |
| TC-CRE-06 | Too many decimals | `claimedAmount` `10.001` | **400**. |
| TC-CRE-07 | Bad currency | `"currency":"sgd"` or missing | **400**. |
| TC-CRE-08 | Bad market | `"market":"Singapore"` | **400**. |
| TC-CRE-09 | Unknown claim type | `"claimType":"BOAT"` | **400** `MALFORMED_REQUEST`. |
| TC-CRE-10 | Bad email | `"claimantEmail":"not-an-email"` | **400**. |
| TC-CRE-11 | Future incident date | `"incidentDate":"2999-01-01"` | **400**. |
| TC-CRE-12 | Unparseable date | `"incidentDate":"12/10/2026"` | **400**. |
| TC-CRE-13 | Blank name / over-long description | `"claimantName":"   "`, 2001-char description | **400**. |
| TC-CRE-14 | Amount is not a number | `"claimedAmount":"lots"` | **400**. |
| TC-CRE-15 | Empty object | `{}` | **400** `VALIDATION_FAILED`; message names the missing fields. |
| TC-CRE-16 | Malformed JSON / no body | `{ this is not json` / empty | **400** `MALFORMED_REQUEST`. |
| TC-CRE-17 | Wrong content type | `Content-Type: text/plain` | **415** `UNSUPPORTED_MEDIA_TYPE`. |
| TC-CRE-18 | Nothing saved on failure | any 400 above | `claims` table stays empty. |
| TC-CRE-19 | Market outside the configured allow-list | `claims.markets.allowed=SG,HK` (test values), `"market":"AU"` | **400** `INVALID_MARKET`; nothing saved. Listed markets give **201**. (`MarketValidationTest`) |
| TC-CRE-20 | No allow-list configured (default) | `"market":"ZZ"` | **201**; any 2-letter upper-case code is still accepted. (`DefaultMarketTest`) |

Sample `201` response:

```json
{
  "id": 1,
  "claimId": "CLM-2026-000101",
  "claimantName": "Mei Tan",
  "claimantEmail": "mei.tan@example.com",
  "market": "SG",
  "claimType": "MOTOR",
  "description": "Rear bumper damaged in a low-speed collision.",
  "incidentDate": "2026-10-12",
  "claimedAmount": 2400.00,
  "currency": "INR",
  "status": "SUBMITTED",
  "assignedOfficerId": null,
  "decisionReason": null,
  "settledAmount": null,
  "decidedAt": null,
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

`GET /api/v1/claims/{claimId}` - automated: `ClaimCreationTest`, `ClaimWorkflowTest`, `EndpointContractTest`

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-GET-01 | Existing claim | `GET /api/v1/claims/CLM-2026-000101` | **200**; same body shape as the create response, same `claimId`, current `status`. |
| TC-GET-02 | `claimId` is stable | GET after assign/review | **200**; same `claimId`, updated `status`. |
| TC-GET-03 | After a decision | GET after settle | **200**; `status` `SETTLED`, `decisionReason`, `settledAmount`, `decidedAt` populated. |
| TC-GET-04 | Unknown claim | `GET /api/v1/claims/CLM-2026-999999` | **404** `CLAIM_NOT_FOUND`; `path` echoes the URL; no stack trace. |
| TC-GET-05 | Internal numeric id in the public route | `GET /api/v1/claims/1` | **404** (the route only accepts `claimId`). |
| TC-GET-06 | Wrong method | `PUT` or `DELETE` on the same URL | **405** `METHOD_NOT_ALLOWED`. |

## 6. Endpoint 3 - Search officer workload

`GET /api/v1/officer/claims` - automated: `OfficerSearchTest`, `UnassignedFilterTest`, `EndpointContractTest`

Query parameters (all optional): `status`, `market` (exact, case-sensitive match), `claimType`, `assignedOfficerId`, `assigned` (`true` = has an officer, `false` = unassigned, i.e. `assigned_officer_id IS NULL`), `page` (default `0`, min `0`), `size` (default `20`, min `1`, max `100`). Results are newest first. Filtering and paging run in SQL.

Seed used by `OfficerSearchTest`: SG MOTOR `SUBMITTED`; SG PROPERTY `UNDER_REVIEW` (OFF-1); AU MOTOR `SUBMITTED` assigned to OFF-2.

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-SRCH-01 | No filters | `GET /api/v1/officer/claims` | **200**; `page` 0, `size` 20, `totalElements` 3, newest first. |
| TC-SRCH-02 | Filter by status | `?status=UNDER_REVIEW` | **200**; only the matching claim. |
| TC-SRCH-03 | Filter by market / type / officer | `?market=SG`, `?claimType=MOTOR`, `?assignedOfficerId=OFF-2` | **200**; only matching claims. |
| TC-SRCH-04 | Combined filters | `?status=SUBMITTED&market=SG` | **200**; both filters applied (AND). |
| TC-SRCH-05 | Pagination in the database | `?size=1&page=1` | **200**; one item, correct `totalElements` and `totalPages`. |
| TC-SRCH-06 | Summary item fields | any | Items have `id`, `claimId`, `claimantName`, `market`, `claimType`, `claimedAmount`, `currency`, `status`, `assignedOfficerId`, `createdAt` and **no** `description`. |
| TC-SRCH-07 | Bad enum value | `?status=NOPE`, `?claimType=BOAT` | **400** `INVALID_PARAMETER`. |
| TC-SRCH-08 | Bad paging values | `?page=-1`, `?size=0`, `?size=101`, `?page=abc` | **400**. `?size=100` is **200**. |
| TC-SRCH-10 | Unassigned claims | `?assigned=false` | **200**; only claims with `assignedOfficerId` null, newest first. (`UnassignedFilterTest`) |
| TC-SRCH-11 | Assigned claims | `?assigned=true` | **200**; only claims with an officer. `?assigned=true&assignedOfficerId=OFF-1` is allowed. |
| TC-SRCH-12 | Unassigned combined with other filters and paging | `?assigned=false&market=AU`, `?assigned=false&status=UNDER_REVIEW`, `?assigned=false&size=1` | **200**; AND semantics, correct `totalElements` and `totalPages`. A claim leaves the unassigned list once assigned. |
| TC-SRCH-13 | Invalid `assigned` value | `?assigned=maybe` | **400** `INVALID_PARAMETER`. |
| TC-SRCH-14 | Contradictory filters | `?assigned=false&assignedOfficerId=OFF-1` | **400** `CONFLICTING_FILTERS`. |
| TC-SRCH-15 | Empty results | `?assigned=true&market=ZZ`, or `?assigned=false` once every claim is assigned | **200**; empty `content`, `totalElements` 0, `totalPages` 0. (`UnassignedFilterTest`) |
| TC-SRCH-09 | No match | `?market=ZZ` | **200**; empty `content`, `totalElements` 0 (covered through TC-SRCH-15). |

Sample response:

```json
{
  "content": [
    {
      "id": 1,
      "claimId": "CLM-2026-000101",
      "claimantName": "Mei Tan",
      "market": "SG",
      "claimType": "MOTOR",
      "claimedAmount": 2400.00,
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

Unassigned-claims example: `GET /api/v1/officer/claims?assigned=false` returns the same page shape, and every item has `"assignedOfficerId": null` (the sample above is such a response). `?assigned=false&assignedOfficerId=OFF-1` returns:

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

## 7. Endpoints 4 and 5 - Assign an officer, start/resume review

`POST /api/v1/officer/claims/{claimId}/assign` and `POST /api/v1/officer/claims/{claimId}/review` - automated: `ClaimWorkflowTest`, `ClaimDomainTest`, `EndpointContractTest`

Request (both): `{ "officerId": "OFF-1007" }`. `officerId` is required, not blank, max 50 characters.

| ID | Scenario | Request | Expected |
|---|---|---|---|
| TC-ASG-01 | Assign a submitted claim | `assign` with `OFF-1007` | **200** `{claimId, status:"SUBMITTED", assignedOfficerId:"OFF-1007", updatedAt}`. |
| TC-ASG-02 | Reassign before review | `assign` with `OFF-2` | **200**; `assignedOfficerId` `OFF-2`. |
| TC-ASG-03 | Blank / over-long officer | `""`, `"   "`, 51 characters | **400**; claim unchanged. |
| TC-ASG-04 | Empty / malformed body, wrong content type | `{}` / bad JSON / `text/plain` | **400** / **400** / **415**. |
| TC-ASG-05 | Unknown claim | `assign` on `CLM-2026-999999` | **404** `CLAIM_NOT_FOUND`. |
| TC-ASG-06 | Terminal claim | `assign` on a settled/rejected claim | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-ASG-07 | Wrong method | `GET .../assign` | **405**. |
| TC-REV-01 | Start review | `review` on a `SUBMITTED` claim | **200** `{status:"UNDER_REVIEW", assignedOfficerId}`; officer is assigned if none was. |
| TC-REV-02 | Review twice | `review` again | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-REV-03 | Different officer than assigned | claim assigned to `OFF-1`, review by `OFF-2` | **409** `OFFICER_MISMATCH`. |
| TC-REV-04 | Resume while a request is OPEN | claim `AWAITING_INFORMATION`, call `review` | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-REV-05 | Terminal claim | `review` on a decided claim | **409**. |
| TC-REV-06 | Validation / unknown claim | blank officer / unknown `claimId` | **400** / **404**. |
| TC-REV-07 | Explicit resume with nothing OPEN | domain rule only (cannot occur through the API, because answering resumes automatically) | Covered by `ClaimDomainTest`. |

## 8. Endpoints 6, 7 and 8 - Information requests

Automated: `ClaimWorkflowTest`, `EndpointContractTest`

### Endpoint 6 - `POST /api/v1/officer/claims/{claimId}/information-requests`

Request: `{ "question": "Please provide the repair estimate.", "requestedByOfficerId": "OFF-1007" }` (`question` max 2000, officer max 50, both required).

| ID | Scenario | Expected |
|---|---|---|
| TC-IRQ-01 | Claim is `UNDER_REVIEW` | **201** `{informationRequestId:"IR-2026-000001", claimId, question, status:"OPEN", requestedByOfficerId, createdAt, answer:null, answeredAt:null}`; claim becomes `AWAITING_INFORMATION`. |
| TC-IRQ-02 | Claim is `SUBMITTED` | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-IRQ-03 | Claim already `AWAITING_INFORMATION` (second request) | **409**. |
| TC-IRQ-04 | Claim is terminal | **409**. |
| TC-IRQ-05 | Blank question / `{}` / bad JSON | **400**. |
| TC-IRQ-06 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |

### Endpoint 7 - `GET /api/v1/claims/{claimId}/information-requests`

| ID | Scenario | Expected |
|---|---|---|
| TC-ILS-01 | Claim with one request | **200**; array with that request, `status` `OPEN`, then `ANSWERED` after endpoint 8. |
| TC-ILS-02 | Claim with no requests | **200**; `[]`. |
| TC-ILS-03 | Requests are per claim | Claim B does not list claim A's requests. |
| TC-ILS-04 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |

### Endpoint 8 - `POST /api/v1/claims/{claimId}/information`

Request: `{ "informationRequestId": "IR-2026-000001", "answer": "The repair estimate is 2,200 INR." }` (answer max 4000). Text only; no file upload exists.

| ID | Scenario | Expected |
|---|---|---|
| TC-ANS-01 | Answer the only OPEN request | **200** `{informationRequestId, claimId, status:"ANSWERED", answer, answeredAt}`; claim returns to `UNDER_REVIEW` automatically. |
| TC-ANS-02 | Unknown `informationRequestId` | **404** `INFORMATION_REQUEST_NOT_FOUND`. |
| TC-ANS-03 | Request belongs to a different claim | **404** `INFORMATION_REQUEST_NOT_FOUND`. |
| TC-ANS-04 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-ANS-05 | Answer the same request twice | **409** (claim already resumed). |
| TC-ANS-06 | Answer after the claim was decided | **409**. |
| TC-ANS-07 | Blank answer / `{}` / bad JSON | **400**. |
| TC-ANS-08 | Claim stays `AWAITING_INFORMATION` until every OPEN request is answered | Only reachable in the domain layer: the API allows one open request at a time (TC-IRQ-03). |

## 9. Endpoint 9 - Record a decision

`POST /api/v1/officer/claims/{claimId}/decision` - automated: `ClaimWorkflowTest`, `EndpointContractTest`

Request fields: `decision` (`SETTLED` or `REJECTED`), `decisionReason` (required, max 2000), `settledAmount` (at most 2 decimals), `currency` (3 upper-case letters, must equal the claim currency), `decidedByOfficerId` (required, max 50).

```json
{ "decision": "SETTLED", "decisionReason": "Repair estimate approved.", "settledAmount": 2200.00, "currency": "INR", "decidedByOfficerId": "OFF-1007" }
```

| ID | Scenario | Expected |
|---|---|---|
| TC-DEC-01 | Settle a claim `UNDER_REVIEW` | **200** `{claimId, status:"SETTLED", decisionReason, settledAmount:2200.00, currency, decidedByOfficerId, decidedAt, updatedAt}`. |
| TC-DEC-02 | Reject a claim `UNDER_REVIEW` (no `settledAmount`) | **200**, `status` `REJECTED`, no `settledAmount`. |
| TC-DEC-03 | Settle exactly the claimed amount | **200**. |
| TC-DEC-04 | Second decision on a decided claim | **409** `INVALID_CLAIM_TRANSITION`; first decision kept. |
| TC-DEC-05 | Decision on a `SUBMITTED` or `AWAITING_INFORMATION` claim | **409** `INVALID_CLAIM_TRANSITION`. |
| TC-DEC-06 | `settledAmount` zero or negative | **400**. |
| TC-DEC-07 | `settledAmount` above the claimed amount | **400** `INVALID_SETTLED_AMOUNT`. |
| TC-DEC-08 | Settle without `settledAmount` | **400**. |
| TC-DEC-09 | Reject with a `settledAmount` | **400** `INVALID_SETTLED_AMOUNT`. |
| TC-DEC-10 | Currency differs from the claim | **400** `CURRENCY_MISMATCH`. |
| TC-DEC-11 | `decision` not `SETTLED`/`REJECTED` (for example `UNDER_REVIEW`) | **400**. |
| TC-DEC-12 | Blank reason / `{}` / bad JSON | **400**. |
| TC-DEC-13 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-DEC-14 | Failed attempts do not change the claim | Claim still `UNDER_REVIEW` after the 400 cases. |
| TC-DEC-15 | Wrong method | `GET .../decision` -> **405**. |

## 10. Endpoint 10 - Management summary

`GET /api/v1/management/summary` - automated: `ManagementSummaryTest`, `EndpointContractTest`

| ID | Scenario | Expected |
|---|---|---|
| TC-SUM-01 | Empty database | **200**; `outstandingClaimCount` 0, empty `outstandingLiabilityByCurrency`, every status present with count 0. |
| TC-SUM-02 | Mixed claims and currencies | Terminal claims excluded from outstanding figures; liability reported per currency and never added across currencies; `claimCount` per currency correct. |
| TC-SUM-03 | `AWAITING_INFORMATION` claim | Counted as outstanding and included in liability. |
| TC-SUM-04 | After a decision | Decided claim moves to `SETTLED`/`REJECTED` in `countsByStatus` and leaves the liability. |
| TC-SUM-05 | Exact decimals | 0.10 + 0.20 INR gives 0.30 INR; USD is a separate line. |
| TC-SUM-06 | Labelling | `liabilityNote` says it is a simple estimate and "Not an actuarial reserve". |
| TC-SUM-07 | Wrong method | `POST` -> **405**. |

Sample response:

```json
{
  "generatedAt": "2026-10-18T07:10:36Z",
  "liabilityNote": "Simple estimate: sum of claimed amounts of non-terminal claims, grouped by currency. Not an actuarial reserve.",
  "outstandingClaimCount": 2,
  "outstandingLiabilityByCurrency": [
    { "currency": "AUD", "claimCount": 1, "estimatedLiability": 5200.00 },
    { "currency": "INR", "claimCount": 1, "estimatedLiability": 1800.00 }
  ],
  "countsByStatus": { "SUBMITTED": 1, "UNDER_REVIEW": 1, "AWAITING_INFORMATION": 0, "SETTLED": 4, "REJECTED": 1 }
}
```

## 10a. Endpoint 11 - Per-officer workload and performance

`GET /api/v1/management/officers` - automated: `OfficerMetricsTest`

No parameters. Built from existing columns only (no new table): open workload comes from `assigned_officer_id` and `status` of non-terminal claims; performance comes from `decided_by_officer_id`, the decision outcome and `settled_amount`. Both aggregates are `GROUP BY` queries in the database. Officers are sorted by `officerId`. Amounts are grouped by currency and never added across currencies.

Limits: open workload is by the **current** assignee (there is no reassignment history), and there is no time-based metric (for example time to decision) because the data model has no timestamp for when review started.

| ID | Scenario | Expected |
|---|---|---|
| TC-OFF-01 | Empty database | **200**; `officers` is `[]`; `note` and `generatedAt` present. |
| TC-OFF-02 | Only unassigned claims exist | **200**; `officers` is `[]`. |
| TC-OFF-03 | Two officers with open, settled and rejected claims in several currencies | Per officer: `openClaimCount`, `openClaimsByStatus` (all three open statuses, zero-filled), `openClaimedAmountByCurrency` (one line per currency, statuses combined), `decidedCount`, `settledCount`, `rejectedCount`, `settledAmountByCurrency`. Terminal claims are not counted as open; unassigned claims never appear. |
| TC-OFF-04 | Officer with decisions but no open work | Appears with `openClaimCount` 0 and empty open amounts. |
| TC-OFF-05 | One officer with claims in two currencies | Two separate currency lines, not a single total. |
| TC-OFF-06 | Wrong method | `POST` -> **405**. |

Sample response:

```json
{
  "generatedAt": "2026-10-18T09:09:12Z",
  "note": "Open workload is by CURRENT assignee (no reassignment history); decisions are by the deciding officer. Amounts are grouped by currency and never added across currencies.",
  "officers": [
    {
      "officerId": "OFF-A",
      "openClaimCount": 2,
      "openClaimsByStatus": { "SUBMITTED": 1, "UNDER_REVIEW": 1, "AWAITING_INFORMATION": 0 },
      "openClaimedAmountByCurrency": [ { "currency": "INR", "claimCount": 2, "amount": 1500.00 } ],
      "decidedCount": 0, "settledCount": 0, "rejectedCount": 0,
      "settledAmountByCurrency": []
    },
    {
      "officerId": "OFF-B",
      "openClaimCount": 0,
      "openClaimsByStatus": { "SUBMITTED": 0, "UNDER_REVIEW": 0, "AWAITING_INFORMATION": 0 },
      "openClaimedAmountByCurrency": [],
      "decidedCount": 1, "settledCount": 1, "rejectedCount": 0,
      "settledAmountByCurrency": [ { "currency": "AUD", "claimCount": 1, "amount": 600.00 } ]
    }
  ]
}
```

## 10b. Endpoint 12 - Claim status history

`GET /api/v1/claims/{claimId}/history` - automated: `StatusHistoryTest`, `ConcurrentTransitionTest`

No parameters. Returns a JSON array, oldest first, one element per status change. `fromStatus` is null on the creation row. `actor` is the officer ID sent in the request, or `CLAIMANT` for creation and for the automatic resume after an answer. Assigning an officer does not change status and adds no row. Rows are written in the same transaction as the change, so a rejected (409/400) action leaves no row.

| ID | Scenario | Expected |
|---|---|---|
| TC-HIS-01 | New claim | **200**; one row: `fromStatus` null, `toStatus` `SUBMITTED`, `actor` `CLAIMANT`, `changedAt` set. |
| TC-HIS-02 | Create, assign, review, information request, answer, reject | **200**; 5 rows in order: SUBMITTED; SUBMITTED to UNDER_REVIEW (officer); UNDER_REVIEW to AWAITING_INFORMATION (officer); AWAITING_INFORMATION to UNDER_REVIEW (`CLAIMANT`); UNDER_REVIEW to REJECTED (deciding officer). |
| TC-HIS-03 | Assign only | **200**; still one row. |
| TC-HIS-04 | Rejected transitions (duplicate review, settled amount over the claimed amount) | No extra rows. |
| TC-HIS-05 | Unknown claim | **404** `CLAIM_NOT_FOUND`. |
| TC-HIS-06 | `POST` to the history URL | **405** `METHOD_NOT_ALLOWED`. |
| TC-HIS-07 | Two competing decisions on one claim (`ConcurrentTransitionTest`) | One **200**, one **409**; exactly one terminal row; stored decision is the winner's. Also a decision racing an information request: one succeeds, one is 409. H2 only. |

Sample response:

```json
[
  { "claimId": "CLM-2026-000101", "fromStatus": null, "toStatus": "SUBMITTED", "changedAt": "2026-10-18T07:10:00Z", "actor": "CLAIMANT" },
  { "claimId": "CLM-2026-000101", "fromStatus": "SUBMITTED", "toStatus": "UNDER_REVIEW", "changedAt": "2026-10-18T07:12:00Z", "actor": "OFF-1007" }
]
```

## 10c. Endpoint 13 - Claim assignment history

`GET /api/v1/claims/{claimId}/assignments` - automated: `AssignmentHistoryTest`, `ConcurrentTransitionTest`

No parameters. Returns a JSON array, oldest first, one element per change of assigned officer. `previousOfficerId` is null the first time. `actor` is **null for `assign`** (the request only names the new assignee) and is the reviewing officer when `review` assigns an unassigned claim. There is no authentication, so `actor` is never a verified identity. Re-assigning the same officer, and a review by the already-assigned officer, add no row. Rejected actions add no row.

| ID | Scenario | Expected |
|---|---|---|
| TC-ASGH-01 | New claim | **200**; `[]`. |
| TC-ASGH-02 | Assign `OFF-1`, then `OFF-2` | **200**; two rows in order: null to `OFF-1`, then `OFF-1` to `OFF-2`; `changedAt` set. |
| TC-ASGH-03 | Assign | `actor` is null. |
| TC-ASGH-04 | Assign the same officer twice, or review by the assigned officer | One row only. |
| TC-ASGH-05 | Review of an unassigned claim by `OFF-7` | One row: null to `OFF-7`, `actor` `OFF-7`. |
| TC-ASGH-06 | Assign on a terminal claim (409), blank officer (400) | No new rows. |
| TC-ASGH-07 | Assignment does not change status | `GET .../history` is unchanged. |
| TC-ASGH-08 | Unknown claim / `POST` | **404** `CLAIM_NOT_FOUND` / **405**. |
| TC-ASGH-09 | Two officers race to review one unassigned claim | One **200**, one **409**; the assignee is not overwritten; one assignment row and one UNDER_REVIEW history row. |

Sample response:

```json
[
  { "claimId": "CLM-2026-000101", "previousOfficerId": null, "newOfficerId": "OFF-1", "changedAt": "2026-10-18T07:11:00Z", "actor": null },
  { "claimId": "CLM-2026-000101", "previousOfficerId": "OFF-1", "newOfficerId": "OFF-2", "changedAt": "2026-10-18T07:15:00Z", "actor": null }
]
```

Placeholder officer IDs (`PlaceholderActorTest`): `"string"` in any case as `officerId`, `requestedByOfficerId` or `decidedByOfficerId` returns **400** `VALIDATION_FAILED` with a message saying it must be a real officer ID; IDs that merely contain the word (for example `string-officer-1`) are accepted.

Additional concurrency cases (`ConcurrentTransitionTest`): two answers to one information request give one 200 and one 409; stale review, information request and second decision after a settlement give 409 and write no history.

## 11. End-to-end Swagger demo flow

Start the app (`.\mvnw.cmd spring-boot:run`), open `http://localhost:8181/swagger-ui/index.html`, and for each step expand the operation, choose **Try it out**, paste the body, and **Execute**. Copy IDs from responses; do not reuse the sample values.

| Step | Operation | Input | Expected |
|---|---|---|---|
| 1 | `POST /api/v1/claims` | The base request from section 4 | 201; copy `claimId` (call it `CLAIM`). |
| 2 | `GET /api/v1/claims/{claimId}` | `CLAIM` | 200, status `SUBMITTED`. |
| 3 | `GET /api/v1/officer/claims` | `status=SUBMITTED`, `market=SG` | 200; `CLAIM` is listed. |
| 4 | `POST .../{claimId}/assign` | `CLAIM`, `{"officerId":"OFF-1007"}` | 200, still `SUBMITTED`, assigned. |
| 5 | `POST .../{claimId}/review` | `CLAIM`, `{"officerId":"OFF-1007"}` | 200, `UNDER_REVIEW`. |
| 6 | `POST .../{claimId}/information-requests` | `CLAIM`, `{"question":"Please provide the repair estimate.","requestedByOfficerId":"OFF-1007"}` | 201, `OPEN`; copy `informationRequestId` (`IR`). |
| 7 | `GET /api/v1/claims/{claimId}/information-requests` | `CLAIM` | 200; one `OPEN` request. Step 2 now shows `AWAITING_INFORMATION`. |
| 8 | `POST /api/v1/claims/{claimId}/information` | `CLAIM`, `{"informationRequestId":"<IR>","answer":"The repair estimate is 2,200 INR."}` | 200, `ANSWERED`; claim is back to `UNDER_REVIEW` automatically (no extra review call). |
| 9 | `POST .../{claimId}/decision` | `CLAIM`, the settlement body from section 9 | 200, `SETTLED`, `settledAmount` 2200.00. |
| 10 | `GET /api/v1/management/summary` | none | 200; `CLAIM` is not in the outstanding liability. |
| 11 | `POST .../{claimId}/decision` | `CLAIM`, a rejection body with `"currency":"INR"` | **409** `INVALID_CLAIM_TRANSITION` (duplicate decision). |
| 12 | `GET /api/v1/claims/CLM-2026-999999` | none | **404** `CLAIM_NOT_FOUND`. |
| 13 | `GET /api/v1/officer/claims` | `assigned=false` (run before step 4) | 200; `CLAIM` is listed with `assignedOfficerId` null. |
| 14 | `GET /api/v1/claims/{claimId}/history` and `.../assignments` | `CLAIM` (after step 11) | 200; status rows SUBMITTED, UNDER_REVIEW, AWAITING_INFORMATION, UNDER_REVIEW, SETTLED; one assignment row for `OFF-1007`. Checked with curl on a verification instance (port 8199) on 2026-10-18: the five status rows and the one assignment row matched; steps 1-12 were not repeated in full. |

The same flow as a PowerShell script is in `README.md`.

## 12. Coverage and verified results

### Coverage by endpoint

| # | Endpoint | Automated test classes | Missing |
|---|---|---|---|
| 1 | `POST /api/v1/claims` | ClaimCreationTest, EndpointContractTest | none |
| 2 | `GET /api/v1/claims/{claimId}` | ClaimCreationTest, EndpointContractTest | none |
| 3 | `GET /api/v1/officer/claims` | OfficerSearchTest, UnassignedFilterTest | TC-SRCH-09 (no match) has no dedicated test |
| 4 | `POST .../assign` | ClaimWorkflowTest, EndpointContractTest | none |
| 5 | `POST .../review` | ClaimWorkflowTest, ClaimDomainTest, EndpointContractTest | none |
| 6 | `POST .../information-requests` | ClaimWorkflowTest, EndpointContractTest | none |
| 7 | `GET .../information-requests` | ClaimWorkflowTest, EndpointContractTest | none |
| 8 | `POST .../information` | ClaimWorkflowTest, EndpointContractTest | none |
| 9 | `POST .../decision` | ClaimWorkflowTest, EndpointContractTest | none |
| 10 | `GET /api/v1/management/summary` | ManagementSummaryTest, EndpointContractTest | none |
| 11 | `GET /api/v1/management/officers` | OfficerMetricsTest | none (no time-based metric exists, so none is tested) |
| 12 | `GET /api/v1/claims/{claimId}/history` | StatusHistoryTest, ConcurrentTransitionTest | none |
| 13 | `GET /api/v1/claims/{claimId}/assignments` | AssignmentHistoryTest, ConcurrentTransitionTest | none |

Not covered by any automated test: a forced `500 INTERNAL_ERROR`, PostgreSQL (the concurrency tests run on H2 only), Kafka, and authentication (none exists).

### Verified results

Filled in only from a real run. The totals come from `.\mvnw.cmd -B clean test` and the Surefire XML files under `target/surefire-reports`:

- **Run on 2026-10-18 (after assignment history, the placeholder-actor check, currency tests and more concurrency tests were added):** `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS, H2 in PostgreSQL mode only.
- Per class: AssignmentHistoryTest 10, ClaimCreationTest 9, ClaimDomainTest 6, ClaimWorkflowTest 18, ConcurrentTransitionTest 5, CurrencyHandlingTest 5, DefaultMarketTest 1, EndpointContractTest 43, ManagementSummaryTest 2, MarketValidationTest 3, OfficerMetricsTest 6, OfficerSearchTest 6, PlaceholderActorTest 4, StatusHistoryTest 6, UnassignedFilterTest 8.
- **Demo flow (section 11):** run for real on 2026-10-18 as the equivalent PowerShell script (not by clicking in the Swagger UI), against a verification instance on port 8199 because another process was using 8181. Results: steps 1-10 returned 201/200/200/200/200/201/200/200/200/200 as documented, step 8 left the claim `UNDER_REVIEW`, step 10 showed `outstandingClaimCount` 0, step 11 returned **409 `INVALID_CLAIM_TRANSITION`**, step 12 returned **404 `CLAIM_NOT_FOUND`**. `/swagger-ui/index.html` returned 200 and `/v3/api-docs` lists 10 paths. Clicking through Swagger UI itself was not performed.
- **PostgreSQL:** not tested. **Kafka:** not implemented.
