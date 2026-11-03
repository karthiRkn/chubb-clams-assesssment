import { Routes } from '@angular/router';

/**
 * Claims officer area (/officer/**). Entry is guarded in app.routes.ts (CLAIMS_OFFICER only).
 * The review workspace (/officer/claims/:claimId) holds the decision (approve / reject) and the request-information dialog;
 * settlement and the claim's assessment history are their own pages under it.
 */
export const OFFICER_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard',
    loadComponent: () => import('./dashboard/dashboard').then((m) => m.OfficerDashboard),
  },
  {
    path: 'queue',
    title: 'Claims queue',
    loadComponent: () => import('./queue/claims-queue').then((m) => m.ClaimsQueue),
  },
  {
    path: 'assigned',
    title: 'Assigned claims',
    loadComponent: () => import('./assigned/assigned-claims').then((m) => m.AssignedClaims),
  },
  {
    path: 'assessments',
    title: 'Assessment history',
    loadComponent: () =>
      import('./assessment-history/assessment-history').then((m) => m.AssessmentHistoryPicker),
  },
  {
    path: 'claims/:claimId',
    title: 'Review claim',
    loadComponent: () => import('./review/review-claim').then((m) => m.ReviewClaim),
  },
  {
    path: 'claims/:claimId/settlement',
    title: 'Settle claim',
    loadComponent: () => import('./actions/settlement').then((m) => m.Settlement),
  },
  {
    path: 'claims/:claimId/assessments',
    title: 'Assessment history',
    loadComponent: () => import('./assessment-history/assessment-history').then((m) => m.AssessmentHistory),
  },
];
