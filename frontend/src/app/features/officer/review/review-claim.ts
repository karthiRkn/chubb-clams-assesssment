import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, forkJoin, of, switchMap, tap } from 'rxjs';
import {
  AssessmentEntry,
  Claim,
  ClaimAssignmentEntry,
  ClaimHistoryEntry,
  ClaimUpdateEntry,
  InformationRequest,
  MAX_NOTE_LENGTH,
  TERMINAL_STATUSES,
} from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { AuthService } from '../../../services/auth.service';
import { ClaimService } from '../../../services/claim.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { ClaimDetailCard } from '../../../shared/claim-detail-card/claim-detail-card';
import { ClaimHistoryTabs } from '../../../shared/claim-history-tabs/claim-history-tabs';
import { ClaimTimeline } from '../../../shared/claim-timeline/claim-timeline';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { InformationRequestsTable } from '../../../shared/information-requests/information-requests-table';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { errorMessage } from '../../../utils/error-message';
import { AssessmentEntries } from '../assessment-history/assessment-entries';
import { ReviewActions } from './review-actions';

interface ReviewBundle {
  claim: Claim;
  history: ClaimHistoryEntry[];
  assignments: ClaimAssignmentEntry[];
  updates: ClaimUpdateEntry[];
  requests: InformationRequest[];
  assessments: AssessmentEntry[];
}

interface LoadError {
  message: string;
  notFound: boolean;
}

/**
 * The officer's workspace for one claim. Which actions appear depends on the claim's status and who it is assigned to:
 *
 *   unassigned (SUBMITTED)              Take claim / Start review (starting a review also takes the claim)
 *   mine, SUBMITTED                     Start review (optional note)
 *   mine, MORE_INFO_REQUIRED, none open Resume review
 *   mine, UNDER_REVIEW                  Review actions beside each other: Approve, Reject (inline) and Request Information (dialog)
 *   mine, APPROVED                      Settle (its own page)
 *
 * APIs: GET /claims/{id} (+ /history, /assignments, /updates), GET /officer/claims/{id}/assessments,
 * GET /claims/{id}/information-requests, POST /officer/claims/{id}/assign, /review, /decision and /information-requests.
 */
