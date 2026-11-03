import { Routes } from '@angular/router';

/** Manager area (/manager/**). Entry is guarded in app.routes.ts (MANAGER only). */
export const MANAGER_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Overview',
    loadComponent: () => import('./dashboard/dashboard').then((m) => m.ManagerDashboard),
  },
  {
    path: 'claims',
    title: 'Claims',
    loadComponent: () => import('./claims/manager-claims').then((m) => m.ManagerClaims),
  },
  {
    path: 'assignments',
    title: 'Assignment dashboard',
    loadComponent: () => import('./assignments/assignments').then((m) => m.Assignments),
  },
  {
    path: 'claims/:claimId',
    title: 'Claim overview',
    loadComponent: () => import('./claim-overview/claim-overview').then((m) => m.ClaimOverview),
  },
  {
    path: 'officers',
    title: 'Officer metrics',
    loadComponent: () => import('./officers/officer-metrics').then((m) => m.OfficerMetricsPage),
  },
  {
    path: 'liability',
    title: 'Liability reports',
    loadComponent: () => import('./liability/liability-reports').then((m) => m.LiabilityReports),
  },
];
