# Project structure

How the Angular code is organised, how a request travels through it, and where to add things. For setup and running see the
[README](../README.md); for the REST contract see [`endpoints_details.md`](../endpoints_details.md).

## Layout

```
chubb_Assessment_frontend/
├── angular.json            build, dev-server (proxy), test builders; production budgets + environment replacement
├── proxy.conf.json         dev proxy: /svc/user -> :8185, /svc/claim -> :8181, /svc/assessment -> :8182
├── e2e/                    opt-in end-to-end scripts against a live backend (see e2e/README.md)
├── docs/                   PROJECT_STRUCTURE.md (this file), ROLE_POLICY.md (who may become what), DEMO_GUIDE.md
├── endpoints_details.md    the REST contract used by this app
├── public/                 static assets copied as-is (favicon)
└── src/
    ├── index.html, main.ts, styles.scss          bootstrap, Material 3 theme, global layout/utility classes
    ├── environments/       environment.ts (dev) / environment.prod.ts - the three API path prefixes
    └── app/
        ├── app.ts, app.config.ts, app.routes.ts, app-title.strategy.ts
        ├── models/         typed interfaces for every request/response (one file per backend area)
        ├── services/       AuthService, UserService, ClaimService, AssessmentService, ManagementService,
        │                   NotificationService, LoadingService, ConfirmService
        ├── guards/         authGuard, guestGuard, homeRedirectGuard, roleGuard
        ├── interceptors/   jwt, error, loading (+ http-context flags)
        ├── config/         api.config.ts - API prefixes, public paths, market and currency pick-lists
        ├── utils/          error-message (incl. per-screen status wording), http-params, date, validators, csv, role-policy (who an admin may move to which role)
        ├── layout/         shell, header, sidebar (+ nav-items per role), footer, notification-area
        ├── shared/         reusable UI (see below)
        └── features/       one folder per role area, each lazy-loaded
            ├── auth/       login, register
            ├── common/     profile, not-found
            ├── claimant/   dashboard, create-claim, my-claims, claim-details, edit-claim, information-requests
            ├── officer/    dashboard, queue, assigned, review (workspace, review-actions panel, request-information dialog), actions/settlement, assessment-history
            ├── manager/    dashboard, claims, assignments (+ assign dialog), claim-overview, officers, liability
            └── admin/      dashboard, users/{user-management,create-user,user-detail,edit-user}
```

