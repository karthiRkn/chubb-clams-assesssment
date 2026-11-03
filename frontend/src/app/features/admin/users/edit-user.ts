import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, of, switchMap, tap } from 'rxjs';
import { ROLE_LABELS, Role, User } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { UserService } from '../../../services/user.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { RoleBadge } from '../../../shared/user-badges/user-badges';
import { errorMessage } from '../../../utils/error-message';
import {
  allowedRoleChange,
  canChangeRole,
  CLAIMANT_ROLE_FIXED,
  isRoleChangeAllowed,
  roleChoices,
} from '../../../utils/role-policy';

/**
 * Edit User: change a staff member's role, PUT /users/{username}/role.
 * The role policy (utils/role-policy.ts) decides what is offered: a claimant's role is fixed, so the control is replaced by an
 * explanation; a staff account can move between the staff roles but never becomes a claimant.
 * An administrator cannot change their own role (409 CANNOT_CHANGE_OWN_ROLE), so the control is replaced by an explanation on
 * their own account. The new role applies at the person's next sign-in: a token already issued keeps its old role until it
 * expires (the backend has no revocation).
 */
@Component({
  selector: 'app-edit-user',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    RouterLink,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    RoleBadge,
  ],
  template: `
    <div class="page narrow">
      @if (error(); as err) {
        <app-page-header title="Edit user" backLink="/admin/users" backLabel="User management" />
        <app-empty-state
          [icon]="err.notFound ? 'person_off' : 'error_outline'"
          [title]="err.notFound ? 'User not found' : 'Could not load this user'"
          [message]="err.message"
        >
          <a mat-flat-button routerLink="/admin/users">Back to users</a>
        </app-empty-state>
      } @else if (user(); as u) {
        <app-page-header
          [title]="'Edit ' + u.username"
          subtitle="Change what this person is allowed to do."
          [backLink]="['/admin/users', u.username]"
          backLabel="User detail"
        />

        <section class="panel" aria-label="Role">
          <h2>Role</h2>
          <p class="current">Current role: <app-role-badge [role]="u.role" /></p>

          @if (isSelf()) {
            <p class="muted self"><mat-icon aria-hidden="true">lock</mat-icon>You cannot change your own role. Ask another administrator.</p>
          } @else if (!canChange()) {
            <p class="muted self"><mat-icon aria-hidden="true">lock</mat-icon>{{ claimantFixed }}</p>
            <a mat-stroked-button class="alt" routerLink="/admin/users/new"><mat-icon>person_add</mat-icon>Create a staff user</a>
          } @else {
            <p class="muted lead">The new role applies the next time {{ u.username }} signs in.</p>
            <div class="inline">
              <mat-form-field class="role-field">
                <mat-label>New role</mat-label>
                <mat-select [formControl]="role">
                  @for (r of choices(); track r) {
                    <mat-option [value]="r">{{ roleLabels[r] }}</mat-option>
                  }
                </mat-select>
                <mat-hint>Staff roles only. A staff account cannot become a claimant.</mat-hint>
                @if (role.hasError('roleTransition')) {
                  <mat-error>That role change is not allowed for this account.</mat-error>
                }
              </mat-form-field>
              <button mat-flat-button type="button" [disabled]="busy() || role.invalid || role.value === u.role" (click)="changeRole(u)">
                <mat-icon>swap_horiz</mat-icon>Change role
              </button>
            </div>
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
    .current {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;
    }
    .lead {
      margin-bottom: 12px;
    }
    .self {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .inline {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 12px;
    }
    .role-field {
      width: 240px;
      max-width: 100%;
    }
    .inline button {
      margin-top: 8px;
    }
    .alt {
      margin-top: 12px;
    }
  `,
})
export class EditUser {
  private readonly users = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  /** The `:username` route parameter. */
  readonly username = input.required<string>();

  protected readonly roleLabels = ROLE_LABELS;
  protected readonly claimantFixed = CLAIMANT_ROLE_FIXED;

  protected readonly user = signal<User | null>(null);
  protected readonly error = signal<{ message: string; notFound: boolean } | null>(null);
  protected readonly busy = signal(false);
  protected readonly isSelf = computed(() => this.user()?.username === this.auth.username());
  /** False for a claimant: there is no role to move to, so no picker is shown. */
  protected readonly canChange = computed(() => {
    const user = this.user();
    return user !== null && canChangeRole(user.role);
  });
  /** The picker lists the current role and the roles the policy allows from it. */
  protected readonly choices = computed(() => {
    const user = this.user();
    return user ? roleChoices(user.role) : [];
  });

  protected readonly role = new FormControl<Role>('CLAIMS_OFFICER', {
    nonNullable: true,
    validators: [Validators.required, allowedRoleChange(() => this.user()?.role ?? null)],
  });

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

    // Start the picker on the person's current role.
    effect(() => {
      const user = this.user();
      if (user) {
        untracked(() => this.role.setValue(user.role));
      }
    });
  }

  protected changeRole(user: User): void {
    const next = this.role.value;
    // The button is disabled for these, but a role must never be sent that the policy refuses (for example a claimant to staff).
    if (!isRoleChangeAllowed(user.role, next)) {
      this.role.markAsTouched();
      return;
    }
    this.confirm
      .confirm({
        title: 'Change role?',
        message: `${user.username} will change from ${ROLE_LABELS[user.role]} to ${ROLE_LABELS[next]}. It applies the next time they sign in.`,
        confirmLabel: 'Change role',
        icon: 'swap_horiz',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.busy.set(true);
          return this.users.changeRole(user.username, next);
        }),
      )
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.notifications.success(`${updated.username} is now ${ROLE_LABELS[updated.role]}.`);
          void this.router.navigate(['/admin/users', updated.username]);
        },
        error: () => this.busy.set(false),
      });
  }
}
