import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { ClaimHistoryEntry, ClaimStatus } from '../../models';

type StepState = 'done' | 'current' | 'todo' | 'error' | 'skipped';

interface Step {
  label: string;
  caption: string;
  state: StepState;
  icon: string;
}

/**
 * The claim's journey as a four-step progress tracker: Submitted, Under review, Decision, Settlement.
 * (The detailed histories live in `app-claim-history-tabs`.) The dates under the steps come from the status history.
 */
@Component({
  selector: 'app-claim-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <ol class="steps" aria-label="Claim progress">
      @for (step of steps(); track step.label) {
        <li class="step" [class]="step.state" [attr.aria-current]="step.state === 'current' ? 'step' : null">
          <span class="marker"><mat-icon aria-hidden="true">{{ step.icon }}</mat-icon></span>
          <span class="step-label">{{ step.label }}</span>
          <span class="step-caption">{{ step.caption }}</span>
        </li>
      }
    </ol>
  `,
  styles: `
    .steps {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 8px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .step {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      text-align: center;
    }
    .step:not(:last-child)::after {
      content: '';
      position: absolute;
      top: 19px;
      left: calc(50% + 24px);
      right: calc(-50% + 24px);
      height: 2px;
      background: var(--app-border);
    }
    .step.done:not(:last-child)::after {
      background: var(--status-approved-fg);
    }
    .marker {
      display: grid;
      place-items: center;
      width: 40px;
      height: 40px;
      border-radius: 50%;
      border: 2px solid var(--app-border);
      background: var(--mat-sys-surface);
      color: var(--app-muted);
    }
    .done .marker { background: var(--status-approved-fg); border-color: var(--status-approved-fg); color: #fff; }
    .current .marker { border-color: var(--mat-sys-primary); color: var(--mat-sys-primary); box-shadow: 0 0 0 4px var(--mat-sys-primary-container); }
    .error .marker { background: var(--status-rejected-fg); border-color: var(--status-rejected-fg); color: #fff; }
    .skipped { opacity: 0.5; }
    .step-label { font: var(--mat-sys-label-large); }
    .step-caption { font: var(--mat-sys-body-small); color: var(--app-muted); }

    @media (max-width: 599px) {
      .steps { grid-template-columns: minmax(0, 1fr); }
      .step { flex-direction: row; text-align: left; gap: 12px; }
      .step:not(:last-child)::after { display: none; }
      .step-caption::before { content: '· '; }
    }
  `,
})
export class ClaimTimeline {
  readonly status = input.required<ClaimStatus>();
  readonly history = input<readonly ClaimHistoryEntry[]>([]);

  protected readonly steps = computed<Step[]>(() => {
    const status = this.status();
    const at = (to: ClaimStatus) => this.history().find((h) => h.toStatus === to)?.changedAt;
    const shortDate = (iso?: string) => (iso ? new Date(iso).toLocaleDateString() : '');
    const inReview = status === 'UNDER_REVIEW' || status === 'MORE_INFO_REQUIRED';

    const submitted: Step = {
      label: 'Submitted',
      caption: shortDate(at('SUBMITTED')),
      state: status === 'SUBMITTED' ? 'current' : 'done',
      icon: 'send',
    };

    const review: Step = {
      label: 'Under review',
      caption: status === 'MORE_INFO_REQUIRED' ? 'More information requested' : shortDate(at('UNDER_REVIEW')),
      state: status === 'SUBMITTED' ? 'todo' : inReview ? 'current' : 'done',
      icon: status === 'MORE_INFO_REQUIRED' ? 'help' : 'manage_search',
    };

    const decided = status === 'APPROVED' || status === 'SETTLED';
    const decision: Step =
      status === 'REJECTED'
        ? { label: 'Rejected', caption: shortDate(at('REJECTED')), state: 'error', icon: 'cancel' }
        : {
            label: 'Decision',
            caption: decided ? `Approved ${shortDate(at('APPROVED'))}`.trim() : '',
            state: decided ? 'done' : 'todo',
            icon: decided ? 'check_circle' : 'gavel',
          };

    const settlement: Step =
      status === 'REJECTED'
        ? { label: 'Settlement', caption: 'Not applicable', state: 'skipped', icon: 'paid' }
        : {
            label: 'Settlement',
            caption: status === 'SETTLED' ? shortDate(at('SETTLED')) : status === 'APPROVED' ? 'Payment pending' : '',
            state: status === 'SETTLED' ? 'done' : status === 'APPROVED' ? 'current' : 'todo',
            icon: 'paid',
          };

    return [submitted, review, decision, settlement];
  });
}
