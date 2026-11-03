import { Role } from '../models';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Match child routes too (e.g. /claimant/claims/CLM-1 keeps "My claims" highlighted). */
  exact?: boolean;
}

/**
 * The sidebar for each role. This is navigation only: the same rules are enforced by the route guards (what a person can
 * open) and, ultimately, by the backend (what a person can read or change).
 */
export const NAV_ITEMS: Record<Role, readonly NavItem[]> = {
  CLAIMANT: [
    { label: 'Dashboard', icon: 'dashboard', route: '/claimant/dashboard', exact: true },
    { label: 'New claim', icon: 'add_circle', route: '/claimant/new-claim', exact: true },
    { label: 'My claims', icon: 'description', route: '/claimant/claims' },
    { label: 'Information requests', icon: 'forum', route: '/claimant/information-requests' },
    { label: 'Profile', icon: 'person', route: '/profile' },
  ],
  CLAIMS_OFFICER: [
    { label: 'Dashboard', icon: 'dashboard', route: '/officer/dashboard', exact: true },
    { label: 'Claims queue', icon: 'inbox', route: '/officer/queue' },
    { label: 'Assigned claims', icon: 'assignment_ind', route: '/officer/assigned' },
    { label: 'Assessment history', icon: 'history', route: '/officer/assessments' },
    { label: 'Profile', icon: 'person', route: '/profile' },
  ],
  MANAGER: [
    { label: 'Dashboard', icon: 'dashboard', route: '/manager/dashboard', exact: true },
    { label: 'Claims', icon: 'description', route: '/manager/claims' },
    { label: 'Assignment dashboard', icon: 'assignment', route: '/manager/assignments' },
    { label: 'Officer metrics', icon: 'groups', route: '/manager/officers' },
    { label: 'Liability reports', icon: 'account_balance', route: '/manager/liability' },
    { label: 'Profile', icon: 'person', route: '/profile' },
  ],
  ADMIN: [
    { label: 'Dashboard', icon: 'dashboard', route: '/admin/dashboard', exact: true },
    { label: 'User management', icon: 'manage_accounts', route: '/admin/users', exact: true },
    { label: 'Create user', icon: 'person_add', route: '/admin/users/new', exact: true },
    { label: 'Profile', icon: 'person', route: '/profile' },
  ],
};
