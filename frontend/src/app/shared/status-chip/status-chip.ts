import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { CLAIM_STATUS_LABELS, ClaimStatus } from '../../models';

interface ChipStyle {
  css: string;
  icon: string;
}

const CHIP_STYLES: Record<ClaimStatus, ChipStyle> = {
  SUBMITTED: { css: 'submitted', icon: 'send' },
  UNDER_REVIEW: { css: 'review', icon: 'manage_search' },
  MORE_INFO_REQUIRED: { css: 'info', icon: 'help' },
  APPROVED: { css: 'approved', icon: 'check_circle' },
  REJECTED: { css: 'rejected', icon: 'cancel' },
  SETTLED: { css: 'settled', icon: 'paid' },
};

/** A claim status as a coloured pill. An icon accompanies the colour so the status never depends on colour alone. */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `<span class="chip" [class]="style().css"><mat-icon aria-hidden="true">{{ style().icon }}</mat-icon>{{ label() }}</span>`,
  styles: `
    :host {
      display: inline-flex;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px 2px 6px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
    }
    mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
    .submitted { background: var(--status-submitted-bg); color: var(--status-submitted-fg); }
    .review { background: var(--status-review-bg); color: var(--status-review-fg); }
    .info { background: var(--status-info-bg); color: var(--status-info-fg); }
    .approved { background: var(--status-approved-bg); color: var(--status-approved-fg); }
    .rejected { background: var(--status-rejected-bg); color: var(--status-rejected-fg); }
    .settled { background: var(--status-settled-bg); color: var(--status-settled-fg); }
  `,
})
export class StatusChip {
  readonly status = input.required<ClaimStatus>();

  protected readonly style = computed(() => CHIP_STYLES[this.status()] ?? { css: 'submitted', icon: 'help' });
  protected readonly label = computed(() => CLAIM_STATUS_LABELS[this.status()] ?? this.status());
}
