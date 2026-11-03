import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, map } from 'rxjs';
import { ManagementSummary, OfficerMetrics } from '../../../models';
import { ClaimService } from '../../../services/claim.service';
import { ManagementService } from '../../../services/management.service';
import { BarChart } from '../../../shared/chart/bar-chart';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { errorMessage } from '../../../utils/error-message';
import { statusCounts, workloadSeries } from '../metrics';
import { MoneyPipe } from '../../../shared/money.pipe';

interface ManagerDashboardData {
  summary: ManagementSummary;
  metrics: OfficerMetrics;
  unassigned: number;
}

/**
 * Manager home: headline numbers, estimated liability per currency (each currency on its own tile, never added together),
 * claims by status and the officers' open workload.
 * APIs: GET /management/summary, GET /management/officers, GET /officer/claims?assigned=false (count only).
 */
@Component({
  selector: 'app-manager-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    BarChart,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    StatCard,
  ],
  template: `
    <div class="page">
      <app-page-header title="Overview" subtitle="Outstanding claims, liability and team workload.">
        <button mat-icon-button type="button" (click)="load()" aria-label="Refresh"><mat-icon>refresh</mat-icon></button>
        <a mat-flat-button routerLink="/manager/assignments"><mat-icon>assignment</mat-icon>Assign claims</a>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load the overview" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (data(); as d) {
        <div [class.stale]="loading()" class="stack">
          <div class="stat-grid">
            <app-stat-card
              label="Outstanding claims"
              [value]="d.summary.outstandingClaimCount"
              icon="pending_actions"
              link="/manager/liability"
              hint="Not yet settled or rejected"
            />
            <app-stat-card
              label="Waiting to be assigned"
              [value]="d.unassigned"
              icon="assignment_late"
              [tone]="d.unassigned > 0 ? 'attention' : 'default'"
              link="/manager/assignments"
              hint="No officer yet"
            />
            <app-stat-card
              label="Officers with open work"
              [value]="activeOfficers()"
              icon="groups"
              link="/manager/officers"
              [hint]="d.metrics.officers.length + ' officers on record'"
            />
            <app-stat-card
              label="Claim count"
              [value]="claimCount()"
              icon="description"
              link="/manager/claims"
              hint="All claims, any status"
            />
          </div>

          <section class="panel" aria-label="Estimated liability">
            <h2>Estimated liability by currency</h2>
            @if (d.summary.outstandingLiabilityByCurrency.length === 0) {
              <p class="muted">No outstanding liability.</p>
            } @else {
              <div class="stat-grid">
                @for (row of d.summary.outstandingLiabilityByCurrency; track row.currency) {
                  <app-stat-card
                    [label]="row.currency"
                    [value]="(row.estimatedLiability | money: row.currency) ?? ''"
                    icon="account_balance"
                    [hint]="row.claimCount + (row.claimCount === 1 ? ' outstanding claim' : ' outstanding claims')"
                    link="/manager/liability"
                  />
                }
              </div>
            }
            <p class="note muted">{{ d.summary.liabilityNote }} Updated {{ d.summary.generatedAt | date: 'shortTime' }}.</p>
          </section>

          <div class="two-col">
            <section class="panel" aria-label="Claims by status">
              <app-bar-chart
                title="Claims by status"
                subtitle="All claims in the system"
                ariaLabel="Horizontal bar chart of the number of claims in each status"
                categoryLabel="Status"
                [labels]="status().labels"
                [series]="[{ name: 'Claims', values: status().values, slot: 0 }]"
              />
            </section>

            <section class="panel" aria-label="Open workload by officer">
              <app-bar-chart
                title="Open workload by officer"
                subtitle="Claims each officer still has to progress"
                ariaLabel="Stacked horizontal bar chart of each officer's open claims by status"
                categoryLabel="Officer"
                [stacked]="true"
                [labels]="officerNames()"
                [series]="workload()"
                emptyText="No officer has open claims yet."
              />
            </section>
          </div>
        </div>
      } @else {
        <app-loading-spinner label="Loading the overview…" />
      }
    </div>
  `,
  styles: `
    .stack {
      display: flex;
      flex-direction: column;
      gap: 24px;
      transition: opacity 0.15s;
    }
    .stale {
      opacity: 0.6;
    }
    h2 {
      font: var(--mat-sys-title-medium);
      margin-bottom: 12px;
    }
    .note {
      margin-top: 12px;
      font: var(--mat-sys-body-small);
    }
  `,
})
export class ManagerDashboard implements OnInit {
  private readonly management = inject(ManagementService);
  private readonly claims = inject(ClaimService);

  protected readonly data = signal<ManagerDashboardData | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  protected readonly status = computed(() => statusCounts(this.data()?.summary.countsByStatus ?? {}));
  protected readonly claimCount = computed(() =>
    Object.values(this.data()?.summary.countsByStatus ?? {}).reduce((total, n) => total + n, 0),
  );
  protected readonly activeOfficers = computed(
    () => this.data()?.metrics.officers.filter((o) => o.openClaimCount > 0).length ?? 0,
  );
  // Only officers with open work appear in the workload chart.
  private readonly working = computed(() => this.data()?.metrics.officers.filter((o) => o.openClaimCount > 0) ?? []);
  protected readonly officerNames = computed(() => this.working().map((o) => o.officerId));
  protected readonly workload = computed(() => workloadSeries(this.working()));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.loading.set(true);
    forkJoin({
      summary: this.management.summary(),
      metrics: this.management.officers(),
      unassigned: this.claims.listStaff({ assigned: false, size: 1 }).pipe(map((page) => page.totalElements)),
    }).subscribe({
      next: (result) => {
        this.data.set(result);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        // Keep what is already on screen if a refresh fails; only a first load shows the error state.
        if (!this.data()) {
          this.error.set(errorMessage(err));
        }
      },
    });
  }
}
