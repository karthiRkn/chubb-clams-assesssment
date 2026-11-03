import { Routes } from '@angular/router';
import { authGuard, guestGuard, homeRedirectGuard } from './guards/auth.guard';
import { roleGuard } from './guards/role.guard';
import { Role } from './models';

/**
 * Route map.
 *
 *   /login, /register            public (signed-in people are sent to their own home)
 *   /                            signed in -> the home page of the person's role
 *   /claimant/**                 CLAIMANT only
 *   /officer/**                  CLAIMS_OFFICER only
 *   /manager/**                  MANAGER only
 *   /admin/**                    ADMIN only
 *   /profile                     any signed-in role
 *
 * A signed-out visit to any protected URL goes to /login?returnUrl=...; a signed-in visit to another role's area is
 * redirected to the person's own home with a notice. The feature areas are lazy-loaded, so a claimant never downloads
 * the officer, manager or admin code.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'register',
    title: 'Create account',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },
      {
        path: 'claimant',
        canActivate: [roleGuard],
        data: { roles: ['CLAIMANT'] satisfies Role[] },
        loadChildren: () => import('./features/claimant/claimant.routes').then((m) => m.CLAIMANT_ROUTES),
      },
      {
        path: 'officer',
        canActivate: [roleGuard],
        data: { roles: ['CLAIMS_OFFICER'] satisfies Role[] },
        loadChildren: () => import('./features/officer/officer.routes').then((m) => m.OFFICER_ROUTES),
      },
      {
        path: 'manager',
        canActivate: [roleGuard],
        data: { roles: ['MANAGER'] satisfies Role[] },
        loadChildren: () => import('./features/manager/manager.routes').then((m) => m.MANAGER_ROUTES),
      },
      {
        path: 'admin',
        canActivate: [roleGuard],
        data: { roles: ['ADMIN'] satisfies Role[] },
        loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
      },
      {
        path: 'profile',
        title: 'Profile',
        loadComponent: () => import('./features/common/profile/profile').then((m) => m.Profile),
      },
      {
        path: '**',
        title: 'Page not found',
        loadComponent: () => import('./features/common/not-found/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
