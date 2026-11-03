import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Only a person with a live token gets in; everyone else goes to the login page and comes back afterwards. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.hasValidSession()) {
    return true;
  }
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** The empty path: send each person to the home page of their own role. */
export const homeRedirectGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.homeRoute()]);
};

/** Keeps signed-in people off the login and register pages. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.hasValidSession() ? inject(Router).createUrlTree([auth.homeRoute()]) : true;
};
