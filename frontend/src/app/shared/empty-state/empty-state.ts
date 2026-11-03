import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** "Nothing here yet" / "could not load" placeholder. Project buttons into it for the next step the person can take. */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <div class="empty" role="status">
      <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>
      <h3>{{ title() }}</h3>
      @if (message()) {
        <p class="muted">{{ message() }}</p>
      }
      <div class="actions-slot"><ng-content /></div>
    </div>
  `,
  styles: `
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 40px 16px;
      text-align: center;
      border: 1px dashed var(--app-border);
      border-radius: var(--app-radius);
      background: var(--mat-sys-surface-container-lowest);
    }
    mat-icon {
      width: 48px;
      height: 48px;
      font-size: 48px;
      color: var(--app-muted);
    }
    h3 {
      font: var(--mat-sys-title-medium);
    }
    p {
      max-width: 48ch;
    }
    .actions-slot {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 8px;
      margin-top: 8px;
    }
    .actions-slot:empty {
      display: none;
    }
  `,
})
export class EmptyState {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly message = input<string | null>(null);
}
