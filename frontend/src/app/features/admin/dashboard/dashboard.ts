import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { EMPTY, expand, map, reduce } from 'rxjs';
import { ROLE_LABELS, ROLES, User } from '../../../models';
import { UserService } from '../../../services/user.service';
import { BarChart } from '../../../shared/chart/bar-chart';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatCard } from '../../../shared/stat-card/stat-card';
import { ActiveBadge, RoleBadge } from '../../../shared/user-badges/user-badges';
import { errorMessage } from '../../../utils/error-message';

/** Stop after this many pages so a huge user table cannot turn a dashboard into a download. */
const MAX_PAGES = 20;
const PAGE_SIZE = 100;

interface AdminData {
  users: User[];
  total: number;
  /** True when there were more users than the dashboard reads (counts are then a lower bound). */
  truncated: boolean;
}

/**
 * Admin home: how many people have access, in which role, and how many accounts are switched off.
 * GET /users has no filter or count-by-role, so this reads the pages (100 at a time) and counts them here.
 */
@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    ActiveBadge,
    BarChart,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    RoleBadge,
    StatCard,
  ],
  template: `
    <div class="page">
      <app-page-header title="Administration" subtitle="Who can sign in, and what they can do.">
        <a mat-stroked-button routerLink="/admin/users"><mat-icon>manage_accounts</mat-icon>Manage users</a>
        <a mat-flat-button routerLink="/admin/users/new"><mat-icon>person_add</mat-icon>Create user</a>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load users" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (data(); as d) {
        <div class="stat-grid">
          <app-stat-card label="Users" [value]="d.total" icon="groups" link="/admin/users" />
          <app-stat-card label="Active" [value]="active()" icon="check_circle" tone="good" hint="Can sign in" />
          <app-stat-card
            label="Inactive"
            [value]="inactive()"
            icon="block"
            [tone]="inactive() > 0 ? 'attention' : 'default'"
            hint="Switched off"
          />
          <app-stat-card label="Administrators" [value]="counts()[3]" icon="admin_panel_settings" />
        </div>

        @if (d.truncated) {
          <p class="muted">Showing counts for the first {{ d.users.length }} of {{ d.total }} users.</p>
        }

        <div class="two-col">
          <section class="panel" aria-label="Users by role">
            <app-bar-chart
              title="Users by role"
              ariaLabel="Horizontal bar chart of the number of users in each role"
              categoryLabel="Role"
              [labels]="roleLabels"
              [series]="[{ name: 'Users', values: counts(), slot: 0 }]"
            />
          </section>

          <section class="panel" aria-label="Newest users">
            <div class="panel-head">
              <h2>Newest accounts</h2>
              <a mat-button routerLink="/admin/users">View all</a>
            </div>
            <ul class="rows">
              @for (user of newest(); track user.username) {
                <li>
                  <a class="row" [routerLink]="['/admin/users', user.username]">
                    <span class="main">
                      <span class="name">{{ user.username }}</span>
                      <span class="muted">{{ user.createdAt | date: 'mediumDate' }}</span>
                    </span>
                    <app-role-badge [role]="user.role" />
                    <app-active-badge [active]="user.active" />
                  </a>
                </li>
              }
            </ul>
          </section>
        </div>
      } @else {
        <app-loading-spinner label="Loading users…" />
      }
    </div>
  `,
  styles: `
    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    .panel-head h2 {
      margin: 0;
    }
    .rows {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      align-items: center;
      gap: 12px;
      padding: 10px 8px;
      border-top: 1px solid var(--app-border);
      color: inherit;
      text-decoration: none;
    }
    li:first-child .row {
      border-top: 0;
    }
    .row:hover {
      background: var(--mat-sys-surface-container-low);
    }
    .main {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .name {
      font-weight: 500;
      overflow-wrap: anywhere;
    }
    @media (max-width: 599px) {
      .row {
        grid-template-columns: minmax(0, 1fr) auto;
      }
      .row app-role-badge {
        display: none;
      }
    }
  `,
})
export class AdminDashboard implements OnInit {
  private readonly users = inject(UserService);

  protected readonly roleLabels = ROLES.map((role) => ROLE_LABELS[role]);
  protected readonly data = signal<AdminData | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly active = computed(() => this.data()?.users.filter((u) => u.active).length ?? 0);
  protected readonly inactive = computed(() => this.data()?.users.filter((u) => !u.active).length ?? 0);
  /** Users per role, in the order of ROLES (so index 3 is ADMIN). */
  protected readonly counts = computed(() =>
    ROLES.map((role) => this.data()?.users.filter((u) => u.role === role).length ?? 0),
  );
  protected readonly newest = computed(() =>
    [...(this.data()?.users ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, 5),
  );

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.users
      .list(0, PAGE_SIZE)
      .pipe(
        // Keep asking for the next page while there is one (up to MAX_PAGES).
        expand((page) => (page.page + 1 < page.totalPages && page.page + 1 < MAX_PAGES ? this.users.list(page.page + 1, PAGE_SIZE) : EMPTY)),
        reduce(
          (all, page) => ({ users: [...all.users, ...page.content], total: page.totalElements }),
          { users: [] as User[], total: 0 },
        ),
        map((all): AdminData => ({ ...all, truncated: all.users.length < all.total })),
      )
      .subscribe({
        next: (data) => this.data.set(data),
        error: (err: unknown) => this.error.set(errorMessage(err)),
      });
  }
}
