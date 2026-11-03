import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ROLES, Role } from '../models';

/**
 * Which role an administrator may move an account to (Admin > Edit user).
 *
 * - A CLAIMANT stays a claimant. Claimants register themselves; nobody is promoted into a staff role from a claimant account.
 * - Staff accounts (claims officer, manager, administrator) are provisioned separately with Create user, so a staff account
 *   is never turned into a claimant either. A claimant owns claims by login name, and an officer may hold assigned work.
 * - Moves between the three staff roles stay allowed.
 *
 * This is a UI rule. The User Service accepts any role on `PUT /users/{username}/role` (it only refuses your own account), so
 * this table keeps the Admin screens honest but is not enforcement. See README "Role policy" for what that means.
 * The table is written by hand, one row per role, so a new `Role` fails to compile until its row is decided.
 */
export const ROLE_TRANSITIONS: Readonly<Record<Role, readonly Role[]>> = {
  CLAIMANT: [],
  CLAIMS_OFFICER: ['MANAGER', 'ADMIN'],
  MANAGER: ['CLAIMS_OFFICER', 'ADMIN'],
  ADMIN: ['CLAIMS_OFFICER', 'MANAGER'],
};

/** Roles an administrator can give a new account on Create user. Claimants create their own accounts on the register page. */
export const PROVISIONABLE_ROLES: readonly Role[] = ['CLAIMS_OFFICER', 'MANAGER', 'ADMIN'];

/** Shown wherever a claimant's role is fixed, so every screen says the same thing. */
export const CLAIMANT_ROLE_FIXED =
  'Claimants register themselves and stay claimants. Claims officers, managers and administrators are created separately with Create user.';

/** Whether the account can be moved from `from` to `to`. Staying on the same role is not a move. */
export function isRoleChangeAllowed(from: Role, to: Role): boolean {
  return ROLE_TRANSITIONS[from].includes(to);
}

/** Whether Edit user has anything to offer for an account that currently holds `current`. */
export function canChangeRole(current: Role): boolean {
  return ROLE_TRANSITIONS[current].length > 0;
}

/** What the role picker lists for an account: the current role (so the picker shows it) and the roles it may move to, in the usual order. */
export function roleChoices(current: Role): readonly Role[] {
  return ROLES.filter((role) => role === current || isRoleChangeAllowed(current, role));
}

/**
 * Form validator for Edit user: the chosen role must be the current one (nothing to change) or an allowed move.
 * `current` is a function because the account is loaded after the form exists.
 */
export function allowedRoleChange(current: () => Role | null): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const from = current();
    const to = control.value as Role;
    return from === null || from === to || isRoleChangeAllowed(from, to)
      ? null
      : { roleTransition: { from, to } };
  };
}

/** Form validator for Create user: only staff roles can be provisioned there. */
export const provisionableRole: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null =>
  PROVISIONABLE_ROLES.includes(control.value as Role) ? null : { notProvisionable: true };
