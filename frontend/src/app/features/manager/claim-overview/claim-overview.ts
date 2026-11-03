import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of, switchMap, tap } from 'rxjs';
import {
  Claim,
  ClaimAssignmentEntry,
  ClaimHistoryEntry,
  ClaimSummary,
  ClaimUpdateEntry,
  InformationRequest,
  TERMINAL_STATUSES,
} from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { ClaimService } from '../../../services/claim.service';
import { ManagementService } from '../../../services/management.service';
import { ClaimDetailCard } from '../../../shared/claim-detail-card/claim-detail-card';
import { ClaimHistoryTabs } from '../../../shared/claim-history-tabs/claim-history-tabs';
import { ClaimTimeline } from '../../../shared/claim-timeline/claim-timeline';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { InformationRequestsTable } from '../../../shared/information-requests/information-requests-table';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { errorMessage } from '../../../utils/error-message';
import { AssignDialog, AssignDialogData } from '../assignments/assign-dialog';

interface Bundle {
  claim: Claim;
  history: ClaimHistoryEntry[];
  assignments: ClaimAssignmentEntry[];
  updates: ClaimUpdateEntry[];
  requests: InformationRequest[];
}

/**
 * Read-only view of any claim for a manager, with the assign / reassign action.
 * A manager may read claims and their histories but not the officers' assessment notes (the backend answers 403).
 */
@Component({
  selector: 'app-claim-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatIconModule,
    RouterLink,
    ClaimDetailCard,
    ClaimHistoryTabs,
    ClaimTimeline,
    EmptyState,
    InformationRequestsTable,
    LoadingSpinner,
    PageHeader,
  ],
  template: `
    <div class="page">
      @if (error(); as err) {
        <app-page-header title="Claim" backLink="/manager/claims" backLabel="Claims" />
        <app-empty-state
          [icon]="err.notFound ? 'search_off' : 'error_outline'"
          [title]="err.notFound ? 'Claim not found' : 'Could not load this claim'"
          [message]="err.message"
        >
          <a mat-flat-button routerLink="/manager/claims">Back to claims</a>
        </app-empty-state>
      } @else if (data(); as d) {
        <app-page-header
          [title]="d.claim.claimId"
          subtitle="Claim overview"
          backLink="/manager/claims"
          backLabel="Claims"
        >
          @if (canAssign()) {
            <button mat-flat-button type="button" (click)="assign(d.claim)">
              <mat-icon>{{ d.claim.assignedOfficerId ? 'swap_horiz' : 'person_add' }}</mat-icon>
              {{ d.claim.assignedOfficerId ? 'Reassign' : 'Assign to an officer' }}
            </button>
          }
        </app-page-header>

        <app-claim-detail-card [claim]="d.claim" />

        <section class="panel" aria-label="Progress">
          <h2>Progress</h2>
          <app-claim-timeline [status]="d.claim.status" [history]="d.history" />
        </section>

        <section class="panel" aria-label="History">
          <h2>History</h2>
          <app-claim-history-tabs [history]="d.history" [assignments]="d.assignments" [updates]="d.updates" />
        </section>

        <section class="panel" aria-label="Information requests">
          <h2>Information Requests</h2>
          <app-information-requests-table [requests]="d.requests" emptyMessage="No information has been requested on this claim." />
        </section>
      } @else {
        <app-loading-spinner label="Loading claim…" />
      }
    </div>
  `,
})
export class ClaimOverview {
  private readonly claims = inject(ClaimService);
  private readonly assessment = inject(AssessmentService);
  private readonly management = inject(ManagementService);
  private readonly dialog = inject(MatDialog);

  /** The `:claimId` route parameter. */
  readonly claimId = input.required<string>();

  private readonly tick = signal(0);
  protected readonly data = signal<Bundle | null>(null);
  protected readonly error = signal<{ message: string; notFound: boolean } | null>(null);

  protected readonly canAssign = computed(() => {
    const claim = this.data()?.claim;
    return !!claim && !TERMINAL_STATUSES.includes(claim.status);
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
              this.error.set({ message: errorMessage(err), notFound: err instanceof HttpErrorResponse && err.status === 404 });
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

  protected assign(claim: Claim): void {
    this.management.officers().subscribe({
      next: (metrics) => this.openDialog(claim, metrics.officers.map((o) => ({ id: o.officerId, open: o.openClaimCount }))),
      // Suggestions are optional: the manager can still type a login name.
      error: () => this.openDialog(claim, []),
    });
  }

  private openDialog(claim: Claim, officers: AssignDialogData['officers']): void {
    const summary: ClaimSummary = {
      id: claim.id,
      claimId: claim.claimId,
      claimantId: claim.claimantId,
      claimantName: claim.claimantName,
      policyNumber: claim.policyNumber,
      market: claim.market,
      claimType: claim.claimType,
      claimedAmount: claim.claimedAmount,
      approvedAmount: claim.approvedAmount,
      currency: claim.currency,
      status: claim.status,
      assignedOfficerId: claim.assignedOfficerId,
      createdAt: claim.createdAt,
    };
    this.dialog
      .open<AssignDialog, AssignDialogData, boolean>(AssignDialog, {
        data: { claim: summary, officers },
        width: '460px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((assigned) => {
        if (assigned) {
          this.tick.update((n) => n + 1);
        }
      });
  }
}
