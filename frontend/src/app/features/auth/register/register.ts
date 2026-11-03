import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router, RouterLink } from '@angular/router';
import { USERNAME_HINT, USERNAME_PATTERN } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { NotificationService } from '../../../services/notification.service';
import { errorCode, errorMessage } from '../../../utils/error-message';
import { maxBytes } from '../../../utils/validators';

const passwordsMatch: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const password = group.get('password')?.value;
  const confirm = group.get('confirm')?.value;
  return password && confirm && password !== confirm ? { mismatch: true } : null;
};

/** Self-service sign-up. The backend always creates a CLAIMANT, whatever is sent. */
@Component({
  selector: 'app-register',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <main class="auth-page">
      <section class="auth-card" aria-labelledby="register-title">
        <div class="brand">
          <mat-icon aria-hidden="true">shield</mat-icon>
          <span>Chubb APAC Claims</span>
        </div>
        <h1 id="register-title">Create your account</h1>
        <p class="muted">Claimant accounts let you report an incident and follow your claim.</p>

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          @if (formError(); as message) {
            <div class="alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon>{{ message }}</div>
          }

          <mat-form-field subscriptSizing="dynamic">
            <mat-label>Username</mat-label>
            <input matInput formControlName="username" autocomplete="username" autocapitalize="none" spellcheck="false" />
            <mat-hint>{{ usernameHint }}</mat-hint>
            @if (form.controls.username.hasError('required')) {
              <mat-error>Choose a username.</mat-error>
            } @else if (form.controls.username.hasError('pattern')) {
              <mat-error>{{ usernameHint }}</mat-error>
            } @else if (form.controls.username.hasError('taken')) {
              <mat-error>That username is already taken.</mat-error>
            }
          </mat-form-field>

          <mat-form-field>
            <mat-label>Password</mat-label>
            <input matInput type="password" formControlName="password" autocomplete="new-password" />
            <mat-hint>8 to 72 characters</mat-hint>
            @if (form.controls.password.hasError('required')) {
              <mat-error>Choose a password.</mat-error>
            } @else if (form.controls.password.hasError('minlength')) {
              <mat-error>Use at least 8 characters.</mat-error>
            } @else if (form.controls.password.hasError('maxBytes')) {
              <mat-error>That password is too long (72 bytes at most).</mat-error>
            }
          </mat-form-field>

          <mat-form-field>
            <mat-label>Confirm password</mat-label>
            <input matInput type="password" formControlName="confirm" autocomplete="new-password" />
            @if (form.controls.confirm.hasError('required')) {
              <mat-error>Repeat your password.</mat-error>
            } @else if (form.hasError('mismatch')) {
              <mat-error>The passwords do not match.</mat-error>
            }
          </mat-form-field>

          <button mat-flat-button class="submit" type="submit" [disabled]="submitting()">
            @if (submitting()) {
              <mat-progress-spinner mode="indeterminate" diameter="20" />
              Creating account…
            } @else {
              Create account
            }
          </button>
        </form>

        <p class="switch">Already registered? <a routerLink="/login">Sign in</a></p>
      </section>
    </main>
  `,
  styles: `
    .brand {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 24px;
      font: var(--mat-sys-title-medium);
      color: var(--mat-sys-primary);
    }
    h1 {
      font: var(--mat-sys-headline-medium);
    }
    form {
      display: flex;
      flex-direction: column;
      margin-top: 24px;
    }
    .submit {
      height: 48px;
      gap: 8px;
    }
    .alert {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 16px;
      padding: 12px;
      border-radius: 8px;
      background: var(--status-rejected-bg);
      color: var(--status-rejected-fg);
    }
    .switch {
      margin-top: 24px;
      text-align: center;
    }
  `,
})
export class Register {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  protected readonly usernameHint = USERNAME_HINT;
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group(
    {
      username: ['', [Validators.required, Validators.pattern(USERNAME_PATTERN)]],
      password: ['', [Validators.required, Validators.minLength(8), maxBytes(72)]],
      confirm: ['', [Validators.required]],
    },
    { validators: passwordsMatch },
  );

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.formError.set(null);

    const { username, password } = this.form.getRawValue();
    this.auth.register({ username, password }).subscribe({
      next: () => {
        this.notifications.success('Account created. You can sign in now.');
        void this.router.navigate(['/login']);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        if (err instanceof HttpErrorResponse && errorCode(err) === 'USERNAME_TAKEN') {
          this.form.controls.username.setErrors({ taken: true });
          this.form.controls.username.markAsTouched();
        } else {
          this.formError.set(errorMessage(err, 'Could not create the account. Please try again.'));
        }
      },
    });
  }
}
