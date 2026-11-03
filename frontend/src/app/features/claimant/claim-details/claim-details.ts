import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of, switchMap, tap } from 'rxjs';
import {
  Claim,
  ClaimAssignmentEntry,
  ClaimHistoryEntry,
  ClaimUpdateEntry,
  InformationRequest,
} from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { ClaimService } from '../../../services/claim.service';
import { ClaimDetailCard } from '../../../shared/claim-detail-card/claim-detail-card';
import { ClaimHistoryTabs } from '../../../shared/claim-history-tabs/claim-history-tabs';
import { ClaimTimeline } from '../../../shared/claim-timeline/claim-timeline';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { InformationRequestList } from '../../../shared/information-requests/information-request-list';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { errorMessage } from '../../../utils/error-message';
import { formatMoney } from '../../../shared/money.pipe';

interface ClaimBundle {
  claim: Claim;
  history: ClaimHistoryEntry[];
  assignments: ClaimAssignmentEntry[];
  updates: ClaimUpdateEntry[];
  requests: InformationRequest[];
}

interface LoadError {
  message: string;
  notFound: boolean;
}

/**
 * One claim, end to end: details, status timeline, information requests (with answer forms) and, while it is SUBMITTED,
 * the option to correct it.
 * APIs: GET /claims/{id}, /history, /assignments, /updates (Claim Service); GET /claims/{id}/information-requests (Assessment Service).
 */
@Component({
  selector: 'app-claim-details',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    RouterLink,
    ClaimDetailCard,
    ClaimHistoryTabs,
    ClaimTimeline,
    EmptyState,
    InformationRequestList,
    LoadingSpinner,
    PageHeader,
  ],
  template: `
    <div class="page">
      @if (error(); as err) {
        <app-page-header title="Claim" backLink="/claimant/claims" backLabel="My claims" />
        <app-empty-state
          [icon]="err.notFound ? 'search_off' : 'error_outline'"
          [title]="err.notFound ? 'Claim not found' : 'Could not load this claim'"
          [message]="err.message"
        >
          <button mat-flat-button type="button" (click)="reload()">Try again</button>
          <a mat-stroked-button routerLink="/claimant/claims">Back to my claims</a>
        </app-empty-state>
      } @else if (data(); as d) {
        <app-page-header [title]="d.claim.claimId" subtitle="Claim details and progress" backLink="/claimant/claims" backLabel="My claims">
          @if (canEdit()) {
            <a mat-stroked-button routerLink="edit"><mat-icon>edit</mat-icon>Edit claim</a>
          }
          <button mat-icon-button type="button" (click)="reload()" aria-label="Refresh this claim"><mat-icon>refresh</mat-icon></button>
        </app-page-header>

        @if (banner(); as b) {
          <div class="banner" [class]="b.tone" role="status">
            <mat-icon aria-hidden="true">{{ b.icon }}</mat-icon>
            <span>{{ b.text }}</span>
          </div>
        }

        <app-claim-detail-card [claim]="d.claim" />

        <section class="panel" aria-label="Progress">
          <h2>Progress</h2>
          <app-claim-timeline [status]="d.claim.status" [history]="d.history" />
        </section>

        <section class="panel" aria-label="History">
          <h2>History</h2>
          <app-claim-history-tabs [history]="d.history" [assignments]="d.assignments" [updates]="d.updates" />
        </section>

        <section class="panel" aria-label="Information requests" id="information">
          <h2>Information requests</h2>
          <app-information-request-list [requests]="d.requests" [canAnswer]="true" (answered)="reload()" />
        </section>
      } @else {
        <app-loading-spinner label="Loading claim…" />
      }
    </div>
  `,
  styles: `
    .banner {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border-radius: var(--app-radius);
    }
    .banner.attention { background: var(--status-info-bg); color: var(--status-info-fg); }
    .banner.good { background: var(--status-approved-bg); color: var(--status-approved-fg); }
    .banner.bad { background: var(--status-rejected-bg); color: var(--status-rejected-fg); }
    .banner.neutral { background: var(--status-submitted-bg); color: var(--status-submitted-fg); }
  `,
})
export class ClaimDetails {
  private readonly claims = inject(ClaimService);
  private readonly assessment = inject(AssessmentService);

  /** The `:claimId` route parameter. */
  readonly claimId = input.required<string>();

  private readonly tick = signal(0);
  protected readonly data = signal<ClaimBundle | null>(null);
  protected readonly error = signal<LoadError | null>(null);

  protected readonly canEdit = computed(() => this.data()?.claim.status === 'SUBMITTED');

  /** A one-line "what does this status mean for me" message. */
  protected readonly banner = computed(() => {
    const claim = this.data()?.claim;
    if (!claim) {
      return null;
    }
    switch (claim.status) {
      case 'SUBMITTED':
        return { tone: 'neutral', icon: 'schedule', text: 'Your claim has been received and is waiting for an officer to pick it up. You can still edit it.' };
      case 'UNDER_REVIEW':
        return { tone: 'neutral', icon: 'manage_search', text: 'An officer is reviewing your claim.' };
      case 'MORE_INFO_REQUIRED':
        return { tone: 'attention', icon: 'help', text: 'The claims officer needs more information from you. Please answer the request below.' };
      case 'APPROVED':
        return {
          tone: 'good',
          icon: 'check_circle',
          text: `Approved for ${formatMoney(claim.approvedAmount, claim.currency)}. Settlement is being arranged.`,
        };
      case 'SETTLED':
        return { tone: 'good', icon: 'paid', text: 'Your claim has been settled.' };
      case 'REJECTED':
        return { tone: 'bad', icon: 'cancel', text: 'Your claim was not approved. The reason is shown in the details below.' };
    }
  });

  constructor() {
    toObservable(computed(() => ({ id: this.claimId(), tick: this.tick() })))
      .pipe(
        tap(({ id }) => {
          this.error.set(null);
          if (this.data()?.claim.claimId !== id) {
            this.data.set(null);
          }
        }),
        switchMap(({ id }) =>
          forkJoin({
            claim: this.claims.get(id),
            history: this.claims.history(id),
            assignments: this.claims.assignments(id),
            updates: this.claims.updates(id),
            requests: this.assessment.informationRequests(id),
          }).pipe(
            catchError((err: unknown) => {
              this.error.set({
                message: errorMessage(err),
                notFound: err instanceof HttpErrorResponse && err.status === 404,
              });
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((bundle) => {
        if (bundle) {
          this.data.set(bundle);
        }
      });
  }

  protected reload(): void {
    this.tick.update((n) => n + 1);
  }
}
