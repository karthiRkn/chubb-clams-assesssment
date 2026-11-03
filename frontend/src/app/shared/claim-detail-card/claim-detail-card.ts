import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Claim } from '../../models';
import { StatusChip } from '../status-chip/status-chip';
import { MoneyPipe } from '../money.pipe';

/** Read-only details of one claim, shared by the claimant's claim page and every officer screen. */
@Component({
  selector: 'app-claim-detail-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MoneyPipe, StatusChip],
  template: `
    <section class="panel" aria-label="Claim details">
      <div class="head">
        <div>
          <div class="muted label">Claim reference</div>
          <div class="ref mono">{{ claim().claimId }}</div>
        </div>
        <app-status-chip [status]="claim().status" />
      </div>

      <dl class="grid">
        <div>
          <dt>Claimant</dt>
          <dd>{{ claim().claimantName }}</dd>
        </div>
        <div>
          <dt>Email</dt>
          <dd>{{ claim().claimantEmail }}</dd>
        </div>
        <div>
          <dt>Policy number</dt>
          <dd class="mono">{{ claim().policyNumber }}</dd>
        </div>
        <div>
          <dt>Claim type</dt>
          <dd>{{ claim().claimType === 'MOTOR' ? 'Motor' : 'Property' }}</dd>
        </div>
        <div>
          <dt>Market</dt>
          <dd>{{ claim().market }}</dd>
        </div>
        <div>
          <dt>Incident date</dt>
          <dd>{{ claim().incidentDate | date: 'mediumDate' }}</dd>
        </div>
        <div>
          <dt>Claimed amount</dt>
          <dd>{{ claim().claimedAmount | money: claim().currency }}</dd>
        </div>
        <div>
          <dt>Approved amount</dt>
          <dd>
            @if (claim().approvedAmount !== null) {
              {{ claim().approvedAmount | money: claim().currency }}
            } @else {
              <span class="muted">—</span>
            }
          </dd>
        </div>
        <div>
          <dt>Assigned officer</dt>
          <dd>
            @if (claim().assignedOfficerId) {
              {{ claim().assignedOfficerId }}
            } @else {
              <span class="muted">Not assigned yet</span>
            }
          </dd>
        </div>
        <div>
          <dt>Submitted</dt>
          <dd>{{ claim().createdAt | date: 'medium' }}</dd>
        </div>
        <div>
          <dt>Last updated</dt>
          <dd>{{ claim().updatedAt | date: 'medium' }}</dd>
        </div>
        @if (claim().settledAt) {
          <div>
            <dt>Settled</dt>
            <dd>{{ claim().settledAt | date: 'medium' }}</dd>
          </div>
        }
        <div class="wide">
          <dt>What happened</dt>
          <dd class="text">{{ claim().incidentDescription }}</dd>
        </div>
        @if (claim().decisionReason) {
          <div class="wide">
            <dt>Decision reason</dt>
            <dd class="text">{{ claim().decisionReason }}</dd>
          </div>
        }
      </dl>
    </section>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      margin-bottom: 16px;
    }
    .label {
      font: var(--mat-sys-label-medium);
    }
    .ref {
      font: var(--mat-sys-title-large);
      overflow-wrap: anywhere;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 16px 24px;
      margin: 0;
    }
    .wide {
      grid-column: 1 / -1;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--app-muted);
      margin-bottom: 2px;
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .text {
      white-space: pre-line;
    }
  `,
})
export class ClaimDetailCard {
  readonly claim = input.required<Claim>();
}