@Component({
  selector: 'app-review-claim',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    RouterLink,
    AssessmentEntries,
    ClaimDetailCard,
    ClaimHistoryTabs,
    ClaimTimeline,
    EmptyState,
    InformationRequestsTable,
    LoadingSpinner,
    PageHeader,
    ReviewActions,
  ],
  template: `
    <div class="page">
      @if (error(); as err) {
        <app-page-header title="Claim" backLink="/officer/assigned" backLabel="Assigned claims" />
        <app-empty-state
          [icon]="err.notFound ? 'search_off' : 'error_outline'"
          [title]="err.notFound ? 'Claim not found' : 'Could not load this claim'"
          [message]="
            err.notFound
              ? 'It does not exist, or it is assigned to another officer.'
              : err.message
          "
        >
          @if (!err.notFound) {
            <button mat-flat-button type="button" (click)="reload()">Try again</button>
          }
          <a mat-stroked-button routerLink="/officer/queue">Claims queue</a>
        </app-empty-state>
      } @else if (data(); as d) {
        <app-page-header
          [title]="d.claim.claimId"
          subtitle="Review workspace"
          [backLink]="view().mine ? '/officer/assigned' : '/officer/queue'"
          [backLabel]="view().mine ? 'Assigned claims' : 'Claims queue'"
        >
          @if (view().canTake) {
            <button mat-stroked-button type="button" (click)="take(d.claim)"><mat-icon>assignment_ind</mat-icon>Take claim</button>
          }
          @if (view().canSettle) {
            <a mat-flat-button [routerLink]="['settlement']"><mat-icon>paid</mat-icon>Settle claim</a>
          }
          <button mat-icon-button type="button" (click)="reload()" aria-label="Refresh this claim"><mat-icon>refresh</mat-icon></button>
        </app-page-header>

        @if (waitingForClaimant()) {
          <div class="banner attention" role="status">
            <mat-icon aria-hidden="true">hourglass_top</mat-icon>
            <span>
              Waiting for the claimant to answer {{ view().openRequests }}
              {{ view().openRequests === 1 ? 'request' : 'requests' }}. The claim returns to review automatically once all are answered.
            </span>
          </div>
        }

        <app-claim-detail-card [claim]="d.claim" />

        @if (view().canStartReview) {
          <section class="panel" aria-label="Start review">
            <h2>{{ d.claim.status === 'MORE_INFO_REQUIRED' ? 'Resume review' : 'Start review' }}</h2>
            <p class="muted lead">
              @if (view().unassigned) {
                Starting the review also assigns this claim to you.
              } @else {
                Moves the claim to “Under review” so you can decide it.
              }
              Add an optional note; it is kept in the assessment history and is never shown to the claimant.
            </p>
            <mat-form-field class="note">
              <mat-label>Assessment note (optional)</mat-label>
              <textarea matInput rows="3" [formControl]="notes" [maxlength]="maxNote"></textarea>
              <mat-hint align="end">{{ notes.value.length }} / {{ maxNote }}</mat-hint>
            </mat-form-field>
            <div class="actions-end">
              <button mat-flat-button type="button" [disabled]="busy()" (click)="startReview(d.claim)">
                <mat-icon>play_arrow</mat-icon>
                {{ d.claim.status === 'MORE_INFO_REQUIRED' ? 'Resume review' : 'Start review' }}
              </button>
            </div>
          </section>
        }
 <section class="panel" aria-label="Information requests">
          <div class="panel-head">
            <h2>Information Requests</h2>
            <button mat-icon-button type="button" (click)="reload()" aria-label="Refresh information requests">
              <mat-icon>refresh</mat-icon>
            </button>
          </div>
          <app-information-requests-table
            [requests]="d.requests"
            emptyMessage="You have not asked the claimant for anything on this claim."
          />
        </section>

        @if (view().canDecide) {
          <app-review-actions [claim]="d.claim" (changed)="reload()" />
        }



        <section class="panel" aria-label="Progress">
          <h2>Progress</h2>
          <app-claim-timeline [status]="d.claim.status" [history]="d.history" />
        </section>

        <section class="panel" aria-label="History">
          <h2>History</h2>
          <app-claim-history-tabs [history]="d.history" [assignments]="d.assignments" [updates]="d.updates" />
        </section>

        <section class="panel" aria-label="Assessment history">
          <div class="panel-head">
            <h2>Assessment history</h2>
            <a mat-button [routerLink]="['assessments']">Open full history</a>
          </div>
          <app-assessment-entries [entries]="d.assessments" />
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
    .banner.attention {
      background: var(--status-info-bg);
      color: var(--status-info-fg);
    }
    .lead {
      margin-bottom: 12px;
    }
    .note {
      width: 100%;
    }
    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    .panel-head h2 {
      margin: 0;
    }
  `,
})
export class ReviewClaim {
  private readonly auth = inject(AuthService);
  private readonly claims = inject(ClaimService);
  private readonly assessment = inject(AssessmentService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  /** The `:claimId` route parameter. */
  readonly claimId = input.required<string>();

  protected readonly maxNote = MAX_NOTE_LENGTH;
  protected readonly notes = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_NOTE_LENGTH)] });

  private readonly tick = signal(0);
  protected readonly data = signal<ReviewBundle | null>(null);
  protected readonly error = signal<LoadError | null>(null);
  protected readonly busy = signal(false);

  /** What this officer may do with this claim right now. */
  protected readonly view = computed(() => {
    const d = this.data();
    if (!d) {
      return { mine: false, unassigned: false, openRequests: 0, canTake: false, canStartReview: false, canDecide: false, canSettle: false };
    }
    const status = d.claim.status;
    // Every action below is an officer's: the route is already CLAIMS_OFFICER-only, and this keeps the buttons honest if that ever changes.
    const isOfficer = this.auth.hasRole('CLAIMS_OFFICER');
    const mine = isOfficer && d.claim.assignedOfficerId === this.auth.username();
    const unassigned = d.claim.assignedOfficerId === null;
    const openRequests = d.requests.filter((r) => r.status === 'OPEN').length;
    return {
      mine,
      unassigned,
      openRequests,
      canTake: isOfficer && unassigned && !TERMINAL_STATUSES.includes(status),
      canStartReview:
        isOfficer &&
        ((status === 'SUBMITTED' && (mine || unassigned)) ||
          (status === 'MORE_INFO_REQUIRED' && mine && openRequests === 0)),
      canDecide: mine && status === 'UNDER_REVIEW',
      canSettle: mine && status === 'APPROVED',
    };
  });

  protected readonly waitingForClaimant = computed(
    () => this.data()?.claim.status === 'MORE_INFO_REQUIRED' && this.view().openRequests > 0,
  );

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
            assessments: this.assessment.assessments(id),
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

  protected take(claim: Claim): void {
    const me = this.auth.username();
    if (!me) {
      return;
    }
    this.confirm
      .confirm({
        title: `Take claim ${claim.claimId}?`,
        message: 'It will be assigned to you and leave the queue.',
        confirmLabel: 'Take claim',
        icon: 'assignment_ind',
      })
      .pipe(switchMap((ok) => (ok ? this.claims.assign(claim.claimId, me) : EMPTY)))
      .subscribe(() => {
        this.notifications.success(`${claim.claimId} is now assigned to you.`);
        this.reload();
      });
  }

  protected startReview(claim: Claim): void {
    const resuming = claim.status === 'MORE_INFO_REQUIRED';
    const note = this.notes.value.trim();
    this.confirm
      .confirm({
        title: resuming ? 'Resume review?' : 'Start review?',
        message: this.view().unassigned
          ? 'The claim will be assigned to you and moved to “Under review”.'
          : 'The claim will be moved to “Under review”.',
        confirmLabel: resuming ? 'Resume review' : 'Start review',
        icon: 'play_arrow',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.busy.set(true);
          return this.assessment.startReview(claim.claimId, note ? { assessmentNotes: note } : {});
        }),
      )
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.notes.reset('');
          this.notifications.success(`Review of ${claim.claimId} has started.`);
          this.reload();
        },
        error: () => this.busy.set(false),
      });
  }
}
