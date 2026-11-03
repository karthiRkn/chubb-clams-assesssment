import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { CLAIM_STATUSES, ClaimStatus, ClaimSummary } from '../../../models';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { PageHeader } from '../../../shared/page-header/page-header';

/** Every claim in the system, for a manager: GET /claims (the backend scopes it by role, and a manager sees all). */
@Component({
  selector: 'app-manager-claims',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, RouterLink, ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header title="Claims" subtitle="Every claim, newest first. Open one to see its history or assign it.">
        <a mat-stroked-button routerLink="/manager/assignments"><mat-icon>assignment</mat-icon>Assignment dashboard</a>
      </app-page-header>

      <app-claim-list
        source="claims"
        [initialStatus]="initialStatus()"
        [showClaimant]="true"
        [showOfficer]="true"
        [detailLink]="detailLink"
        emptyTitle="No claims yet"
        emptyMessage="Claims will appear here as soon as claimants submit them."
      />
    </div>
  `,
})
export class ManagerClaims {
  /** From the `?status=` query parameter (withComponentInputBinding), so a dashboard link can open a filtered list. */
  readonly status = input<string>();

  protected readonly initialStatus = computed<ClaimStatus | null>(() => CLAIM_STATUSES.find((s) => s === this.status()) ?? null);
  protected readonly detailLink = (claim: ClaimSummary) => ['/manager/claims', claim.claimId];
}
