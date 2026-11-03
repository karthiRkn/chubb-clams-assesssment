import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { API_BASES, PUBLIC_API_PATHS } from '../config/api.config';
import { AuthService } from '../services/auth.service';

/**
 * Adds `Authorization: Bearer <jwt>` to every call to the three backend services, except login and register.
 * A 401 on any other backend call means the token is expired or no longer accepted: the session is ended and the person
 * is sent to the login page (and back to where they were afterwards).
 */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  const isBackendCall = API_BASES.some((base) => req.url.startsWith(base));
  const isPublic = PUBLIC_API_PATHS.some((path) => req.url.endsWith(path));
  if (!isBackendCall || isPublic) {
    return next(req);
  }

  const token = auth.getToken();
  const authorised = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authorised).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.logout({ expired: true });
      }
      return throwError(() => error);
    }),
  );
};
