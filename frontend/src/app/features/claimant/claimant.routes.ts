import { Routes } from '@angular/router';

/**
 * Claimant area (/claimant/**). Entry is guarded in app.routes.ts (CLAIMANT only).
 * The create page is `new-claim`, not `claims/new`: it must not sit under `claims`, or the sidebar would highlight
 * "My claims" while the person is filling in a new claim (and "new" could be mistaken for a claim reference).
 */
export const CLAIMANT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard',
    loadComponent: () => import('./dashboard/dashboard').then((m) => m.ClaimantDashboard),
  },
  {
    path: 'new-claim',
    title: 'Report a claim',
    loadComponent: () => import('./create-claim/create-claim').then((m) => m.CreateClaim),
  },
  {
    path: 'claims',
    title: 'My claims',
    loadComponent: () => import('./my-claims/my-claims').then((m) => m.MyClaims),
  },
  {
    path: 'claims/:claimId',
    title: 'Claim details',
    loadComponent: () => import('./claim-details/claim-details').then((m) => m.ClaimDetails),
  },
  {
    path: 'claims/:claimId/edit',
    title: 'Edit claim',
    loadComponent: () => import('./edit-claim/edit-claim').then((m) => m.EditClaim),
  },
  {
    path: 'information-requests',
    title: 'Information requests',
    loadComponent: () =>
      import('./information-requests/information-requests').then((m) => m.ClaimantInformationRequests),
  },
];
