import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { AuthService } from '../../../services/auth.service';
import { CLAIM_STATUSES, ClaimStatus, ClaimSummary } from '../../../models';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { PageHeader } from '../../../shared/page-header/page-header';

/** The officer's own claims: GET /officer/claims?assignedOfficerId=<me>. `?status=` pre-selects the status filter. */
@Component({
  selector: 'app-assigned-claims',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header title="Assigned claims" subtitle="Claims assigned to you, newest first." />

      <app-claim-list
        source="staff"
        [fixed]="fixed()"
        [initialStatus]="initialStatus()"
        [showClaimant]="true"
        [detailLink]="detailLink"
        emptyTitle="No claims assigned to you"
        emptyMessage="Take a claim from the queue and it will appear here."
      />
    </div>
  `,
})
export class AssignedClaims {
  private readonly auth = inject(AuthService);

  /** From the `?status=` query parameter (withComponentInputBinding). */
  readonly status = input<string>();

  protected readonly fixed = computed(() => ({ assignedOfficerId: this.auth.username() }));
  protected readonly initialStatus = computed<ClaimStatus | null>(() => {
    const requested = this.status();
    return CLAIM_STATUSES.find((s) => s === requested) ?? null;
  });
  protected readonly detailLink = (claim: ClaimSummary) => ['/officer/claims', claim.claimId];
}
