import { ChangeDetectionStrategy, Component, inject, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { EMPTY, switchMap } from 'rxjs';
import { ClaimSummary } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ClaimService } from '../../../services/claim.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { PageHeader } from '../../../shared/page-header/page-header';

/**
 * Unassigned claims an officer can pick up: GET /officer/claims?assigned=false.
 * "Take claim" is POST /officer/claims/{id}/assign naming the officer themselves (an officer may not name anyone else).
 */
@Component({
  selector: 'app-claims-queue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header
        title="Claims queue"
        subtitle="Submitted claims nobody has picked up yet. Take one to start working on it."
      />

      <app-claim-list
        #list
        source="staff"
        [fixed]="{ assigned: false }"
        [filters]="['claimType', 'market']"
        [showClaimant]="true"
        [detailLink]="detailLink"
        [rowActions]="actions"
        emptyTitle="The queue is empty"
        emptyMessage="There are no unassigned claims right now. New submissions will appear here."
      />

      <ng-template #actions let-claim>
        <button mat-flat-button type="button" (click)="take(claim)"><mat-icon>assignment_ind</mat-icon>Take claim</button>
      </ng-template>
    </div>
  `,
})
export class ClaimsQueue {
  private readonly auth = inject(AuthService);
  private readonly claims = inject(ClaimService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  private readonly list = viewChild.required<ClaimList>('list');

  protected readonly detailLink = (claim: ClaimSummary) => ['/officer/claims', claim.claimId];

  protected take(claim: ClaimSummary): void {
    const me = this.auth.username();
    if (!me) {
      return;
    }
    this.confirm
      .confirm({
        title: `Take claim ${claim.claimId}?`,
        message: 'It will be assigned to you and leave the queue. You can then start the review.',
        confirmLabel: 'Take claim',
        icon: 'assignment_ind',
      })
      .pipe(switchMap((ok) => (ok ? this.claims.assign(claim.claimId, me) : EMPTY)))
      .subscribe({
        next: () => {
          this.notifications.success(`${claim.claimId} is now assigned to you.`);
          void this.router.navigate(['/officer/claims', claim.claimId]);
        },
        // Another officer may have taken it first: refresh so the list is truthful.
        error: () => this.list().reload(),
      });
  }
}
