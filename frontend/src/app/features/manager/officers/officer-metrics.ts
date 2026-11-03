import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, OnInit, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { OfficerEntry, OfficerMetrics } from '../../../models';
import { ManagementService } from '../../../services/management.service';
import { BarChart } from '../../../shared/chart/bar-chart';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { errorMessage } from '../../../utils/error-message';
import { decisionSeries, workloadSeries } from '../metrics';
import { MoneyPipe } from '../../../shared/money.pipe';

/**
 * Per-officer workload and decisions: GET /management/officers.
 * Open work counts an APPROVED claim until it is settled. There is no time-based metric in the backend (no reassignment
 * history either), so none is shown. Amounts stay in their own currency; nothing is summed across currencies.
 */
@Component({
  selector: 'app-officer-metrics',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatSortModule,
    MatTableModule,
    BarChart,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    StatCard,
  ],
  template: `
    <div class="page">
      <app-page-header title="Officer metrics" subtitle="Open workload and decisions for each claims officer.">
        <button mat-stroked-button type="button" (click)="load()"><mat-icon>refresh</mat-icon>Refresh</button>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load officer metrics" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (metrics(); as m) {
        @if (m.officers.length === 0) {
          <app-empty-state
            icon="groups"
            title="No officer activity yet"
            message="Officers appear here once claims are assigned to them or they make a decision."
          />
        } @else {
          <div [class.stale]="loading()" class="stack">
            <div class="stat-grid">
              <app-stat-card label="Workload" [value]="totals().open" icon="pending_actions" [hint]="'Open claims across ' + m.officers.length + (m.officers.length === 1 ? ' officer' : ' officers')" />
              <app-stat-card label="Approvals" [value]="totals().approved" icon="check_circle" hint="Approved, settled or not" />
              <app-stat-card label="Settlements" [value]="totals().settled" icon="paid" tone="good" hint="Approved and paid out" />
              <app-stat-card label="Rejections" [value]="totals().rejected" icon="cancel" [tone]="totals().rejected > 0 ? 'bad' : 'default'" hint="Final, never settled" />
            </div>

            <div class="two-col">
              <section class="panel" aria-label="Open workload">
                <app-bar-chart
                  title="Open workload"
                  subtitle="Claims each officer still has to progress, by status"
                  ariaLabel="Stacked horizontal bar chart of each officer's open claims by status"
                  categoryLabel="Officer"
                  [stacked]="true"
                  [labels]="names()"
                  [series]="workload()"
                />
              </section>
              <section class="panel" aria-label="Decisions">
                <app-bar-chart
                  title="Decisions"
                  subtitle="Approved and rejected claims per officer"
                  ariaLabel="Stacked horizontal bar chart of each officer's approved and rejected decisions"
                  categoryLabel="Officer"
                  [stacked]="true"
                  [labels]="names()"
                  [series]="decisions()"
                  emptyText="No decisions have been made yet."
                />
              </section>
            </div>

            <section aria-label="Officer table">
              <h2>Details</h2>
              <div class="table-wrap">
                <table mat-table matSort [dataSource]="dataSource" aria-label="Officer metrics">
                  <ng-container matColumnDef="officerId">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header>Officer</th>
                    <td mat-cell *matCellDef="let o"><strong>{{ o.officerId }}</strong></td>
                  </ng-container>
                  <ng-container matColumnDef="openClaimCount">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header class="num">Open</th>
                    <td mat-cell *matCellDef="let o" class="num">{{ o.openClaimCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="decidedCount">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header class="num hide-sm">Decided</th>
                    <td mat-cell *matCellDef="let o" class="num hide-sm">{{ o.decidedCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="approvedCount">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header class="num hide-sm">Approved</th>
                    <td mat-cell *matCellDef="let o" class="num hide-sm">{{ o.approvedCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="settledCount">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header class="num hide-md">Settled</th>
                    <td mat-cell *matCellDef="let o" class="num hide-md">{{ o.settledCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="rejectedCount">
                    <th mat-header-cell *matHeaderCellDef mat-sort-header class="num hide-sm">Rejected</th>
                    <td mat-cell *matCellDef="let o" class="num hide-sm">{{ o.rejectedCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="openAmount">
                    <th mat-header-cell *matHeaderCellDef class="hide-md">Open claimed amount</th>
                    <td mat-cell *matCellDef="let o" class="hide-md">
                      @for (t of o.openClaimedAmountByCurrency; track t.currency) {
                        <div class="nowrap">
                          {{ t.amount | money: t.currency }} <span class="muted">({{ t.claimCount }})</span>
                        </div>
                      } @empty {
                        <span class="muted">—</span>
                      }
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="approvedAmount">
                    <th mat-header-cell *matHeaderCellDef class="hide-md">Approved amount</th>
                    <td mat-cell *matCellDef="let o" class="hide-md">
                      @for (t of o.approvedAmountByCurrency; track t.currency) {
                        <div class="nowrap">
                          {{ t.amount | money: t.currency }} <span class="muted">({{ t.claimCount }})</span>
                        </div>
                      } @empty {
                        <span class="muted">—</span>
                      }
                    </td>
                  </ng-container>

                  <tr mat-header-row *matHeaderRowDef="columns"></tr>
                  <tr mat-row *matRowDef="let row; columns: columns"></tr>
                </table>
              </div>
              <p class="note muted">{{ m.note }} Updated {{ m.generatedAt | date: 'shortTime' }}.</p>
            </section>
          </div>
        }
      } @else {
        <app-loading-spinner label="Loading officer metrics…" />
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
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .note {
      margin-top: 8px;
      font: var(--mat-sys-body-small);
    }
  `,
})
export class OfficerMetricsPage implements OnInit {
  private readonly management = inject(ManagementService);

  protected readonly columns = [
    'officerId',
    'openClaimCount',
    'decidedCount',
    'approvedCount',
    'settledCount',
    'rejectedCount',
    'openAmount',
    'approvedAmount',
  ];

  protected readonly metrics = signal<OfficerMetrics | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly dataSource = new MatTableDataSource<OfficerEntry>([]);
  private readonly sort = viewChild(MatSort);

  private readonly working = computed(() => this.metrics()?.officers.filter((o) => o.openClaimCount > 0 || o.decidedCount > 0) ?? []);
  protected readonly names = computed(() => this.working().map((o) => o.officerId));
  protected readonly workload = computed(() => workloadSeries(this.working()));
  protected readonly decisions = computed(() => decisionSeries(this.working()));
  protected readonly totals = computed(() => {
    const officers = this.metrics()?.officers ?? [];
    return {
      open: officers.reduce((n, o) => n + o.openClaimCount, 0),
      approved: officers.reduce((n, o) => n + o.approvedCount, 0),
      settled: officers.reduce((n, o) => n + o.settledCount, 0),
      rejected: officers.reduce((n, o) => n + o.rejectedCount, 0),
    };
  });

  constructor() {
    // The sort header only exists once there is data on screen, so hook it up whenever it appears.
    effect(() => {
      this.dataSource.sort = this.sort() ?? null;
    });
  }

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.loading.set(true);
    this.management.officers().subscribe({
      next: (metrics) => {
        this.metrics.set(metrics);
        this.dataSource.data = metrics.officers;
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (!this.metrics()) {
          this.error.set(errorMessage(err));
        }
      },
    });
  }
}
