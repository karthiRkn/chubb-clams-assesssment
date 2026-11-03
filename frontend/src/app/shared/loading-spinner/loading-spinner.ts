import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/** Centered spinner with a label, for the first load of a page or panel. */
@Component({
  selector: 'app-loading-spinner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatProgressSpinnerModule],
  template: `
    <div class="wrap" role="status" aria-live="polite">
      <mat-progress-spinner mode="indeterminate" [diameter]="diameter()" />
      <span class="muted">{{ label() }}</span>
    </div>
  `,
  styles: `
    .wrap {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
      padding: 48px 16px;
    }
  `,
})
export class LoadingSpinner {
  readonly label = input('Loading…');
  readonly diameter = input(40);
}
