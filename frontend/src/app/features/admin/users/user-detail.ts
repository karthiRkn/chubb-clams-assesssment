import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, of, switchMap, tap } from 'rxjs';
import { User } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { UserService } from '../../../services/user.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { ActiveBadge, RoleBadge } from '../../../shared/user-badges/user-badges';
import { errorMessage } from '../../../utils/error-message';
import { canChangeRole, CLAIMANT_ROLE_FIXED } from '../../../utils/role-policy';

/**
 * User Detail: GET /users/{username}, plus activate / deactivate (PUT .../active). The role is changed on the Edit User page,
 * which is only offered where the role policy allows a change (never for a claimant).
 * An administrator cannot deactivate themselves (409 CANNOT_DEACTIVATE_SELF), so that control is replaced by an explanation on
 * their own account. A deactivated person's open session keeps working until their token expires (the backend has no revocation).
 */
@Component({
  selector: 'app-user-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    ActiveBadge,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    RoleBadge,
  ],
  template: `
    <div class="page narrow">
      @if (error(); as err) {
        <app-page-header title="User" backLink="/admin/users" backLabel="User management" />
        <app-empty-state
          [icon]="err.notFound ? 'person_off' : 'error_outline'"
          [title]="err.notFound ? 'User not found' : 'Could not load this user'"
          [message]="err.message"
        >
          <a mat-flat-button routerLink="/admin/users">Back to users</a>
        </app-empty-state>
      } @else if (user(); as u) {
        <app-page-header [title]="u.username" subtitle="User detail" backLink="/admin/users" backLabel="User management">
          @if (canChangeRole(u.role)) {
            <a mat-stroked-button routerLink="edit"><mat-icon>edit</mat-icon>Edit user</a>
          }
        </app-page-header>

        <section class="panel" aria-label="Account">
          <dl class="grid">
            <div>
              <dt>Login name</dt>
              <dd class="mono">{{ u.username }}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd><app-role-badge [role]="u.role" /></dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd><app-active-badge [active]="u.active" /></dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{{ u.createdAt | date: 'medium' }}</dd>
            </div>
          </dl>
          @if (!canChangeRole(u.role)) {
            <p class="muted self"><mat-icon aria-hidden="true">lock</mat-icon>{{ claimantFixed }}</p>
          }
        </section>

        <section class="panel" aria-label="Account status">
          <h2>{{ u.active ? 'Deactivate account' : 'Activate account' }}</h2>
          @if (isSelf()) {
            <p class="muted self"><mat-icon aria-hidden="true">lock</mat-icon>You cannot deactivate your own account.</p>
          } @else if (u.active) {
            <p class="muted lead">
              {{ u.username }} will no longer be able to sign in. A session that is already open keeps working until it expires.
            </p>
            <button mat-stroked-button class="btn-warn" type="button" [disabled]="busy()" (click)="setActive(u, false)">
              <mat-icon>block</mat-icon>Deactivate account
            </button>
          } @else {
            <p class="muted lead">{{ u.username }} is switched off and cannot sign in. Activate the account to restore access.</p>
            <button mat-flat-button type="button" [disabled]="busy()" (click)="setActive(u, true)">
              <mat-icon>check_circle</mat-icon>Activate account
            </button>
          }
        </section>
      } @else {
        <app-loading-spinner label="Loading user…" />
      }
    </div>
  `,
  styles: `
    .narrow {
      max-width: 760px;
    }
    h2 {
      font: var(--mat-sys-title-medium);
      margin-bottom: 8px;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 16px 24px;
      margin: 0;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--app-muted);
      margin-bottom: 4px;
    }
    dd {
      margin: 0;
    }
    .lead {
      margin-bottom: 12px;
    }
    .self {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .grid + .self {
      margin-top: 16px;
    }
  `,
})
export class UserDetail {
  private readonly users = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  /** The `:username` route parameter. */
  readonly username = input.required<string>();

  protected readonly user = signal<User | null>(null);
  protected readonly error = signal<{ message: string; notFound: boolean } | null>(null);
  protected readonly busy = signal(false);
  protected readonly isSelf = computed(() => this.user()?.username === this.auth.username());
  protected readonly canChangeRole = canChangeRole;
  protected readonly claimantFixed = CLAIMANT_ROLE_FIXED;

  constructor() {
    toObservable(this.username)
      .pipe(
        tap(() => {
          this.user.set(null);
          this.error.set(null);
        }),
        switchMap((name) =>
          this.users.get(name).pipe(
            catchError((err: unknown) => {
              this.error.set({ message: errorMessage(err), notFound: err instanceof HttpErrorResponse && err.status === 404 });
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((user) => {
        if (user) {
          this.user.set(user);
        }
      });
  }

  protected setActive(user: User, active: boolean): void {
    this.confirm
      .confirm({
        title: active ? `Activate ${user.username}?` : `Deactivate ${user.username}?`,
        message: active
          ? 'They will be able to sign in again.'
          : 'They will no longer be able to sign in. Any session that is already open keeps working until it expires.',
        confirmLabel: active ? 'Activate' : 'Deactivate',
        tone: active ? 'primary' : 'warn',
        icon: active ? 'check_circle' : 'block',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.busy.set(true);
          return this.users.setActive(user.username, active);
        }),
      )
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.user.set(updated);
          this.notifications.success(`${updated.username} was ${active ? 'activated' : 'deactivated'}.`);
        },
        error: () => this.busy.set(false),
      });
  }
}
