import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, map } from 'rxjs';
import { ClaimStatus, ClaimSummary } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ClaimService } from '../../../services/claim.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { StatusChip } from '../../../shared/status-chip/status-chip';
import { errorMessage } from '../../../utils/error-message';
import { MoneyPipe } from '../../../shared/money.pipe';

interface OfficerDashboardData {
  queue: number;
  notStarted: number;
  underReview: number;
  waiting: number;
  toSettle: number;
  newest: ClaimSummary[];
  mine: ClaimSummary[];
}

/**
 * Officer home. Every number is a `totalElements` read from a size-1 query on GET /officer/claims, so it is exact:
 * the unassigned queue, and the officer's own claims split by status.
 */
@Component({
  selector: 'app-officer-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    StatCard,
    StatusChip,
  ],
  template: `
    <div class="page">
      <app-page-header [title]="'Hello, ' + (auth.username() ?? '')" subtitle="Your workload at a glance.">
        <a mat-flat-button routerLink="/officer/queue"><mat-icon>inbox</mat-icon>Open the queue</a>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load your dashboard" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (data(); as d) {
        <div class="stat-grid">
          <app-stat-card label="In the queue" [value]="d.queue" icon="inbox" [tone]="d.queue > 0 ? 'attention' : 'default'" link="/officer/queue" hint="Unassigned claims" />
          <app-stat-card label="Not started" [value]="d.notStarted" icon="play_circle" link="/officer/assigned" [queryParams]="{ status: 'SUBMITTED' }" hint="Assigned, review not begun" />
          <app-stat-card label="Under review" [value]="d.underReview" icon="manage_search" link="/officer/assigned" [queryParams]="{ status: 'UNDER_REVIEW' }" hint="Ready for your decision" />
          <app-stat-card label="Waiting for claimant" [value]="d.waiting" icon="hourglass_top" link="/officer/assigned" [queryParams]="{ status: 'MORE_INFO_REQUIRED' }" hint="More information requested" />
          <app-stat-card label="Ready to settle" [value]="d.toSettle" icon="paid" [tone]="d.toSettle > 0 ? 'good' : 'default'" link="/officer/assigned" [queryParams]="{ status: 'APPROVED' }" hint="Approved, payment to record" />
        </div>

        <div class="two-col">
          <section class="panel" aria-label="Newest in the queue">
            <div class="panel-head">
              <h2>Newest in the queue</h2>
              <a mat-button routerLink="/officer/queue">View queue</a>
            </div>
            @if (d.newest.length === 0) {
              <app-empty-state icon="inbox" title="The queue is empty" message="There is nothing to pick up right now." />
            } @else {
              <ul class="rows">
                @for (claim of d.newest; track claim.claimId) {
                  <li>
                    <a class="row" [routerLink]="['/officer/claims', claim.claimId]">
                      <span class="main">
                        <span class="mono ref">{{ claim.claimId }}</span>
                        <span class="muted">{{ claim.claimantName }} · {{ claim.createdAt | date: 'mediumDate' }}</span>
                      </span>
                      <span class="nowrap">{{ claim.claimedAmount | money: claim.currency }}</span>
                    </a>
                  </li>
                }
              </ul>
            }
          </section>

          <section class="panel" aria-label="My latest claims">
            <div class="panel-head">
              <h2>My latest claims</h2>
              <a mat-button routerLink="/officer/assigned">View all</a>
            </div>
            @if (d.mine.length === 0) {
              <app-empty-state icon="assignment_ind" title="Nothing assigned to you" message="Take a claim from the queue to get started." />
            } @else {
              <ul class="rows">
                @for (claim of d.mine; track claim.claimId) {
                  <li>
                    <a class="row" [routerLink]="['/officer/claims', claim.claimId]">
                      <span class="main">
                        <span class="mono ref">{{ claim.claimId }}</span>
                        <span class="muted">{{ claim.claimantName }}</span>
                      </span>
                      <app-status-chip [status]="claim.status" />
                    </a>
                  </li>
                }
              </ul>
            }
          </section>
        </div>
      } @else {
        <app-loading-spinner label="Loading your dashboard…" />
      }
    </div>
  `,
  styles: `
    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    .panel-head h2 {
      margin: 0;
    }
    .rows {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 12px;
      padding: 12px 8px;
      border-top: 1px solid var(--app-border);
      color: inherit;
      text-decoration: none;
    }
    li:first-child .row {
      border-top: 0;
    }
    .row:hover {
      background: var(--mat-sys-surface-container-low);
    }
    .main {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .ref {
      font-weight: 500;
    }
  `,
})
export class OfficerDashboard implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly claims = inject(ClaimService);

  protected readonly data = signal<OfficerDashboardData | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const me = this.auth.username();
    if (!me) {
      return;
    }
    this.error.set(null);

    const mineWith = (status: ClaimStatus) =>
      this.claims.listStaff({ assignedOfficerId: me, status, size: 1 }).pipe(map((page) => page.totalElements));

    forkJoin({
      queue: this.claims.listStaff({ assigned: false, size: 1 }).pipe(map((page) => page.totalElements)),
      notStarted: mineWith('SUBMITTED'),
      underReview: mineWith('UNDER_REVIEW'),
      waiting: mineWith('MORE_INFO_REQUIRED'),
      toSettle: mineWith('APPROVED'),
      newest: this.claims.listStaff({ assigned: false, size: 5 }).pipe(map((page) => page.content)),
      mine: this.claims.listStaff({ assignedOfficerId: me, size: 5 }).pipe(map((page) => page.content)),
    }).subscribe({
      next: (result) => this.data.set(result),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }
}
