import { Routes } from '@angular/router';

/**
 * Admin area (/admin/**). Entry is guarded in app.routes.ts (ADMIN only).
 * `users/new` is declared before `users/:username` so "new" is never read as a login name (login names are 3+ characters,
 * and "new" is only three, so the order really matters).
 */
export const ADMIN_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Administration',
    loadComponent: () => import('./dashboard/dashboard').then((m) => m.AdminDashboard),
  },
  {
    path: 'users',
    title: 'User management',
    loadComponent: () => import('./users/user-management').then((m) => m.UserManagement),
  },
  {
    path: 'users/new',
    title: 'Create user',
    loadComponent: () => import('./users/create-user').then((m) => m.CreateUser),
  },
  {
    path: 'users/:username/edit',
    title: 'Edit user',
    loadComponent: () => import('./users/edit-user').then((m) => m.EditUser),
  },
  {
    path: 'users/:username',
    title: 'User detail',
    loadComponent: () => import('./users/user-detail').then((m) => m.UserDetail),
  },
];
