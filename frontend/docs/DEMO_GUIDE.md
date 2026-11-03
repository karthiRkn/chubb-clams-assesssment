# Demo and interview guide

Architecture diagrams are in the [README](../README.md); this page is for presenting them.

## The 30-second pitch

An Angular 22 app that gives four kinds of people one claims platform. It talks to three existing microservices and changes none of them. The backend stays the source of truth for every business rule; the UI shows what applies, validates the obvious, and explains failures.

## Five-minute demo

Start the backend and `npm start`, then use the demo-account buttons on the login page.

| Min | Who | Do | Say |
|---|---|---|---|
| 0:00 | claimant1 | Report a claim (SGD) | Claimant is locked to their own claims; scope is enforced by the backend |
| 1:00 | officer1 | Queue → take it → Start review | The officer name is never sent: it comes from the token |
| 1:45 | officer1 | Request Information (dialog) | The claim moves to `MORE_INFO_REQUIRED`; errors stay inside the dialog so typed text is never lost |
| 2:15 | claimant1 | Answer the request | Answering the last open request resumes the review automatically |
| 2:45 | officer1 | Approve with an amount, then Settle | Approve and settle are two steps; a rejected claim can never be settled |
| 3:30 | manager1 | Overview, Officer metrics, Liability reports | One figure per currency, never a cross-currency total |
| 4:15 | admin1 | Create user (note: staff roles only), then open a claimant | Claimants register themselves and stay claimants; their detail page has no Edit user button and says why |

Before the last step, register a new claimant on `/register` so there is a non-demo account to show.

## Talking points

| Topic | Point |
|---|---|
| Proxy, not CORS | The backend sends no CORS headers and has no gateway, so every call goes through three path prefixes. Same build runs in dev and production |
| Session | `sessionStorage` only, so it dies with the tab; a timer signs out at expiry; a 401 clears the session and returns the person to where they were |
| Guards vs backend | Guards decide what is shown. The backend enforces every rule, so a hand-edited URL or token cannot widen access |
| Money | Never added across currencies, in tables, charts or CSV |
| Cross-service honesty | Approval writes an assessment entry, calls the Claim Service, then commits. Consistency is best effort, not transactional, and the docs say so |
| Role policy | One table decides every role change. It is a UI rule; the backend still accepts any role, which is the first thing to raise if asked |
| Performance | Each role area is lazy-loaded, so a claimant never downloads officer, manager or admin code |

## Numbers you can quote (measured on this machine)

| | |
|---|---|
| Unit tests | 143 passing in 10 spec files (`npm run test:ci`) |
| Production build | Initial bundle 862 kB raw, about 212 kB transferred; 70 lazy chunks |
| Largest component | 361 lines (limit 500) |
| End-to-end scripts | 84 API checks and 58 browser steps exist in the scripts; **not run** with the latest change |

## Questions to expect

| Question | Honest answer |
|---|---|
| Can an admin still promote a claimant? | Not through the UI. Through the API, yes: the User Service has no such rule. See [ROLE_POLICY.md](ROLE_POLICY.md) for the one-line backend fix |
| Why lock every claimant? | The API has no "self-registered" flag, so the UI cannot tell them apart |
| Is it secure? | No claim of that. One shared signing secret, no revocation, refresh, lockout or rate limit, no TLS, demo accounts on by default |
| What is the test coverage? | Not measured: no coverage tool is configured. Only `AuthService` has a service spec |
| Accessibility? | Skip link, labelled landmarks, keyboard support, status never by colour alone, a table twin for every chart. No Lighthouse audit has been run |
| Does it run on PostgreSQL? | The backend defaults to H2. What has run on PostgreSQL is listed in the backend README |

## Where this project differs from `CLAUDE.md`

Found while checking the documents against the code. Left as they are, because `CLAUDE.md` is the requirement and the choice is the owner's.

| `CLAUDE.md` says | Implemented |
|---|---|
| `core/` folder with guards, interceptors, layouts | Flat `guards/`, `interceptors/`, `layout/`, `services/`, `models/` (documented in `PROJECT_STRUCTURE.md`) |
| `/claimant/claims/new`, `/officer/history`, `/manager/officer-metrics`, `/manager/liability-reports`, `/admin/users/create`, `/admin/users/:id` | `/claimant/new-claim`, `/officer/assessments`, `/manager/officers`, `/manager/liability`, `/admin/users/new`, `/admin/users/:username` |
| Roles written `ROLE_CLAIMANT` and so on | Plain `CLAIMANT`, `CLAIMS_OFFICER`, `MANAGER`, `ADMIN`, exactly as the backend sends them |
| Toast "Session expired. Please sign in again." | "Your session has expired. Please sign in again." (the e2e script asserts this wording) |
| `MatCard` required | Not used; screens use a shared `.panel` style |
| Skeleton loaders | Spinner, faded content while refreshing, and empty states |
| Signal Stores "where appropriate" | None; plain signals in services and components |
| 80% coverage, Lighthouse accessibility pass | Neither measured |
