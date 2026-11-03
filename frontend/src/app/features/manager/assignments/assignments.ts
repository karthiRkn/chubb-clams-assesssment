import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, viewChildren } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ClaimSummary, OfficerEntry, TERMINAL_STATUSES } from '../../../models';
import { ManagementService } from '../../../services/management.service';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { PageHeader } from '../../../shared/page-header/page-header';
import { AssignDialog, AssignDialogData } from './assign-dialog';

/**
 * Where a manager hands claims to officers.
 *  - "Waiting to be assigned": GET /officer/claims?assigned=false
 *  - "Assigned": GET /officer/claims?assigned=true (can be handed to someone else until it is SETTLED or REJECTED)
 *  - the officer workload strip: GET /management/officers
 */
@Component({
  selector: 'app-assignments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatTabsModule, ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header
        title="Assignment dashboard"
        subtitle="Hand submitted claims to the right officer, and rebalance work between them."
      />

      @if (officers().length > 0) {
        <section class="panel" aria-label="Officer workload">
          <h2>Who has capacity</h2>
          <ul class="officers">
            @for (officer of officers(); track officer.officerId) {
              <li class="officer">
                <span class="name">{{ officer.officerId }}</span>
                <span class="open"><strong>{{ officer.openClaimCount }}</strong> open</span>
              </li>
            }
          </ul>
        </section>
      }

      <mat-tab-group animationDuration="0ms" mat-stretch-tabs="false" mat-align-tabs="start">
        <mat-tab label="Waiting to be assigned">
          <ng-template matTabContent>
            <div class="tab">
              <app-claim-list
                source="staff"
                [fixed]="{ assigned: false }"
                [filters]="['claimType', 'market']"
                [showClaimant]="true"
                [detailLink]="detailLink"
                [rowActions]="assignAction"
                emptyTitle="Everything is assigned"
                emptyMessage="New submissions that nobody has picked up will appear here."
              />
            </div>
          </ng-template>
        </mat-tab>
        <mat-tab label="Assigned">
          <ng-template matTabContent>
            <div class="tab">
              <app-claim-list
                source="staff"
                [fixed]="{ assigned: true }"
                [showClaimant]="true"
                [showOfficer]="true"
                [detailLink]="detailLink"
                [rowActions]="reassignAction"
                emptyTitle="No claims are assigned"
                emptyMessage="Once claims are assigned to officers they will be listed here."
              />
            </div>
          </ng-template>
        </mat-tab>
      </mat-tab-group>

      <ng-template #assignAction let-claim>
        <button mat-flat-button type="button" (click)="assign(claim)"><mat-icon>person_add</mat-icon>Assign</button>
      </ng-template>
      <ng-template #reassignAction let-claim>
        @if (canReassign(claim)) {
          <button mat-stroked-button type="button" (click)="assign(claim)"><mat-icon>swap_horiz</mat-icon>Reassign</button>
        } @else {
          <span class="muted">Closed</span>
        }
      </ng-template>
    </div>
  `,
  styles: `
    h2 {
      font: var(--mat-sys-title-small);
      margin-bottom: 8px;
    }
    .officers {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .officer {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 12px;
      border: 1px solid var(--app-border);
      border-radius: 999px;
      background: var(--mat-sys-surface-container-low);
    }
    .name {
      font-weight: 500;
    }
    .tab {
      padding-top: 16px;
    }
  `,
})
export class Assignments implements OnInit {
  private readonly dialog = inject(MatDialog);
  private readonly management = inject(ManagementService);

  private readonly lists = viewChildren(ClaimList);

  protected readonly officers = signal<OfficerEntry[]>([]);
  private readonly suggestions = computed(() =>
    this.officers().map((o) => ({ id: o.officerId, open: o.openClaimCount })),
  );

  protected readonly detailLink = (claim: ClaimSummary) => ['/manager/claims', claim.claimId];

  ngOnInit(): void {
    this.loadOfficers();
  }

  protected canReassign(claim: ClaimSummary): boolean {
    return !TERMINAL_STATUSES.includes(claim.status);
  }

  protected assign(claim: ClaimSummary): void {
    this.dialog
      .open<AssignDialog, AssignDialogData, boolean>(AssignDialog, {
        data: { claim, officers: this.suggestions() },
        width: '460px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((assigned) => {
        if (assigned) {
          this.lists().forEach((list) => list.reload());
          this.loadOfficers();
        }
      });
  }

  private loadOfficers(): void {
    this.management.officers().subscribe({
      next: (metrics) => this.officers.set(metrics.officers),
      // The strip is a convenience: the page still works without it.
      error: () => undefined,
    });
  }
}
