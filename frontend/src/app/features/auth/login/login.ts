import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router, RouterLink } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { AuthService } from '../../../services/auth.service';
import { NotificationService } from '../../../services/notification.service';
import { errorMessage } from '../../../utils/error-message';

/** The backend's seeded demo accounts (README of the backend, "Getting a token"). Offered as a shortcut in development only. */
const DEMO_ACCOUNTS = [
  { label: 'Claimant', username: 'claimant1' },
  { label: 'Claims officer', username: 'officer1' },
  { label: 'Manager', username: 'manager1' },
  { label: 'Admin', username: 'admin1' },
] as const;
const DEMO_PASSWORD = 'password123';

/** Only same-site, absolute-path return URLs are followed after login (blocks `//evil.example` and `https://...`). */
function safeReturnUrl(url: string | undefined): string | null {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login') ? url : null;
}

@Component({
  selector: 'app-login',
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
      <section class="auth-card" aria-labelledby="login-title">
        <div class="brand">
          <mat-icon aria-hidden="true">shield</mat-icon>
          <span>Chubb APAC Claims</span>
        </div>
        <h1 id="login-title">Sign in</h1>
        <p class="muted">Use your claims account to continue.</p>

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          @if (formError(); as message) {
            <div class="alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon>{{ message }}</div>
          }

          <mat-form-field>
            <mat-label>Username</mat-label>
            <input matInput formControlName="username" autocomplete="username" autocapitalize="none" spellcheck="false" />
            @if (form.controls.username.hasError('required')) {
              <mat-error>Enter your username.</mat-error>
            }
          </mat-form-field>

          <mat-form-field>
            <mat-label>Password</mat-label>
            <input
              matInput
              formControlName="password"
              autocomplete="current-password"
              [type]="showPassword() ? 'text' : 'password'"
            />
            <button
              mat-icon-button
              matSuffix
              type="button"
              (click)="showPassword.set(!showPassword())"
              [attr.aria-label]="showPassword() ? 'Hide password' : 'Show password'"
              [attr.aria-pressed]="showPassword()"
            >
              <mat-icon>{{ showPassword() ? 'visibility_off' : 'visibility' }}</mat-icon>
            </button>
            @if (form.controls.password.hasError('required')) {
              <mat-error>Enter your password.</mat-error>
            }
          </mat-form-field>

          <button mat-flat-button class="submit" type="submit" [disabled]="submitting()">
            @if (submitting()) {
              <mat-progress-spinner mode="indeterminate" diameter="20" />
              Signing in…
            } @else {
              Sign in
            }
          </button>
        </form>

        <p class="switch">New claimant? <a routerLink="/register">Create an account</a></p>

        @if (!production) {
          <div class="demo">
            <div class="muted demo-title">Demo accounts (development only)</div>
            <div class="actions">
              @for (account of demoAccounts; track account.username) {
                <button mat-stroked-button type="button" (click)="fillDemo(account.username)">{{ account.label }}</button>
              }
            </div>
          </div>
        }
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
    .demo {
      margin-top: 24px;
      padding-top: 16px;
      border-top: 1px solid var(--app-border);
    }
    .demo-title {
      margin-bottom: 8px;
      font: var(--mat-sys-label-medium);
    }
  `,
})
export class Login {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  /** Bound from the `?returnUrl=` query parameter (withComponentInputBinding). */
  readonly returnUrl = input<string>();

  protected readonly production = environment.production;
  protected readonly demoAccounts = DEMO_ACCOUNTS;
  protected readonly showPassword = signal(false);
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.formError.set(null);

    const { username, password } = this.form.getRawValue();
    this.auth.login({ username: username.trim(), password }).subscribe({
      next: (session) => {
        this.notifications.success(`Welcome back, ${session.username}.`);
        void this.router.navigateByUrl(safeReturnUrl(this.returnUrl()) ?? this.auth.homeRoute(session.role));
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.formError.set(
          err instanceof HttpErrorResponse && err.status === 401
            ? 'Incorrect username or password.'
            : errorMessage(err, 'Sign in failed. Please try again.'),
        );
      },
    });
  }

  protected fillDemo(username: string): void {
    this.form.setValue({ username, password: DEMO_PASSWORD });
    this.formError.set(null);
  }
}
