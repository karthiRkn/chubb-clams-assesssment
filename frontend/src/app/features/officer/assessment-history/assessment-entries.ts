import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { AssessmentEntry, AssessmentKind } from '../../../models';
import { EmptyState } from '../../../shared/empty-state/empty-state';

const KINDS: Record<AssessmentKind, { label: string; icon: string; css: string }> = {
  NOTE: { label: 'Review note', icon: 'edit_note', css: 'note' },
  APPROVED: { label: 'Approved', icon: 'check_circle', css: 'good' },
  REJECTED: { label: 'Rejected', icon: 'cancel', css: 'bad' },
  SETTLED: { label: 'Settled', icon: 'paid', css: 'good' },
};

/** The officer-only assessment trail of a claim, oldest first (as the backend returns it). Never shown to claimants. */
@Component({
  selector: 'app-assessment-entries',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatIconModule, EmptyState],
  template: `
    @if (entries().length === 0) {
      <app-empty-state
        icon="history_edu"
        title="No assessment entries yet"
        message="Review notes, the decision and the settlement are recorded here as they happen."
      />
    } @else {
      <ol class="entries">
        @for (entry of entries(); track $index) {
          <li class="entry" [class]="kind(entry).css">
            <mat-icon aria-hidden="true">{{ kind(entry).icon }}</mat-icon>
            <div class="body">
              <div class="title">{{ kind(entry).label }}</div>
              @if (entry.notes) {
                <p class="notes">{{ entry.notes }}</p>
              }
              <div class="meta muted">{{ entry.createdAt | date: 'medium' }} · {{ entry.officerId }}</div>
            </div>
          </li>
        }
      </ol>
    }
  `,
  styles: `
    .entries {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .entry {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      border: 1px solid var(--app-border);
      border-left-width: 4px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-lowest);
    }
    .note { border-left-color: var(--status-review-fg); }
    .note mat-icon { color: var(--status-review-fg); }
    .good { border-left-color: var(--status-approved-fg); }
    .good mat-icon { color: var(--status-approved-fg); }
    .bad { border-left-color: var(--status-rejected-fg); }
    .bad mat-icon { color: var(--status-rejected-fg); }
    .title {
      font: var(--mat-sys-label-large);
    }
    .notes {
      margin: 4px 0;
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .meta {
      font: var(--mat-sys-body-small);
    }
  `,
})
export class AssessmentEntries {
  readonly entries = input.required<readonly AssessmentEntry[]>();

  protected kind(entry: AssessmentEntry) {
    return KINDS[entry.kind] ?? KINDS.NOTE;
  }
}
