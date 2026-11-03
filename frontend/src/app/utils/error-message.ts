import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../models';

export function isApiError(value: unknown): value is ApiError {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ApiError).message === 'string' &&
    typeof (value as ApiError).code === 'string'
  );
}

/** The backend's machine-readable error code (INVALID_CLAIM_TRANSITION, CLAIM_NOT_FOUND, ...), if the response carried one. */
export function errorCode(err: unknown): string | null {
  return err instanceof HttpErrorResponse && isApiError(err.error) ? err.error.code : null;
}

/**
 * Like `errorMessage`, but a screen can say what a particular HTTP status means *there* ("403: you may not do this to this claim").
 * Statuses that are not listed fall back to the backend's own message, then to the generic wording.
 */
export function errorMessageFor(err: unknown, byStatus: Partial<Record<number, string>>, fallback?: string): string {
  if (err instanceof HttpErrorResponse) {
    const specific = byStatus[err.status];
    if (specific) {
      return specific;
    }
  }
  return errorMessage(err, fallback);
}

/** A message that is safe to show to a person, whatever the failure was. */
export function errorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'Cannot reach the server. Check that the backend services are running.';
    }
    if (isApiError(err.error)) {
      return err.error.message;
    }
    switch (err.status) {
      case 401:
        return 'Your session has expired. Please sign in again.';
      case 403:
        return 'You do not have permission to do that.';
      case 404:
        return 'The requested item was not found.';
      case 502:
      case 503:
      case 504:
        return 'A backend service is unavailable. Please try again shortly.';
      default:
        return err.status >= 500 ? 'The server hit an unexpected error. Please try again.' : fallback;
    }
  }
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return fallback;
}
