import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { AssessmentEntry, ClaimSummary } from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { AuthService } from '../../../services/auth.service';
import { ClaimBrief } from '../../../shared/claim-brief/claim-brief';
import { createClaimLoader } from '../../../shared/claim-loader';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { errorMessage } from '../../../utils/error-message';
import { AssessmentEntries } from './assessment-entries';

/**
 * Pick one of your claims to see its assessment trail. Lists GET /officer/claims?assignedOfficerId=<me>;
 * each row opens /officer/claims/{id}/assessments.
 */
@Component({
  selector: 'app-assessment-history-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header
        title="Assessment history"
        subtitle="Choose a claim to see its review notes, decision and settlement. This trail is for officers only."
      />
      <app-claim-list
        source="staff"
        [fixed]="fixed()"
        [filters]="['status', 'claimType']"
        [showClaimant]="true"
        [detailLink]="detailLink"
        emptyTitle="No claims assigned to you"
        emptyMessage="Claims you take from the queue will appear here, with their assessment history."
      />
    </div>
  `,
})
export class AssessmentHistoryPicker {
  private readonly auth = inject(AuthService);
  protected readonly fixed = computed(() => ({ assignedOfficerId: this.auth.username() }));
  protected readonly detailLink = (claim: ClaimSummary) => ['/officer/claims', claim.claimId, 'assessments'];
}

/** The assessment trail of one claim: GET /officer/claims/{id}/assessments (CLAIMS_OFFICER only, never shown to claimants). */
@Component({
  selector: 'app-assessment-history',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, RouterLink, AssessmentEntries, ClaimBrief, EmptyState, LoadingSpinner, PageHeader],
  template: `
    <div class="page narrow">
      <app-page-header
        title="Assessment history"
        [subtitle]="claimId()"
        [backLink]="['/officer/claims', claimId()]"
        backLabel="Back to claim"
      />

      @if (loader.error(); as err) {
        <app-empty-state icon="search_off" title="Claim not found" [message]="err.message">
          <a mat-flat-button routerLink="/officer/assessments">Choose another claim</a>
        </app-empty-state>
      } @else if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load the history" [message]="message" />
      } @else if (loader.claim(); as claim) {
        <app-claim-brief [claim]="claim" />
        @if (entries(); as list) {
          <section class="panel" aria-label="Assessment entries">
            <app-assessment-entries [entries]="list" />
          </section>
        } @else {
          <app-loading-spinner label="Loading history…" />
        }
      } @else {
        <app-loading-spinner label="Loading claim…" />
      }
    </div>
  `,
  styles: `
    .narrow {
      max-width: 860px;
    }
  `,
})
export class AssessmentHistory {
  private readonly assessment = inject(AssessmentService);

  /** The `:claimId` route parameter. */
  readonly claimId = input.required<string>();
  protected readonly loader = createClaimLoader(this.claimId);

  protected readonly entries = signal<AssessmentEntry[] | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    toObservable(this.claimId)
      .pipe(
        tap(() => {
          this.entries.set(null);
          this.error.set(null);
        }),
        switchMap((id) =>
          this.assessment.assessments(id).pipe(
            catchError((err: unknown) => {
              this.error.set(errorMessage(err));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((list) => {
        if (list) {
          this.entries.set(list);
        }
      });
  }
}
