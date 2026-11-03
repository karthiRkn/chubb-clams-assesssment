import { HttpErrorResponse } from '@angular/common/http';
import { inject, signal, Signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap, tap } from 'rxjs';
import { Claim } from '../models';
import { ClaimService } from '../services/claim.service';
import { errorMessage } from '../utils/error-message';

export interface LoadError {
  message: string;
  /** The claim does not exist - or is outside the caller's scope, which the backend deliberately answers the same way. */
  notFound: boolean;
}

/**
 * Loads one claim (GET /claims/{claimId}) and reloads it whenever the `claimId` signal changes.
 * Call from a component constructor (it uses `inject` and ties the subscription to the component's lifetime).
 */
export function createClaimLoader(claimId: Signal<string>) {
  const claims = inject(ClaimService);
  const claim = signal<Claim | null>(null);
  const error = signal<LoadError | null>(null);

  toObservable(claimId)
    .pipe(
      tap(() => {
        claim.set(null);
        error.set(null);
      }),
      switchMap((id) =>
        claims.get(id).pipe(
          catchError((err: unknown) => {
            error.set({ message: errorMessage(err), notFound: err instanceof HttpErrorResponse && err.status === 404 });
            return of(null);
          }),
        ),
      ),
      takeUntilDestroyed(),
    )
    .subscribe((loaded) => {
      if (loaded) {
        claim.set(loaded);
      }
    });

  return { claim: claim.asReadonly(), error: error.asReadonly() };
}
