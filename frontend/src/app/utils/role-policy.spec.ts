import { FormControl } from '@angular/forms';
import { ROLES, Role } from '../models';
import {
  allowedRoleChange,
  canChangeRole,
  isRoleChangeAllowed,
  PROVISIONABLE_ROLES,
  provisionableRole,
  roleChoices,
} from './role-policy';

/** The business rule written out by hand (not read back from the code): the roles each role may be moved to. */
const MAY_MOVE_TO: Record<Role, Role[]> = {
  CLAIMANT: [],
  CLAIMS_OFFICER: ['MANAGER', 'ADMIN'],
  MANAGER: ['CLAIMS_OFFICER', 'ADMIN'],
  ADMIN: ['CLAIMS_OFFICER', 'MANAGER'],
};

const STAFF: Role[] = ['CLAIMS_OFFICER', 'MANAGER', 'ADMIN'];

describe('role policy', () => {
  describe('isRoleChangeAllowed', () => {
    const everyPair = ROLES.flatMap((from) => ROLES.map((to) => [from, to] as [Role, Role]));

    it.each(everyPair)('%s -> %s', (from, to) => {
      expect(isRoleChangeAllowed(from, to)).toBe(MAY_MOVE_TO[from].includes(to));
    });

    it.each(STAFF)('never promotes a claimant to %s', (staff) => {
      expect(isRoleChangeAllowed('CLAIMANT', staff)).toBe(false);
    });

    it.each(STAFF)('never turns %s into a claimant', (staff) => {
      expect(isRoleChangeAllowed(staff, 'CLAIMANT')).toBe(false);
    });

    it.each(ROLES)('does not count staying %s as a move', (role) => {
      expect(isRoleChangeAllowed(role, role)).toBe(false);
    });
  });

  describe('canChangeRole', () => {
    it('is false for a claimant only', () => {
      expect(ROLES.filter((role) => !canChangeRole(role))).toEqual(['CLAIMANT']);
    });
  });

  describe('roleChoices', () => {
    it('offers a claimant nothing but their own role', () => {
      expect(roleChoices('CLAIMANT')).toEqual(['CLAIMANT']);
    });

    it.each(STAFF)('offers %s the staff roles only, never Claimant', (staff) => {
      expect(roleChoices(staff)).toEqual(STAFF);
    });
  });

  describe('allowedRoleChange validator', () => {
    const control = (value: Role) => new FormControl<Role>(value, { nonNullable: true });
    const check = (from: Role | null, to: Role) => allowedRoleChange(() => from)(control(to));

    it('accepts a move between staff roles', () => {
      expect(check('CLAIMS_OFFICER', 'MANAGER')).toBeNull();
    });

    it('accepts leaving the role as it is', () => {
      expect(check('CLAIMANT', 'CLAIMANT')).toBeNull();
    });

    it('rejects promoting a claimant', () => {
      expect(check('CLAIMANT', 'ADMIN')).toEqual({
        roleTransition: { from: 'CLAIMANT', to: 'ADMIN' },
      });
    });

    it('rejects turning staff into a claimant', () => {
      expect(check('MANAGER', 'CLAIMANT')).toEqual({
        roleTransition: { from: 'MANAGER', to: 'CLAIMANT' },
      });
    });

    it('waits for the account to load before judging', () => {
      expect(check(null, 'CLAIMANT')).toBeNull();
    });
  });

  describe('provisioning', () => {
    it('creates staff accounts only', () => {
      expect([...PROVISIONABLE_ROLES]).toEqual(STAFF);
    });

    it.each(STAFF)('accepts %s on Create user', (staff) => {
      expect(provisionableRole(new FormControl(staff))).toBeNull();
    });

    it.each(['CLAIMANT', '', 'SERVICE'])('rejects "%s" on Create user', (value) => {
      expect(provisionableRole(new FormControl(value))).toEqual({ notProvisionable: true });
    });
  });
});
