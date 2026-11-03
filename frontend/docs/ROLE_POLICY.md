# Role policy: review, change and impact

The rule and its diagram are in the [README, section 8](../README.md#8-user-management-and-role-policy). This page records what was found in the Admin module, what changed, what did not, and what the backend owner still has to do.

## What the Admin module allowed before

| # | Action | Before | Now (UI) | Backend (unchanged) |
|---|---|---|---|---|
| 1 | Claimant → claims officer, manager or administrator (Edit user) | offered | no picker, explanation shown | accepts |
| 2 | Staff → claimant (Edit user) | offered | not in the picker | accepts |
| 3 | Create a user with the Claimant role | offered | not in the list | accepts |
| 4 | Staff ↔ staff | offered | offered | accepts |
| 5 | Change your own role | control hidden | control hidden | `409 CANNOT_CHANGE_OWN_ROLE` |
| 6 | Deactivate yourself | control hidden | control hidden | `409 CANNOT_DEACTIVATE_SELF` |

Rows 1 to 3 were the invalid transitions. Both pickers listed all four roles for every account.

## Decisions to confirm

| Decision | Taken | Why | Reversal |
|---|---|---|---|
| Lock **every** claimant, not only "self-registered" ones | yes | The User Service returns `{ username, role, active, createdAt }` with no origin field, so the UI cannot tell them apart | Needs a backend field |
| Staff accounts never become claimants | yes | Claims are owned by login name, and an officer may hold assigned work. Reading "separately provisioned" as applying both ways | Add `CLAIMANT` to the staff rows of `ROLE_TRANSITIONS` |
| Create user offers staff roles only | yes | Claimants register themselves; the form is the staff provisioning route | Add `CLAIMANT` to `PROVISIONABLE_ROLES` |
| Staff ↔ staff stays allowed | yes | Not asked to restrict, and the existing e2e flow promotes an officer to manager | Edit `ROLE_TRANSITIONS` |

All four are one table and one list in `src/app/utils/role-policy.ts`.

## Frontend files

| File | Change |
|---|---|
| `src/app/utils/role-policy.ts` | **New.** Transition table, `PROVISIONABLE_ROLES`, `isRoleChangeAllowed`, `canChangeRole`, `roleChoices`, two form validators |
| `src/app/utils/role-policy.spec.ts` | **New.** Hand-written 4 × 4 matrix, validators, provisioning |
| `features/admin/users/edit-user.ts` | Claimant: locked panel and link to Create user. Staff: picker without Claimant, validator, re-check before the confirm dialog and the request |
| `features/admin/users/create-user.ts` | Staff roles only, validator, hint, subtitle |
| `features/admin/users/user-detail.ts` | "Edit user" hidden for claimants, reason shown |
| `features/admin/users/user-management.ts` | Wording only |
| `edit-user.spec.ts`, `create-user.spec.ts` | **New.** Render the real pages |
| `README.md`, `endpoints_details.md`, `docs/PROJECT_STRUCTURE.md`, `CLAUDE.md`, `Frontend_UI_UX_Requirements.md` | Documentation |

Deliberately **not** changed: `user.service.ts` and the models (no API contract change, and services hold no UI logic), `admin.routes.ts`, and the menu labels (the e2e script finds "Create user" by name).

## Backend follow-up (not done here)

The frontend project does not modify the backend, so this is a recommendation for its owner. Today `UserService.changeRole` checks only that the role is assignable, that it is not the caller's own account, and that the user exists. Any ADMIN token can therefore still call `PUT /users/{username}/role` with a claimant and `ADMIN`.

Suggested rule in `changeRole`, after loading the user: refuse unless both the current and the new role are staff roles, as `409 ROLE_CHANGE_NOT_ALLOWED`. Per the backend's own rules this also needs a test, a row in `API_TEST_CASES.md` section 3b and a README note. If self-registered and admin-created claimants must be told apart, add an origin field to `UserResponse` and the `users` table (a new Flyway migration). No frontend change would then be needed beyond the existing 409 toast.

## Impact analysis

| Area | Impact |
|---|---|
| People | Administrators only. Claimants, officers and managers see no difference |
| API | None. Same endpoints, same bodies. The UI now sends fewer role values |
| Bundle | No new dependency. The policy is imported by the admin screens only, which are lazy-loaded |
| Tests | 89 → 143 unit tests, all passing. Three mutants (claimant widened in the table, submit-time guard removed, Create user listing every role) were each caught |
| End-to-end | `ui.mjs` creates an officer and promotes it to manager, which is still allowed. **Neither e2e script was re-run** (they need the backend) and neither covers the new rule yet |
| Support | An administrator who needs a claimant promoted, or staff demoted to claimant, now has no UI path. The answer is a new account |

## Not solved by this change

- **Direct API calls** (above).
- **Stale tokens.** A promoted or demoted person keeps their old role until the token expires (60 minutes by default); there is no revocation.
- **Officers holding work.** Moving an officer to another role leaves their assigned claims assigned to them. The Admin UI cannot see assigned claims and the effect on those claims has not been tested end to end.
- **No audit trail.** The backend records no history of role changes, so the UI cannot show one.
- **Granting ADMIN** is one confirmation click, the same as any other role. A typed confirmation would be a reasonable hardening; it was not asked for.

## Suggested end-to-end additions

In `e2e/ui.mjs`: register a claimant, open it in Admin, assert there is no "Edit user" button and that `/admin/users/<name>/edit` shows the explanation; assert the Create user role list has no "Claimant". Not written, because they could not be run in this session.
