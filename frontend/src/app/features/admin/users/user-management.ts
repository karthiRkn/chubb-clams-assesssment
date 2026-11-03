import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { catchError, EMPTY, of, switchMap, tap } from 'rxjs';
import { PageResponse, User } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { UserService } from '../../../services/user.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { ActiveBadge, RoleBadge } from '../../../shared/user-badges/user-badges';
import { errorMessage } from '../../../utils/error-message';

/**
 * Every account, paged by the server (GET /users, ordered by username).
 * Activate / deactivate is PUT /users/{username}/active; role changes live on the user's own page (and never apply to a
 * claimant, see utils/role-policy.ts). An administrator cannot switch off or change their own account (the backend answers
 * 409), so those actions are not offered for their own row.
 */
@Component({
  selector: 'app-user-management',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatTableModule,
    RouterLink,
    ActiveBadge,
    EmptyState,
    LoadingSpinner,
    PageHeader,
    RoleBadge,
  ],
  template: `
    <div class="page">
      <app-page-header title="User management" subtitle="Create staff accounts, change staff roles, and switch access on or off.">
        <a mat-flat-button routerLink="/admin/users/new"><mat-icon>person_add</mat-icon>Create user</a>
      </app-page-header>

      <form class="find" (submit)="$event.preventDefault(); find()" role="search">
        <mat-form-field class="find-field">
          <mat-label>Find a user by login name</mat-label>
          <input matInput [formControl]="search" autocapitalize="none" spellcheck="false" autocomplete="off" />
          <mat-icon matSuffix aria-hidden="true">search</mat-icon>
        </mat-form-field>
        <button mat-stroked-button type="submit" [disabled]="!search.value.trim()">Open</button>
      </form>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load users" [message]="message">
          <button mat-flat-button type="button" (click)="reload()">Try again</button>
        </app-empty-state>
      } @else if (data(); as result) {
        @if (result.content.length === 0) {
          <app-empty-state icon="group_off" title="No users" message="Create the first account to get started.">
            <a mat-flat-button routerLink="/admin/users/new">Create user</a>
          </app-empty-state>
        } @else {
          <div class="table-wrap" [class.stale]="loading()">
            <table mat-table [dataSource]="result.content" aria-label="Users">
              <ng-container matColumnDef="username">
                <th mat-header-cell *matHeaderCellDef>User</th>
                <td mat-cell *matCellDef="let u">
                  <a [routerLink]="['/admin/users', u.username]"><strong>{{ u.username }}</strong></a>
                  @if (isSelf(u)) {
                    <span class="muted"> (you)</span>
                  }
                </td>
              </ng-container>
              <ng-container matColumnDef="role">
                <th mat-header-cell *matHeaderCellDef>Role</th>
                <td mat-cell *matCellDef="let u"><app-role-badge [role]="u.role" /></td>
              </ng-container>
              <ng-container matColumnDef="active">
                <th mat-header-cell *matHeaderCellDef>Status</th>
                <td mat-cell *matCellDef="let u"><app-active-badge [active]="u.active" /></td>
              </ng-container>
              <ng-container matColumnDef="createdAt">
                <th mat-header-cell *matHeaderCellDef class="hide-sm">Created</th>
                <td mat-cell *matCellDef="let u" class="hide-sm nowrap">{{ u.createdAt | date: 'mediumDate' }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef class="end">Actions</th>
                <td mat-cell *matCellDef="let u" class="end nowrap">
                  <a mat-button [routerLink]="['/admin/users', u.username]">Manage</a>
                  @if (!isSelf(u)) {
                    @if (u.active) {
                      <button mat-button class="btn-warn" type="button" (click)="toggle(u)">Deactivate</button>
                    } @else {
                      <button mat-button type="button" (click)="toggle(u)">Activate</button>
                    }
                  }
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="columns"></tr>
              <tr mat-row *matRowDef="let row; columns: columns"></tr>
            </table>
          </div>
          <mat-paginator
            [length]="result.totalElements"
            [pageIndex]="result.page"
            [pageSize]="result.size"
            [pageSizeOptions]="[10, 20, 50, 100]"
            showFirstLastButtons
            aria-label="Select page of users"
            (page)="onPage($event)"
          />
        }
      } @else {
        <app-loading-spinner label="Loading users…" />
      }
    </div>
  `,
  styles: `
    .find {
      display: flex;
      align-items: flex-start;
      gap: 12px;
    }
    .find-field {
      flex: 1 1 280px;
      max-width: 420px;
    }
    .find button {
      margin-top: 8px;
    }
    .end {
      text-align: right;
    }
    .stale {
      opacity: 0.6;
      transition: opacity 0.15s;
    }
  `,
})
export class UserManagement {
  private readonly users = inject(UserService);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly columns = ['username', 'role', 'active', 'createdAt', 'actions'];
  protected readonly search = new FormControl('', { nonNullable: true });

  private readonly page = signal(0);
  private readonly size = signal(20);
  private readonly tick = signal(0);
  protected readonly data = signal<PageResponse<User> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly query = computed(() => ({ page: this.page(), size: this.size(), tick: this.tick() }));

  constructor() {
    toObservable(this.query)
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap(({ page, size }) =>
          this.users.list(page, size).pipe(
            catchError((err: unknown) => {
              this.error.set(errorMessage(err));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        this.loading.set(false);
        if (result) {
          this.data.set(result);
        }
      });
  }

  protected isSelf(user: User): boolean {
    return user.username === this.auth.username();
  }

  protected reload(): void {
    this.tick.update((n) => n + 1);
  }

  protected onPage(event: PageEvent): void {
    this.page.set(event.pageIndex);
    this.size.set(event.pageSize);
  }

  /** GET /users/{username}: only exact login names exist as a lookup, so this opens the user if there is one. */
  protected find(): void {
    const name = this.search.value.trim().toLowerCase();
    if (!name) {
      return;
    }
    // A 404 USER_NOT_FOUND is shown by the error interceptor.
    this.users.get(name).subscribe((user) => void this.router.navigate(['/admin/users', user.username]));
  }

  protected toggle(user: User): void {
    const activate = !user.active;
    this.confirm
      .confirm({
        title: activate ? `Activate ${user.username}?` : `Deactivate ${user.username}?`,
        message: activate
          ? 'They will be able to sign in again.'
          : 'They will no longer be able to sign in. A session that is already open keeps working until it expires (up to an hour).',
        confirmLabel: activate ? 'Activate' : 'Deactivate',
        tone: activate ? 'primary' : 'warn',
        icon: activate ? 'check_circle' : 'block',
      })
      .pipe(switchMap((ok) => (ok ? this.users.setActive(user.username, activate) : EMPTY)))
      .subscribe(() => {
        this.notifications.success(`${user.username} was ${activate ? 'activated' : 'deactivated'}.`);
        this.reload();
      });
  }
}
