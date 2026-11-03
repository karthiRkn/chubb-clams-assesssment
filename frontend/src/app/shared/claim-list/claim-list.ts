import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  TemplateRef,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { catchError, Observable, of, switchMap, tap } from 'rxjs';
import { MARKETS } from '../../config/api.config';
import {
  CLAIM_STATUS_LABELS,
  CLAIM_STATUSES,
  CLAIM_TYPES,
  ClaimListParams,
  ClaimStatus,
  ClaimSummary,
  ClaimType,
  PageResponse,
} from '../../models';
import { ClaimService } from '../../services/claim.service';
import { errorMessage } from '../../utils/error-message';
import { EmptyState } from '../empty-state/empty-state';
import { LoadingSpinner } from '../loading-spinner/loading-spinner';
import { StatusChip } from '../status-chip/status-chip';
import { MoneyPipe } from '../money.pipe';

export type ClaimListFilter = 'status' | 'claimType' | 'market';
export interface ClaimRowContext {
  $implicit: ClaimSummary;
}

/**
 * Filterable, server-paged table of claims. Used for "My claims" (claimant), the claim queue, assigned claims and the
 * manager's assignment dashboard.
 *
 *  - `source` picks the endpoint: `claims` calls GET /claims, `staff` calls GET /officer/claims.
 *  - `fixed` pins query parameters the person cannot change (e.g. `{ assigned: false }` for the queue).
 *  - `rowActions` is an optional template rendered in an extra "Actions" column.
 *  - `reload()` re-runs the current query (call it after an action changed the list).
 */
@Component({
  selector: 'app-claim-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    DatePipe,
    NgTemplateOutlet,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatPaginatorModule,
    MatSelectModule,
    MatTableModule,
    EmptyState,
    LoadingSpinner,
    StatusChip,
  ],
  template: `
    <div class="filters" role="search">
      @if (filters().includes('status')) {
        <mat-form-field class="filter">
          <mat-label>Status</mat-label>
          <mat-select [value]="status()" (selectionChange)="setStatus($event.value)">
            <mat-option [value]="null">All statuses</mat-option>
            @for (s of statuses; track s) {
              <mat-option [value]="s">{{ statusLabels[s] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (filters().includes('claimType')) {
        <mat-form-field class="filter">
          <mat-label>Claim type</mat-label>
          <mat-select [value]="claimType()" (selectionChange)="setClaimType($event.value)">
            <mat-option [value]="null">All types</mat-option>
            @for (t of claimTypes; track t) {
              <mat-option [value]="t">{{ t === 'MOTOR' ? 'Motor' : 'Property' }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (filters().includes('market')) {
        <mat-form-field class="filter">
          <mat-label>Market</mat-label>
          <mat-select [value]="market()" (selectionChange)="setMarket($event.value)">
            <mat-option [value]="null">All markets</mat-option>
            @for (m of markets; track m.code) {
              <mat-option [value]="m.code">{{ m.code }} · {{ m.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      <span class="spacer"></span>
      @if (hasFilters()) {
        <button mat-button type="button" (click)="clearFilters()">Clear filters</button>
      }
      <button mat-icon-button type="button" (click)="reload()" aria-label="Refresh the list">
        <mat-icon>refresh</mat-icon>
      </button>
    </div>

    @if (error(); as message) {
      <app-empty-state icon="error_outline" title="Could not load claims" [message]="message">
        <button mat-flat-button type="button" (click)="reload()">Try again</button>
      </app-empty-state>
    } @else if (data(); as result) {
      @if (result.content.length === 0) {
        <app-empty-state icon="inbox" [title]="hasFilters() ? 'No claims match these filters' : emptyTitle()" [message]="hasFilters() ? 'Try changing or clearing the filters.' : emptyMessage()">
          @if (hasFilters()) {
            <button mat-stroked-button type="button" (click)="clearFilters()">Clear filters</button>
          } @else {
            <ng-content />
          }
        </app-empty-state>
      } @else {
        <div class="table-wrap" [class.stale]="loading()">
          <table mat-table [dataSource]="result.content" aria-label="Claims">
            <ng-container matColumnDef="claimId">
              <th mat-header-cell *matHeaderCellDef>Claim</th>
              <td mat-cell *matCellDef="let c">
                <a class="mono" [routerLink]="detailLink()(c)">{{ c.claimId }}</a>
              </td>
            </ng-container>
            <ng-container matColumnDef="claimant">
              <th mat-header-cell *matHeaderCellDef class="hide-sm">Claimant</th>
              <td mat-cell *matCellDef="let c" class="hide-sm">{{ c.claimantName }}</td>
            </ng-container>
            <ng-container matColumnDef="claimType">
              <th mat-header-cell *matHeaderCellDef class="hide-md">Type</th>
              <td mat-cell *matCellDef="let c" class="hide-md">{{ c.claimType === 'MOTOR' ? 'Motor' : 'Property' }} · {{ c.market }}</td>
            </ng-container>
            <ng-container matColumnDef="amount">
              <th mat-header-cell *matHeaderCellDef class="amount">Claimed</th>
              <td mat-cell *matCellDef="let c" class="amount nowrap">{{ c.claimedAmount | money: c.currency }}</td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let c"><app-status-chip [status]="c.status" /></td>
            </ng-container>
            <ng-container matColumnDef="officer">
              <th mat-header-cell *matHeaderCellDef class="hide-md">Officer</th>
              <td mat-cell *matCellDef="let c" class="hide-md">
                @if (c.assignedOfficerId) {
                  {{ c.assignedOfficerId }}
                } @else {
                  <span class="muted">Unassigned</span>
                }
              </td>
            </ng-container>
            <ng-container matColumnDef="createdAt">
              <th mat-header-cell *matHeaderCellDef class="hide-sm">Submitted</th>
              <td mat-cell *matCellDef="let c" class="hide-sm nowrap">{{ c.createdAt | date: 'mediumDate' }}</td>
            </ng-container>
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef class="actions-col">Actions</th>
              <td mat-cell *matCellDef="let c" class="actions-col">
                @if (rowActions(); as tpl) {
                  <ng-container *ngTemplateOutlet="tpl; context: { $implicit: c }" />
                }
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="columns()"></tr>
            <tr mat-row *matRowDef="let row; columns: columns()"></tr>
          </table>
        </div>
        <mat-paginator
          [length]="result.totalElements"
          [pageIndex]="result.page"
          [pageSize]="result.size"
          [pageSizeOptions]="[10, 20, 50, 100]"
          showFirstLastButtons
          aria-label="Select page of claims"
          (page)="onPage($event)"
        />
      }
    } @else {
      <app-loading-spinner label="Loading claims…" />
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      margin-bottom: 8px;
    }
    .filter {
      width: 200px;
      max-width: 100%;
      margin-bottom: 0;
    }
    .stale {
      opacity: 0.6;
      transition: opacity 0.15s;
    }
    .amount {
      text-align: right;
    }
    .actions-col {
      text-align: right;
      white-space: nowrap;
    }
    a {
      font-weight: 500;
    }
  `,
})
export class ClaimList {
  private readonly claims = inject(ClaimService);

