import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../services/notification.service';
import { errorMessage } from '../utils/error-message';
import { SKIP_ERROR_TOAST } from './http-context';

/**
 * One place that turns a failed call into a toast, using the backend's own `message` when it sent one.
 * Not shown for: a 401 (the JWT interceptor ends the session and says so) and requests flagged SKIP_ERROR_TOAST
 * (the caller shows the error inline). The error is always re-thrown so callers can still reset their own state.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notifications = inject(NotificationService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status !== 401 && !req.context.get(SKIP_ERROR_TOAST)) {
        notifications.error(errorMessage(error));
      }
      return throwError(() => error);
    }),
  );
};
