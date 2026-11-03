import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-footer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <footer class="footer">
      <span>© {{ year }} Chubb APAC Claims Management</span>
      <span class="muted">Assessment build · demo data only</span>
    </footer>
  `,
  styles: `
    .footer {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 4px 16px;
      padding: 12px 24px;
      border-top: 1px solid var(--app-border);
      font: var(--mat-sys-body-small);
      background: var(--mat-sys-surface-container-lowest);
    }
    @media (max-width: 599px) {
      .footer {
        padding: 12px 16px;
      }
    }
  `,
})
export class Footer {
  protected readonly year = new Date().getFullYear();
}
