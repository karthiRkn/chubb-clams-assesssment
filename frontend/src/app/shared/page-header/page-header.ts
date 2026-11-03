import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

/** Page title with an optional back link and a slot (right-aligned on wide screens) for the page's main actions. */
@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <header class="header">
      <div class="titles">
        @if (backLink(); as link) {
          <a mat-button class="back" [routerLink]="link"><mat-icon>arrow_back</mat-icon>{{ backLabel() }}</a>
        }
        <h1>{{ title() }}</h1>
        @if (subtitle()) {
          <p class="muted">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
  styles: `
    .header {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 16px;
    }
    .titles {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }
    h1 {
      font: var(--mat-sys-headline-small);
      overflow-wrap: anywhere;
    }
    .back {
      align-self: flex-start;
      margin-left: -12px;
    }
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
  /** Router link of the page to go back to (omit for none). */
  readonly backLink = input<string | readonly unknown[] | null>(null);
  readonly backLabel = input('Back');
}
