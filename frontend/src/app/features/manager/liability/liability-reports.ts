import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { CLAIM_STATUS_LABELS, CLAIM_STATUSES, ManagementSummary } from '../../../models';
import { ManagementService } from '../../../services/management.service';
import { NotificationService } from '../../../services/notification.service';
import { BarChart } from '../../../shared/chart/bar-chart';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { downloadCsv } from '../../../utils/csv';
import { errorMessage } from '../../../utils/error-message';
import { statusCounts } from '../metrics';
import { MoneyPipe } from '../../../shared/money.pipe';

/**
 * Outstanding liability: GET /management/summary.
 * A claim not yet decided counts at its claimed amount, an APPROVED one at its approved amount, a settled or rejected one
 * not at all. Each currency is reported on its own: amounts in different currencies are never added, and no exchange
 * rates exist, so there is deliberately no grand total and no chart that would put two currencies on one axis.
 */
@Component({
  selector: 'app-liability-reports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    BarChart,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    StatCard,
  ],
  template: `
    <div class="page">
      <app-page-header title="Liability reports" subtitle="What the outstanding claims could cost, currency by currency.">
        <button mat-stroked-button type="button" (click)="load()"><mat-icon>refresh</mat-icon>Refresh</button>
        <button mat-flat-button type="button" [disabled]="!summary()" (click)="exportCsv()">
          <mat-icon>download</mat-icon>Export CSV
        </button>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load the report" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (summary(); as s) {
        <div [class.stale]="loading()" class="stack">
          <div class="note" role="note">
            <mat-icon aria-hidden="true">info</mat-icon>
            <span>{{ s.liabilityNote }} Generated {{ s.generatedAt | date: 'medium' }}.</span>
          </div>

          @if (s.outstandingLiabilityByCurrency.length === 0) {
            <app-empty-state
              icon="account_balance"
              title="No outstanding liability"
              message="Every claim is either settled or rejected, or none has been submitted yet."
            />
          } @else {
            <div class="stat-grid">
              @for (row of s.outstandingLiabilityByCurrency; track row.currency) {
                <app-stat-card
                  [label]="row.currency + ' estimated liability'"
                  [value]="(row.estimatedLiability | money: row.currency) ?? ''"
                  icon="account_balance"
                  [hint]="row.claimCount + (row.claimCount === 1 ? ' outstanding claim' : ' outstanding claims')"
                />
              }
            </div>

            <section aria-label="Liability table">
              <h2>By currency</h2>
              <div class="table-wrap">
                <table mat-table [dataSource]="s.outstandingLiabilityByCurrency" aria-label="Estimated liability by currency">
                  <ng-container matColumnDef="currency">
                    <th mat-header-cell *matHeaderCellDef>Currency</th>
                    <td mat-cell *matCellDef="let row"><strong>{{ row.currency }}</strong></td>
                  </ng-container>
                  <ng-container matColumnDef="claimCount">
                    <th mat-header-cell *matHeaderCellDef class="num">Outstanding claims</th>
                    <td mat-cell *matCellDef="let row" class="num">{{ row.claimCount }}</td>
                  </ng-container>
                  <ng-container matColumnDef="estimatedLiability">
                    <th mat-header-cell *matHeaderCellDef class="num">Estimated liability</th>
                    <td mat-cell *matCellDef="let row" class="num nowrap">
                      {{ row.estimatedLiability | money: row.currency }}
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="columns"></tr>
                  <tr mat-row *matRowDef="let row; columns: columns"></tr>
                </table>
              </div>
            </section>
          }

          <section class="panel" aria-label="Claims by status">
            <app-bar-chart
              title="Claims by status"
              [subtitle]="s.outstandingClaimCount + ' outstanding in total'"
              ariaLabel="Horizontal bar chart of the number of claims in each status"
              categoryLabel="Status"
              [labels]="status().labels"
              [series]="[{ name: 'Claims', values: status().values, slot: 0 }]"
            />
          </section>
        </div>
      } @else {
        <app-loading-spinner label="Loading the report…" />
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
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border-radius: var(--app-radius);
      background: var(--status-submitted-bg);
      color: var(--status-submitted-fg);
    }
    .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class LiabilityReports implements OnInit {
  private readonly management = inject(ManagementService);
  private readonly notifications = inject(NotificationService);

  protected readonly columns = ['currency', 'claimCount', 'estimatedLiability'];
  protected readonly summary = signal<ManagementSummary | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly status = computed(() => statusCounts(this.summary()?.countsByStatus ?? {}));

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.loading.set(true);
    this.management.summary().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (!this.summary()) {
          this.error.set(errorMessage(err));
        }
      },
    });
  }

  /** Two small tables in one file: liability by currency, then claims by status. */
  protected exportCsv(): void {
    const s = this.summary();
    if (!s) {
      return;
    }
    downloadCsv(`liability-report-${s.generatedAt.slice(0, 10)}.csv`, [
      ['Estimated liability by currency'],
      ['Currency', 'Outstanding claims', 'Estimated liability'],
      ...s.outstandingLiabilityByCurrency.map((r) => [r.currency, r.claimCount, r.estimatedLiability.toFixed(2)]),
      [],
      ['Claims by status'],
      ['Status', 'Claims'],
      ...CLAIM_STATUSES.map((status) => [CLAIM_STATUS_LABELS[status], s.countsByStatus[status] ?? 0]),
      [],
      [s.liabilityNote],
    ]);
    this.notifications.success('Report downloaded.');
  }
}
