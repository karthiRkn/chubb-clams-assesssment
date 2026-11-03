# End-to-end tests

Two scripts that run against the **live backend** through the Angular dev proxy, exactly as a browser would. They are not part of
`npm test` (which needs no backend): run them on demand, for example before a release or after a backend change.

| Command | What it checks | Needs |
|---|---|---|
| `npm run e2e:contract` | Every API the frontend uses: the field names of each response against `src/app/models`, role denials, scoping (404 for another claimant's claim), lifecycle rules, error codes. ~90 checks, no browser. | backend + `npm start` |
| `npm run e2e:ui` | The real screens in Chrome: sign-in and route guards, a claim from submission to settlement across four roles, rejection, the manager reports and CSV export, admin user management, and a phone/tablet viewport. ~50 steps; also fails on any console error, page error or unexpected failed API call. | backend + `npm start` + Chrome |

## Run

```powershell
# 1. the three backend services (see the main README)       2. the frontend, in another terminal
npm start

# 3. in a third terminal
npm run e2e:contract
npm run e2e:ui
```

Environment variables: `E2E_BASE` (default `http://localhost:4200`), `E2E_BROWSER` (`chrome` by default, or `msedge`), `E2E_HEADED=1` to watch the browser.
Screenshots of the main screens are written to `e2e/screenshots/` (git-ignored).

## Things to know

- **They create data**: claims, an information request, users named `ui.test.*`, `qa.*` and `reg*`/`other*`. Use a development backend (the default in-memory H2 databases are wiped on restart).
- They sign in with the backend's demo accounts (`claimant1`, `officer1`, `manager1`, `admin1`, password `password123`), so the backend must be running with `DEMO_USERS=true` (its default).
- They use the browser already installed on the machine (`playwright-core`, no browser download).
