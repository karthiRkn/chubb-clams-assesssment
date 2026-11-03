import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, switchMap } from 'rxjs';
import { ROLE_LABELS, Role, USERNAME_HINT, USERNAME_PATTERN } from '../../../models';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { UserService } from '../../../services/user.service';
import { PageHeader } from '../../../shared/page-header/page-header';
import { errorCode } from '../../../utils/error-message';
import { PROVISIONABLE_ROLES, provisionableRole } from '../../../utils/role-policy';
import { maxBytes } from '../../../utils/validators';

/**
 * Create a staff user (claims officer, manager or administrator): POST /users. New accounts are active; the administrator
 * sets the first password. Claimants are not created here: they register themselves (and stay claimants, see utils/role-policy.ts).
 */
@Component({
  selector: 'app-create-user',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    PageHeader,
  ],
  template: `
    <div class="page narrow">
      <app-page-header
        title="Create user"
        subtitle="Add a claims officer, manager or administrator. Claimants register themselves."
        backLink="/admin/users"
        backLabel="User management"
      />

      <form class="panel" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <mat-form-field subscriptSizing="dynamic">
          <mat-label>Login name</mat-label>
          <input matInput formControlName="username" autocomplete="off" autocapitalize="none" spellcheck="false" />
          <mat-hint>{{ usernameHint }}</mat-hint>
          @if (form.controls.username.hasError('required')) {
            <mat-error>Enter a login name.</mat-error>
          } @else if (form.controls.username.hasError('pattern')) {
            <mat-error>{{ usernameHint }}</mat-error>
          } @else if (form.controls.username.hasError('taken')) {
            <mat-error>That login name is already taken.</mat-error>
          }
        </mat-form-field>

        <mat-form-field>
          <mat-label>Initial password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="new-password" />
          <mat-hint>8 to 72 characters</mat-hint>
          @if (form.controls.password.hasError('required')) {
            <mat-error>Enter a password.</mat-error>
          } @else if (form.controls.password.hasError('minlength')) {
            <mat-error>Use at least 8 characters.</mat-error>
          } @else if (form.controls.password.hasError('maxBytes')) {
            <mat-error>That password is too long (72 bytes at most).</mat-error>
          }
        </mat-form-field>

        <mat-form-field>
          <mat-label>Role</mat-label>
          <mat-select formControlName="role">
            @for (role of roles; track role) {
              <mat-option [value]="role">{{ roleLabels[role] }}</mat-option>
            }
          </mat-select>
          <mat-hint>Staff roles only. Claimants sign themselves up on the register page.</mat-hint>
          @if (form.controls.role.hasError('required')) {
            <mat-error>Choose a role.</mat-error>
          } @else if (form.controls.role.hasError('notProvisionable')) {
            <mat-error>Choose a claims officer, manager or administrator.</mat-error>
          }
        </mat-form-field>

        <div class="actions-end">
          <a mat-button routerLink="/admin/users">Cancel</a>
          <button mat-flat-button type="submit" [disabled]="submitting()">
            <mat-icon>person_add</mat-icon>{{ submitting() ? 'Creating…' : 'Create user' }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: `
    .narrow {
      max-width: 620px;
    }
    form {
      display: flex;
      flex-direction: column;
    }
  `,
})
export class CreateUser {
  private readonly fb = inject(FormBuilder);
  private readonly users = inject(UserService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly roles = PROVISIONABLE_ROLES;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly usernameHint = USERNAME_HINT;
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.pattern(USERNAME_PATTERN)]],
    password: ['', [Validators.required, Validators.minLength(8), maxBytes(72)]],
    role: ['CLAIMS_OFFICER' as Role, [Validators.required, provisionableRole]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password, role } = this.form.getRawValue();
    this.confirm
      .confirm({
        title: 'Create this user?',
        message: `${username} will be created as ${ROLE_LABELS[role]} and can sign in straight away.`,
        confirmLabel: 'Create user',
        icon: 'person_add',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.submitting.set(true);
          return this.users.create({ username, password, role });
        }),
      )
      .subscribe({
        next: (user) => {
          this.notifications.success(`${user.username} was created.`);
          void this.router.navigate(['/admin/users', user.username]);
        },
        error: (err: unknown) => {
          this.submitting.set(false);
          if (err instanceof HttpErrorResponse && errorCode(err) === 'USERNAME_TAKEN') {
            this.form.controls.username.setErrors({ taken: true });
            this.form.controls.username.markAsTouched();
          }
        },
      });
  }
}
