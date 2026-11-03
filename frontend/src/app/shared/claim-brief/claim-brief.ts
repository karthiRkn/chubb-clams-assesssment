import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Claim } from '../../models';
import { StatusChip } from '../status-chip/status-chip';
import { MoneyPipe } from '../money.pipe';

/** A compact reminder of which claim an action form applies to. */
@Component({
  selector: 'app-claim-brief',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MoneyPipe, StatusChip],
  template: `
    <section class="panel" aria-label="Claim being actioned">
      <div class="head">
        <span class="mono ref">{{ claim().claimId }}</span>
        <app-status-chip [status]="claim().status" />
      </div>
      <p class="muted meta">
        {{ claim().claimantName }} · {{ claim().claimType === 'MOTOR' ? 'Motor' : 'Property' }} · {{ claim().market }} · claimed
        <strong>{{ claim().claimedAmount | money: claim().currency }}</strong>
      </p>
      <p class="text">{{ claim().incidentDescription }}</p>
    </section>
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      margin-bottom: 4px;
    }
    .ref {
      font: var(--mat-sys-title-medium);
    }
    .meta {
      margin-bottom: 8px;
    }
    .text {
      white-space: pre-line;
      overflow-wrap: anywhere;
      display: -webkit-box;
      -webkit-line-clamp: 4;
      line-clamp: 4;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
  `,
})
export class ClaimBrief {
  readonly claim = input.required<Claim>();
}