  /** `claims` calls GET /claims (a claimant's own, or every claim for a manager); `staff` calls GET /officer/claims. */
  readonly source = input<'claims' | 'staff'>('claims');
  readonly fixed = input<ClaimListParams>({});
  readonly filters = input<readonly ClaimListFilter[]>(['status', 'claimType', 'market']);
  readonly showClaimant = input(false);
  readonly showOfficer = input(false);
  readonly detailLink = input<(claim: ClaimSummary) => readonly unknown[]>((c) => ['/claimant/claims', c.claimId]);
  readonly rowActions = input<TemplateRef<ClaimRowContext> | null>(null);
  readonly emptyTitle = input('No claims yet');
  readonly emptyMessage = input('Claims will appear here.');
  /** Pre-selects the status filter (for links such as a dashboard card). The person can still change it. */
  readonly initialStatus = input<ClaimStatus | null>(null);

  protected readonly statuses = CLAIM_STATUSES;
  protected readonly statusLabels = CLAIM_STATUS_LABELS;
  protected readonly claimTypes = CLAIM_TYPES;
  protected readonly markets = MARKETS;

  protected readonly status = signal<ClaimStatus | null>(null);
  protected readonly claimType = signal<ClaimType | null>(null);
  protected readonly market = signal<string | null>(null);
  private readonly page = signal(0);
  private readonly size = signal(20);
  private readonly tick = signal(0);

  protected readonly data = signal<PageResponse<ClaimSummary> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly hasFilters = computed(
    () => this.status() !== null || this.claimType() !== null || this.market() !== null,
  );

  protected readonly columns = computed(() => {
    const columns = ['claimId'];
    if (this.showClaimant()) {
      columns.push('claimant');
    }
    columns.push('claimType', 'amount', 'status');
    if (this.showOfficer()) {
      columns.push('officer');
    }
    columns.push('createdAt');
    if (this.rowActions()) {
      columns.push('actions');
    }
    return columns;
  });

  private readonly query = computed<ClaimListParams>(() => {
    this.tick();
    return {
      status: this.status(),
      claimType: this.claimType(),
      market: this.market(),
      ...this.fixed(),
      page: this.page(),
      size: this.size(),
    };
  });

  constructor() {
    effect(() => {
      const initial = this.initialStatus();
      untracked(() => this.status.set(initial));
    });

    toObservable(this.query)
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap((query) =>
          this.fetch(query).pipe(
            catchError((err: unknown) => {
              this.error.set(errorMessage(err));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if (result) {
          this.data.set(result);
        }
      });
  }

  /** Re-runs the current query. */
  reload(): void {
    this.tick.update((n) => n + 1);
  }

  protected setStatus(value: ClaimStatus | null): void {
    this.status.set(value);
    this.page.set(0);
  }

  protected setClaimType(value: ClaimType | null): void {
    this.claimType.set(value);
    this.page.set(0);
  }

  protected setMarket(value: string | null): void {
    this.market.set(value);
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.status.set(null);
    this.claimType.set(null);
    this.market.set(null);
    this.page.set(0);
  }

  protected onPage(event: PageEvent): void {
    this.page.set(event.pageIndex);
    this.size.set(event.pageSize);
  }

  private fetch(query: ClaimListParams): Observable<PageResponse<ClaimSummary>> {
    return this.source() === 'staff' ? this.claims.listStaff(query) : this.claims.list(query);
  }
}
