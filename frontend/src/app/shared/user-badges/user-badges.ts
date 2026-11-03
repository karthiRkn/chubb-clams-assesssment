import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { ROLE_LABELS, Role } from '../../models';

/** A person's role as a quiet pill. */
@Component({
  selector: 'app-role-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge">{{ label() }}</span>`,
  styles: `
    .badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
  `,
})
export class RoleBadge {
  readonly role = input.required<Role>();
  protected readonly label = computed(() => ROLE_LABELS[this.role()] ?? this.role());
}

/** Account state. An icon and a word carry the meaning, so it never depends on colour alone. */
@Component({
  selector: 'app-active-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <span class="badge" [class.off]="!active()">
      <mat-icon aria-hidden="true">{{ active() ? 'check_circle' : 'block' }}</mat-icon>
      {{ active() ? 'Active' : 'Inactive' }}
    </span>
  `,
  styles: `
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px 2px 6px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
      background: var(--status-approved-bg);
      color: var(--status-approved-fg);
    }
    .badge.off {
      background: var(--status-rejected-bg);
      color: var(--status-rejected-fg);
    }
    mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
  `,
})
export class ActiveBadge {
  readonly active = input.required<boolean>();
}
