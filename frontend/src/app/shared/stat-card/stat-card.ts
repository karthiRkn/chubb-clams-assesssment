import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

export type StatTone = 'default' | 'good' | 'attention' | 'bad';

/** One headline number. Becomes a link when `link` is given (a card is only interactive if it goes somewhere). */
@Component({
  selector: 'app-stat-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, RouterLink, NgTemplateOutlet],
  template: `
    @if (link(); as target) {
      <a
        class="card interactive"
        [class]="tone()"
        [routerLink]="target"
        [queryParams]="queryParams()"
        [attr.aria-label]="label() + ': ' + value()"
      >
        <ng-container *ngTemplateOutlet="body" />
      </a>
    } @else {
      <div class="card" [class]="tone()">
        <ng-container *ngTemplateOutlet="body" />
      </div>
    }

    <ng-template #body>
      <mat-icon class="icon" aria-hidden="true">{{ icon() }}</mat-icon>
      <div class="text">
        <span class="label">{{ label() }}</span>
        <span class="value">{{ value() }}</span>
        @if (hint()) {
          <span class="hint">{{ hint() }}</span>
        }
      </div>
    </ng-template>
  `,
  styles: `
    :host {
      display: block;
    }
    .card {
      display: flex;
      align-items: center;
      gap: 16px;
      height: 100%;
      padding: 16px 20px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius);
      background: var(--mat-sys-surface-container-lowest);
      color: inherit;
      text-decoration: none;
    }
    .interactive:hover {
      background: var(--mat-sys-surface-container-low);
    }
    .icon {
      flex: none;
      width: 40px;
      height: 40px;
      padding: 8px;
      border-radius: 50%;
      font-size: 24px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .good .icon { background: var(--status-approved-bg); color: var(--status-approved-fg); }
    .attention .icon { background: var(--status-info-bg); color: var(--status-info-fg); }
    .bad .icon { background: var(--status-rejected-bg); color: var(--status-rejected-fg); }
    .text {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .label {
      font: var(--mat-sys-label-large);
      color: var(--app-muted);
    }
    .value {
      font: var(--mat-sys-headline-medium);
      overflow-wrap: anywhere;
    }
    .hint {
      font: var(--mat-sys-body-small);
      color: var(--app-muted);
    }
  `,
})
export class StatCard {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly icon = input('insights');
  readonly hint = input<string | null>(null);
  readonly tone = input<StatTone>('default');
  readonly link = input<string | readonly unknown[] | null>(null);
  /** Query parameters for `link`, e.g. `{ status: 'APPROVED' }`. */
  readonly queryParams = input<Record<string, string> | null>(null);
}
