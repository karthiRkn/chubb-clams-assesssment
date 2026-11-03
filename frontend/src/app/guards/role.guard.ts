import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../models';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

/**
 * Role-based route protection. A route lists who may enter in `data: { roles: [...] }`.
 * Someone signed in with another role is redirected to their own home page with a notice; someone signed out goes to login.
 * This only decides what the UI shows: the backend enforces the same rules on every request.
 */
export const roleGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const role = auth.role();
  if (!auth.hasValidSession() || role === null) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }

  const allowed = route.data['roles'] as readonly Role[] | undefined;
  if (!allowed || allowed.includes(role)) {
    return true;
  }

  inject(NotificationService).warning('You do not have access to that page.');
  return router.createUrlTree([auth.homeRoute(role)]);
};