Components use **inline templates and styles** (they are short and keep a screen's markup, logic and look in one file), standalone
(no NgModules), `OnPush`, signals for state, and the zoneless change detection that is the Angular 22 default.

## Layers and what each may depend on

```
features/*  ──►  shared/*, services/*, models/*, utils/*
layout/*    ──►  services/*, models/*
shared/*    ──►  services/*, models/*, utils/*        (never features/)
services/*  ──►  models/*, config/*, interceptors/http-context, utils/*
interceptors/* ► services/auth, notification, loading
```

- **`models/`** mirrors the backend DTOs field for field (`claim.models.ts`, `user.models.ts`, `assessment.models.ts`,
  `management.models.ts`, `common.models.ts`). Request types differ from response types on purpose (for example no officer ID in any request).
- **`services/`** are the only code that calls `HttpClient`. Each method is one endpoint and is documented with its method and path.
  `ManagementService` is separate from `ClaimService` although the Claim Service serves both, so manager-only calls are easy to find.
- **`shared/`**: `claim-list` (filters + server paging + empty/error/loading), `claim-detail-card`, `claim-timeline` (the 4-step progress tracker),
  `claim-history-tabs` (Status / Assignment / Update history), `information-requests` (card list with the answer form for claimants, read-only table for officers and managers), `claim-brief`, `status-chip`, `user-badges`, `stat-card`, `page-header`,
  `empty-state`, `loading-spinner`, `confirm-dialog`, `chart/bar-chart` (Chart.js wrapper with a table-view twin), `money.pipe`, `claim-loader`.

## Request flow

```
component ─► service method ─► HttpClient
                                  │ loadingInterceptor   counts requests for the global progress bar
                                  │ jwtInterceptor       adds Authorization: Bearer <jwt> to /svc/* calls (not login/register);
                                  │                      a 401 ends the session and redirects to /login?returnUrl=...
                                  │ errorInterceptor     one toast from the backend's { code, message }; 401 is left to the jwt interceptor
                                  ▼
                    dev server proxy (proxy.conf.json) ──► user :8185 | claim :8181 | assessment :8182
```

A request can opt out of the toast or the progress bar with the `SKIP_ERROR_TOAST` / `SKIP_LOADING` context flags (used by login, which
shows its error inline, and by the quiet counts behind the notification bell).

## Routing and security

| Path | Guard(s) | Roles | Notes |
|---|---|---|---|
| `/login`, `/register` | `guestGuard` | public | signed-in people are sent to their own home |
| `/` | `authGuard` + `homeRedirectGuard` | any | redirects to the home of the role |
| `/claimant/**` | `authGuard`, `roleGuard` | CLAIMANT | `dashboard`, `new-claim`, `claims`, `claims/:claimId`, `claims/:claimId/edit`, `information-requests` |
| `/officer/**` | same | CLAIMS_OFFICER | `dashboard`, `queue`, `assigned`, `assessments`, `claims/:claimId` (workspace), `claims/:claimId/settlement`, `claims/:claimId/assessments` |
| `/manager/**` | same | MANAGER | `dashboard`, `claims`, `claims/:claimId`, `assignments`, `officers`, `liability` |
| `/admin/**` | same | ADMIN | `dashboard`, `users`, `users/new`, `users/:username`, `users/:username/edit` |
| `/profile` | `authGuard` | any | |
| anything else | `authGuard` | any | not-found page |

- `authGuard`: needs a live token (checked against its expiry, not only its presence); otherwise `/login?returnUrl=<where you were going>`.
- `roleGuard`: reads `data.roles` from the route. A signed-in person with another role is redirected to their own home with a warning toast;
  a signed-out visitor goes to login. `return URLs` are only followed when they are same-site absolute paths.
- Each role area is a `loadChildren` route file (`claimant.routes.ts`, ...), so another role's code is never downloaded.
- These guards decide what the UI **shows**. The backend independently enforces every role and scoping rule on every request, so a hand-edited URL or token cannot widen access.

## Session

`AuthService` keeps `{ accessToken, username, role, expiresAt }` in a signal and in **`sessionStorage`** (per tab, cleared on close).
It restores a still-valid session on reload, discards an expired or corrupt one, and arms a timer that signs the person out when the token expires.
`role`, `username` and `isLoggedIn` are computed signals; `homeRoute(role)` is the single place that maps a role to its landing page.

## UX building blocks

| Need | Where |
|---|---|
| Loading | global progress bar in `layout/shell` (from `LoadingService`); `app-loading-spinner` for a first load; faded content while refreshing |
| Toasts | `NotificationService` (Material snack bar) |
| Notification area | `layout/notification-area`: the same messages kept as a list with an unread badge, plus "needs your attention" counts at sign-in |
| Confirmation | `ConfirmService.confirm({ title, message, tone })` returns `Observable<boolean>`; used before every state-changing action |
| Errors | `utils/error-message.ts` turns any failure into a sentence; pages show an `app-empty-state` with "Try again" |
| Empty states | `app-empty-state`, with the next step as a button |
| Responsive | sidebar is a drawer below 960 px; tables drop low-priority columns (`hide-md`, `hide-sm`); forms collapse to one column below 600 px |
| Accessibility | skip link, labelled landmarks, status never by colour alone (icon + text), `aria-current` in the menu, a table-view twin for every chart |

## Charts

`shared/chart/bar-chart.ts` wraps Chart.js (through `ng2-charts`) and follows a fixed set of rules: bars at most 24 px with a rounded data end,
solid hairline grid, 2 px surface gaps between stacked segments, a legend only when there are two or more series, a value at the bar tip for a
single series, a tooltip that lists every series at that category, and a **Table view** toggle. Colours come from `chart-theme.ts` (a
validated 4-colour categorical set; slots are assigned per entity so a status keeps its colour). Amounts in different currencies are never
placed on one axis.

## Adding things

- **A new endpoint:** add the request/response types to `models/`, one method to the matching service in `services/`, and a row to `endpoints_details.md`.
- **A new page:** create the component under `features/<role>/`, add a `loadComponent` route in that area's `*.routes.ts` with a `title`, and (if it belongs in the menu) an entry in `layout/nav-items.ts`.
- **A new role area:** a `<role>.routes.ts`, a `loadChildren` route with `canActivate: [roleGuard]` and `data: { roles: [...] }` in `app.routes.ts`, and an entry in `HOME_ROUTES` and `NAV_ITEMS`.
- **A change to who may become what:** edit `ROLE_TRANSITIONS` or `PROVISIONABLE_ROLES` in `utils/role-policy.ts` (a new `Role` does not compile until its row exists), then the hand-written matrix in `role-policy.spec.ts`. Never list roles in an admin component.
- **Server-pushed notifications (planned, Kafka):** call `NotificationService.push(type, message)` from the new transport; no component needs to change.

## Testing

- `npm run test:ci`: Vitest unit tests (10 files, 143 tests) for the session (storage, restore, expiry timer), the guards for every role, the three interceptors, the utilities (errors, CSV, validators, dates, money, role policy), the Request Information dialog, the information-requests table, and the Edit user and Create user pages.
- Coverage is not measured (no coverage tool is configured).
- `npm run e2e:contract` and `npm run e2e:ui`: opt-in tests against a live backend (see [`e2e/README.md`](../e2e/README.md)).
