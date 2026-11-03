import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, map, Observable } from 'rxjs';
import { CLAIM_STATUSES, ClaimStatus, ClaimSummary } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ClaimService } from '../../../services/claim.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { StatusChip } from '../../../shared/status-chip/status-chip';
import { errorMessage } from '../../../utils/error-message';
import { MoneyPipe } from '../../../shared/money.pipe';

interface DashboardData {
  counts: Record<ClaimStatus, number>;
  recent: ClaimSummary[];
}

/**
 * Claimant home. Exact counts come from one cheap query per status (`size=1`, read `totalElements`), so they stay correct
 * however many claims there are; the five newest claims come from one more query.
 */
@Component({
  selector: 'app-claimant-dashboard',
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
      <app-page-header [title]="'Hello, ' + (auth.username() ?? '')" subtitle="Here is where your claims stand.">
        <a mat-flat-button routerLink="/claimant/new-claim"><mat-icon>add</mat-icon>Report a claim</a>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load your dashboard" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (data(); as d) {
        <div class="stat-grid">
          <app-stat-card label="Total claims" [value]="total()" icon="description" link="/claimant/claims" />
          <app-stat-card label="In progress" [value]="inProgress()" icon="hourglass_top" hint="Submitted or under review" />
          <app-stat-card
            label="Need your information"
            [value]="d.counts.MORE_INFO_REQUIRED"
            icon="help"
            [tone]="d.counts.MORE_INFO_REQUIRED > 0 ? 'attention' : 'default'"
            link="/claimant/information-requests"
            hint="Answer to keep things moving"
          />
          <app-stat-card label="Approved" [value]="d.counts.APPROVED + d.counts.SETTLED" icon="check_circle" tone="good" hint="Approved or settled" />
          <app-stat-card label="Rejected" [value]="d.counts.REJECTED" icon="cancel" [tone]="d.counts.REJECTED > 0 ? 'bad' : 'default'" />
        </div>

        <section class="panel" aria-label="Recent claims">
          <div class="panel-head">
            <h2>Recent claims</h2>
            <a mat-button routerLink="/claimant/claims">View all</a>
          </div>

          @if (d.recent.length === 0) {
            <app-empty-state
              icon="description"
              title="You have not submitted a claim yet"
              message="Report an incident and you will be able to follow it here from submission to settlement."
            >
              <a mat-flat-button routerLink="/claimant/new-claim"><mat-icon>add</mat-icon>Report a claim</a>
            </app-empty-state>
          } @else {
            <ul class="recent">
              @for (claim of d.recent; track claim.claimId) {
                <li>
                  <a class="row" [routerLink]="['/claimant/claims', claim.claimId]">
                    <span class="main">
                      <span class="mono ref">{{ claim.claimId }}</span>
                      <span class="muted">
                        {{ claim.claimType === 'MOTOR' ? 'Motor' : 'Property' }} · {{ claim.createdAt | date: 'mediumDate' }}
                      </span>
                    </span>
                    <span class="amount nowrap">{{ claim.claimedAmount | money: claim.currency }}</span>
                    <app-status-chip [status]="claim.status" />
                  </a>
                </li>
              }
            </ul>
          }
        </section>
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
    .recent {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
      gap: 16px;
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
    @media (max-width: 599px) {
      .row {
        grid-template-columns: minmax(0, 1fr) auto;
      }
      .amount {
        display: none;
      }
    }
  `,
})
export class ClaimantDashboard implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly claims = inject(ClaimService);

  protected readonly data = signal<DashboardData | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly total = computed(() => this.sum(this.data()?.counts, CLAIM_STATUSES));
  protected readonly inProgress = computed(() => this.sum(this.data()?.counts, ['SUBMITTED', 'UNDER_REVIEW']));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    const perStatus = {} as Record<ClaimStatus, Observable<number>>;
    for (const status of CLAIM_STATUSES) {
      perStatus[status] = this.claims.list({ status, size: 1 }).pipe(map((page) => page.totalElements));
    }

    forkJoin({
      counts: forkJoin(perStatus),
      recent: this.claims.list({ size: 5 }).pipe(map((p) => p.content)),
    }).subscribe({
      next: (result) => this.data.set(result),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }

  private sum(counts: Record<ClaimStatus, number> | undefined, statuses: readonly ClaimStatus[]): number {
    return counts ? statuses.reduce((total, status) => total + counts[status], 0) : 0;
  }
}
