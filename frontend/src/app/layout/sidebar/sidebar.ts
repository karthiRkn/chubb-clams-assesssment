import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { ROLE_LABELS } from '../../models';
import { AuthService } from '../../services/auth.service';
import { NAV_ITEMS } from '../nav-items';

/** Role-aware navigation: the items change with the signed-in person's role. */
@Component({
  selector: 'app-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatListModule, RouterLink, RouterLinkActive],
  template: `
    <nav aria-label="Main navigation">
      @if (roleLabel(); as label) {
        <div class="role">{{ label }}</div>
      }
      <mat-nav-list>
        @for (item of items(); track item.route) {
          <a
            mat-list-item
            [routerLink]="item.route"
            routerLinkActive
            #active="routerLinkActive"
            [routerLinkActiveOptions]="{ exact: item.exact ?? false }"
            [activated]="active.isActive"
            [attr.aria-current]="active.isActive ? 'page' : null"
            (click)="navigated.emit()"
          >
            <mat-icon matListItemIcon aria-hidden="true">{{ item.icon }}</mat-icon>
            <span matListItemTitle>{{ item.label }}</span>
          </a>
        }
      </mat-nav-list>
    </nav>
  `,
  styles: `
    .role {
      padding: 16px 24px 4px;
      font: var(--mat-sys-label-medium);
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--app-muted);
    }
  `,
})
export class Sidebar {
  private readonly auth = inject(AuthService);

  /** Fires when a link is chosen, so the shell can close the drawer on small screens. */
  readonly navigated = output<void>();

  protected readonly items = computed(() => {
    const role = this.auth.role();
    return role ? NAV_ITEMS[role] : [];
  });
  protected readonly roleLabel = computed(() => {
    const role = this.auth.role();
    return role ? ROLE_LABELS[role] : null;
  });
}
